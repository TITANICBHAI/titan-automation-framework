package com.titan.automation.engine.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.titan.automation.R
import com.titan.automation.TitanApplication
import com.titan.automation.engine.playback.SimplePlaybackEngine
import com.titan.automation.ui.MainActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Posts and updates a persistent "TITAN Controls" notification that reflects
 * [SimplePlaybackEngine] state and provides a one-tap Stop action.
 *
 * Lifecycle: call [start] once (e.g. from [OverlayService.onCreate]), [release]
 * when the overlay is destroyed.  The notification is automatically removed on
 * [release] if the engine is no longer running.
 */
@Singleton
class ControlNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val simplePlayback: SimplePlaybackEngine
) {
    private val nm    = context.getSystemService(NotificationManager::class.java)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    fun start() {
        scope.launch {
            combine(
                simplePlayback.isPlaying,
                simplePlayback.currentMacroName,
                simplePlayback.completedLoops
            ) { playing, name, loops -> Triple(playing, name, loops) }
                .collect { (playing, name, loops) ->
                    nm.notify(NOTIF_ID, buildNotification(playing, name, loops))
                }
        }
    }

    fun release() {
        scope.cancel()
        if (!simplePlayback.isPlaying.value) nm.cancel(NOTIF_ID)
    }

    private fun buildNotification(
        playing: Boolean,
        macroName: String?,
        loops: Int
    ) = NotificationCompat.Builder(context, TitanApplication.CHANNEL_CONTROL)
        .setSmallIcon(R.drawable.ic_tile_titan)
        .setContentTitle(if (playing) "● TITAN Running" else "TITAN Ready")
        .setContentText(when {
            playing && macroName != null -> "$macroName  (loop $loops)"
            playing                      -> "Automation engine active"
            else                         -> "Tap to open"
        })
        .setOngoing(playing)
        .setShowWhen(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(openAppIntent())
        .apply {
            if (playing) addAction(
                android.R.drawable.ic_media_pause,
                "Stop All",
                stopIntent()
            )
        }
        .build()

    private fun openAppIntent() = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun stopIntent() = PendingIntent.getBroadcast(
        context, 0,
        Intent(ACTION_STOP_ALL).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val NOTIF_ID       = 2001
        const val ACTION_STOP_ALL = "com.titan.automation.ACTION_STOP_ALL"
    }
}
