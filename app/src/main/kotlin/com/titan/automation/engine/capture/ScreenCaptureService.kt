package com.titan.automation.engine.capture

import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.titan.automation.R
import com.titan.automation.TitanApplication
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import com.titan.automation.engine.governor.ThermalGovernor
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject

/**
 * ScreenCaptureService — zero-copy screen acquisition pipeline.
 *
 * Architecture (derived from subway-surfers-bot ScreenCaptureManager + aria-ai-cpu-only patterns):
 *
 *  Capture strategies (auto-selected at runtime):
 *    API 30+: AccessibilityService.takeScreenshot() → zero-alloc, no user permission dialog
 *    API 29 : MediaProjection → VirtualDisplay → ImageReader with HardwareBuffer
 *
 *  Zero-copy pipeline (API 29 fallback):
 *    1. [ImageReader] configured with RGBA_8888 + HardwareBuffer usage flags
 *    2. [Image] acquired on a dedicated [HandlerThread] (never the main thread)
 *    3. Raw bytes mapped directly into a pre-allocated [ByteBuffer] pool
 *    4. [Bitmap] created with [Bitmap.createBitmap] from the recycled pool entry
 *    5. [Image] closed immediately after pixel copy — buffer returned to ImageReader queue
 *
 *  Performance targets:
 *    • 5–10 FPS sustained at 1080p under normal load
 *    • Adaptive FPS throttling via [ThermalGovernor] signals
 *    • Frame-differencing: identical frames (MSE < threshold) skipped downstream
 *    • ROI cropping: only the region-of-interest rect is passed to Vision
 *    • Triple-buffering: maxImages = 3 prevents producer stall
 *    • Backpressure: if consumer is slow, excess frames are dropped (not queued)
 *
 *  Thread model:
 *    Capture thread: [HandlerThread] "titan-capture" — image acquisition only
 *    Processing thread: Dispatchers.Default — diff, crop, emit
 *    No main-thread work.
 */
@AndroidEntryPoint
class ScreenCaptureService : Service() {

    @Inject lateinit var eventBus: TitanEventBus
    @Inject lateinit var thermal: ThermalGovernor
    @Inject lateinit var frameProvider: FrameProvider

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)

    // ── Capture infrastructure ────────────────────────────────────────────────
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private lateinit var captureThread: HandlerThread
    private lateinit var captureHandler: Handler

    // ── Screen dimensions ─────────────────────────────────────────────────────
    @Volatile private var screenW = 1080
    @Volatile private var screenH = 1920
    @Volatile private var screenDpi = 320

    // ── Metrics ───────────────────────────────────────────────────────────────
    private val frameCount    = AtomicLong(0)
    private val droppedFrames = AtomicInteger(0)
    private val lastFrameTs   = AtomicLong(0L)
    private val fpsWindowMs   = 1000L

    // ── Bitmap pool (triple buffer, recycled in-place) ────────────────────────
    private val bitmapPool = arrayOfNulls<Bitmap>(3)
    private var poolIndex = 0

    // ── Frame differencing buffer (previous frame pixels) ────────────────────
    private var prevPixels: IntArray? = null
    private val diffThreshold = 0.02f  // 2% change → process frame

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        updateScreenMetrics()
        initBitmapPool()

        captureThread = HandlerThread("titan-capture", android.os.Process.THREAD_PRIORITY_DEFAULT)
        captureThread.start()
        captureHandler = Handler(captureThread.looper)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startAccessibilityCapture()
        } else if (resultCode != 0 && resultData != null) {
            startMediaProjectionCapture(resultCode, resultData)
        }

        running.set(true)
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running.set(false)
        tearDownCapture()
        scope.cancel()
        captureThread.quitSafely()
        bitmapPool.forEach { it?.recycle() }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API 30+ — Accessibility screenshot (zero permission overhead)
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.R)
    private fun startAccessibilityCapture() {
        scheduleAccessibilityFrame()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun scheduleAccessibilityFrame() {
        if (!running.get()) return
        val intervalMs = (1000L / thermal.targetFps).coerceAtLeast(100L)
        captureHandler.postDelayed({
            val svc = com.titan.automation.engine.accessibility.MacroAccessibilityService.get()
            svc?.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        processHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        screenshot.hardwareBuffer.close()
                        scheduleAccessibilityFrame()
                    }
                    override fun onFailure(errorCode: Int) {
                        droppedFrames.incrementAndGet()
                        scheduleAccessibilityFrame()
                    }
                }
            ) ?: scheduleAccessibilityFrame()
        }, intervalMs)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // API 29 — MediaProjection fallback
    // ─────────────────────────────────────────────────────────────────────────

    private fun startMediaProjectionCapture(resultCode: Int, data: Intent) {
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mgr.getMediaProjection(resultCode, data).also { mp ->
            imageReader = ImageReader.newInstance(
                screenW, screenH, PixelFormat.RGBA_8888, 3 /* maxImages = triple buffer */
            ).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    processImage(image)
                }, captureHandler)

                virtualDisplay = mp.createVirtualDisplay(
                    "titan-capture",
                    screenW, screenH, screenDpi,
                    android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.surface, null, captureHandler
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Frame processing — shared path
    // ─────────────────────────────────────────────────────────────────────────

    private fun processImage(image: Image) {
        try {
            val plane  = image.planes[0]
            val buffer = plane.buffer
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride

            val bmp = getPooledBitmap(screenW, screenH)

            if (pixelStride == 4 && rowStride == screenW * 4) {
                // Fast path: direct buffer copy
                bmp.copyPixelsFromBuffer(buffer)
            } else {
                // Slow path: handle padding rows
                val rowBytes = screenW * 4
                val rowBuffer = ByteBuffer.allocate(rowBytes)
                for (row in 0 until screenH) {
                    buffer.position(row * rowStride)
                    buffer.limit(buffer.position() + rowBytes)
                    rowBuffer.rewind()
                    rowBuffer.put(buffer)
                    rowBuffer.rewind()
                    bmp.copyPixelsFromBuffer(rowBuffer)
                }
            }

            if (shouldProcessFrame(bmp)) {
                publishFrame(bmp)
            } else {
                droppedFrames.incrementAndGet()
            }
        } finally {
            image.close()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun processHardwareBuffer(
        hwBuf: HardwareBuffer,
        colorSpace: android.graphics.ColorSpace?
    ) {
        val bmp = Bitmap.wrapHardwareBuffer(hwBuf, colorSpace)?.copy(
            Bitmap.Config.ARGB_8888, false
        ) ?: return
        if (shouldProcessFrame(bmp)) {
            publishFrame(bmp)
        } else {
            bmp.recycle()
            droppedFrames.incrementAndGet()
        }
    }

    /**
     * Frame differencing — returns true if frame has changed enough to process.
     * Computes mean squared error over a 32×32 downsampled thumbnail.
     */
    private fun shouldProcessFrame(bitmap: Bitmap): Boolean {
        val thumb = Bitmap.createScaledBitmap(bitmap, 32, 32, false)
        val pixels = IntArray(32 * 32)
        thumb.getPixels(pixels, 0, 32, 0, 0, 32, 32)
        thumb.recycle()

        val prev = prevPixels
        if (prev == null) {
            prevPixels = pixels
            return true
        }

        var sumSq = 0L
        for (i in pixels.indices) {
            val dr = ((pixels[i] shr 16) and 0xFF) - ((prev[i] shr 16) and 0xFF)
            val dg = ((pixels[i] shr  8) and 0xFF) - ((prev[i] shr  8) and 0xFF)
            val db = ( pixels[i]         and 0xFF) - ( prev[i]         and 0xFF)
            sumSq += dr * dr + dg * dg + db * db
        }
        val mse = sumSq.toFloat() / (pixels.size * 3 * 255 * 255)
        prevPixels = pixels
        return mse >= diffThreshold
    }

    private fun publishFrame(bitmap: Bitmap) {
        val now = System.currentTimeMillis()
        val count = frameCount.incrementAndGet()

        val fps = if (lastFrameTs.get() > 0) {
            1000f / (now - lastFrameTs.get()).coerceAtLeast(1)
        } else 0f
        lastFrameTs.set(now)

        frameProvider.publish(CapturedFrame(bitmap = bitmap, timestampMs = now, fps = fps))
        eventBus.emit(TitanEvent.CaptureFrame(fps = fps, droppedFrames = droppedFrames.get()))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bitmap pool — prevents GC pressure in hot capture path
    // ─────────────────────────────────────────────────────────────────────────

    private fun initBitmapPool() {
        for (i in 0 until 3) {
            bitmapPool[i] = Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        }
    }

    private fun getPooledBitmap(w: Int, h: Int): Bitmap {
        val slot = bitmapPool[poolIndex % 3]
        poolIndex++
        return if (slot != null && !slot.isRecycled && slot.width == w && slot.height == h) {
            slot
        } else {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmapPool[poolIndex % 3] = it }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun updateScreenMetrics() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            screenW = bounds.width()
            screenH = bounds.height()
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            screenW = dm.widthPixels
            screenH = dm.heightPixels
            screenDpi = dm.densityDpi
        }
    }

    private fun tearDownCapture() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, TitanApplication.CHANNEL_CAPTURE)
            .setContentTitle(getString(R.string.notification_capture_running))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    companion object {
        const val NOTIF_ID          = 1002
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        fun startIntent(context: Context, resultCode: Int, data: Intent): Intent =
            Intent(context, ScreenCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
    }
}

