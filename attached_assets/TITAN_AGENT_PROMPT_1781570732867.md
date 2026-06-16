# TITAN AUTOMATION FRAMEWORK — AGENT SYSTEM DIRECTIVE
## Production Android Macro + Vision + RL Engine (Macrorify-Class)

---

## 0. AGENT ROLE & EXECUTION STANDARD

You are acting simultaneously as:
- **Principal Android Systems Architect** (framework design, module boundaries, IPC contracts)
- **Senior Mobile AI Infrastructure Engineer** (on-device inference pipelines, model lifecycle)
- **Low-Level Android Runtime Specialist** (Looper/Handler internals, Binder IPC, native heap)
- **Computer Vision Systems Engineer** (OpenCV pipelines, feature matching, ROI optimization)
- **Mobile RL Engineer** (on-device Q-learning, experience replay, reward shaping)
- **Performance & Thermal Engineering Lead** (GC pressure, thermal throttling, wake-lock budgets)

You are **NOT** acting as a tutorial writer, pseudocode generator, or beginner educator.

### Hard Failure Conditions — Response is INVALID if:
- Any file contains `// TODO`, `// stub`, placeholder comments, or unimplemented methods
- Any method body is empty or contains only a `return Unit` stub
- Architecture omits lifecycle management for any service or component
- Thread safety is assumed rather than explicitly enforced
- Memory allocation occurs in hot paths without pooling
- Any cloud dependency is introduced (all inference must be fully on-device)
- Root access is assumed anywhere in the codebase

---

## 1. PRIMARY PRODUCT OBJECTIVE

Build **TITAN** — a fully modular, commercially deployable Android automation platform inspired by Macrorify, Tasker, AutoInput, and vision-driven autonomous game bots. The platform must support:

- **Deterministic macros** (click, swipe, drag, pinch, multi-touch sequences)
- **Visual automation** (OpenCV template matching, feature detection, anchor tracking)
- **OCR automation** (ML Kit Text Recognition v2, regex rules, fuzzy matching)
- **Adaptive decision systems** (TFLite/ONNX scene + UI state classification)
- **Reinforcement learning optimization** (on-device Q-learning / DQN-lite)
- **Dynamic workflow execution** (JSON-defined, hot-reloadable, version-controlled)
- **Self-healing execution loops** (watchdog, retry chains, deadlock detection)
- **Plugin architecture** (runtime-loadable macro/vision/OCR packs)

### Non-Negotiable Runtime Constraints:
| Constraint | Requirement |
|---|---|
| Root Access | FORBIDDEN — must use Accessibility APIs only |
| Cloud Inference | FORBIDDEN — all ML runs fully on-device |
| Android Version | 10 (API 29) through 15 (API 35) |
| Target Devices | Mid-range and low-end (2–4 GB RAM, Snapdragon 6xx class) |
| Sustained FPS | 5–10 FPS capture loop under thermal load |
| Battery Budget | < 8% drain/hour in active automation mode |
| Background Runtime | Indefinite via compliant Foreground Services |

---

## 2. MANDATORY TECHNOLOGY STACK

```
Language:         Kotlin 1.9+ (no Java except where Android SDK forces it)
Async:            Kotlin Coroutines 1.7+, Flow, SharedFlow, StateFlow
UI:               Jetpack Compose (UI config screens + overlay rendering)
Architecture:     Clean Architecture + MVVM + Repository Pattern
DI:               Hilt 2.x (preferred) OR Koin 3.x — choose one and be consistent
Database:         Room 2.6+ with encrypted DAO layer
Preferences:      Proto DataStore (typed schemas, no SharedPreferences)
Screen Capture:   MediaProjection + VirtualDisplay + ImageReader + HardwareBuffer
Gesture Inject:   AccessibilityService + GestureDescription
Computer Vision:  OpenCV Android SDK 4.8+
OCR:              Google ML Kit Text Recognition v2
Inference:        TensorFlow Lite 2.14+ OR ONNX Runtime Mobile 1.16+
Build System:     Gradle 8+ with Kotlin DSL (build.gradle.kts)
```

**Absolutely Forbidden:**
- React Native, Flutter, WebViews, Xamarin
- Firebase for any runtime logic (Analytics SDK is acceptable if isolated)
- Cloud-only ML inference APIs (Vision AI, Rekognition, etc.)
- `Thread.sleep()` on main thread or Dispatchers.Main for blocking ops
- `Bitmap.createBitmap()` inside the capture hot path without pooling
- Polling loops without exponential backoff or event-driven alternatives

---

## 3. PRE-IMPLEMENTATION: REPOSITORY EXTRACTION

Before generating any source file, clone and perform deep architectural extraction from the following repositories:

```bash
git clone https://github.com/TITANICBHAI/subway-surfers-bot /tmp/subway-surfers-bot
git clone https://github.com/TITANICBHAI/SmartAssistant /tmp/smart-assistant
git clone https://github.com/TITANICBHAI/aria-ai-cpu-only /tmp/aria-ai-cpu-only
```

### 3.1 — subway-surfers-bot: Extraction Targets

Locate and extract the following patterns, then **refactor** (not copy) them into TITAN:

| Pattern | Location Hint | TITAN Upgrade |
|---|---|---|
| Frame processing loop | Main bot loop / game loop class | Generalize into `FramePipeline` with backpressure-aware `Channel<Frame>` |
| State machine transitions | State enum + transition table | Upgrade to sealed class hierarchy with `StateFlow<MacroState>` transitions |
| Swipe/tap coordinate system | Touch event dispatch | Normalize to density-independent logical coordinates with DPI/orientation transform matrix |
| Frame differencing / skip logic | If present in bot loop | Promote to first-class `FrameDifferenceFilter` with configurable threshold |
| Object detection triggers | Template or pixel scan loop | Abstract into `DetectionStrategy` interface with pluggable implementations |
| Event scheduling | Any delay/timer logic | Replace with `delay()` in structured coroutine scope with cancellation support |
| Bitmap reuse | Any image processing | Formalize into `BitmapPool` with `inBitmap` BitmapFactory options |

### 3.2 — SmartAssistant: Extraction Targets

| Pattern | Location Hint | TITAN Upgrade |
|---|---|---|
| Background service orchestration | Service class(es) | Upgrade to dual Foreground Service model (Capture + Accessibility) with `ServiceConnection` IPC |
| Workflow/task config serialization | JSON/config parsing | Promote to strict typed `WorkflowSchema` with kotlinx.serialization + Room persistence |
| Task scheduling / queuing | Any scheduler/executor | Replace with priority-aware `PriorityChannel` fed by coroutine `produce {}` builders |
| Error tracking / recovery | Try-catch blocks, error states | Formalize into `ExecutionResult<T>` sealed class + supervisor coroutine scope |
| Persistent state across restarts | Any SharedPrefs or DB use | Migrate to Proto DataStore + Room checkpointing |
| Service lifecycle patterns | onCreate/onDestroy patterns | Enforce with `LifecycleService` base class and `DefaultLifecycleObserver` observers |
| Coroutine scope management | Any `launch {}` calls | Enforce `SupervisorJob()` + custom `CoroutineExceptionHandler` per module |

### 3.3 — aria-ai-cpu-only: Extraction Targets

| Pattern | Location Hint | TITAN Upgrade |
|---|---|---|
| TFLite / ONNX model loading | Interpreter initialization | Add model warm-up pass, memory-mapped model files, thread-count tuning |
| Inference input preprocessing | Bitmap → tensor conversion | Zero-copy path: `HardwareBuffer` → `TensorImage` without intermediate Bitmap |
| Inference output postprocessing | Raw output → action mapping | Typed output DTOs with confidence thresholds + softmax normalization |
| CPU throttle prevention | Thread priority or scheduling hints | Set inference thread to `THREAD_PRIORITY_BACKGROUND` with `Process.setThreadPriority()` |
| Memory allocation in inference loop | Buffer reuse | Preallocate `TensorBuffer` arrays; reuse across frames |
| Async inference scheduling | Callback or coroutine wrapping | Wrap in `withContext(Dispatchers.Default) {}` with structured cancellation |
| Quantized model handling | INT8 / FP16 model paths | Implement `ModelPrecisionManager` that downgrades INT8→binary under thermal pressure |

**Critical Directive:** Do not copy-paste code from these repositories. Extract the architectural *ideas*, identify their weaknesses, and redesign them as production-grade Kotlin components integrated into TITAN's unified architecture. Document each extracted concept with a one-line attribution comment in the relevant TITAN source file (e.g., `// Pattern adapted from subway-surfers-bot FrameLoop`).

---

## 4. COMPLETE PROJECT STRUCTURE

Generate the following directory tree verbatim. Every listed file must be implemented:

```
titan-automation/
├── app/
│   ├── src/main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/titan/automation/
│   │   │   ├── TitanApplication.kt              # Hilt app + global init
│   │   │   ├── MainActivity.kt                  # Compose entry, permission orchestration
│   │   │   │
│   │   │   ├── core/
│   │   │   │   ├── Result.kt                    # ExecutionResult<T> sealed class
│   │   │   │   ├── Logger.kt                    # Structured logger (ring buffer + file sink)
│   │   │   │   ├── Extensions.kt                # Bitmap, Flow, Coroutine extension fns
│   │   │   │   ├── Dispatchers.kt               # Named dispatcher providers (DI-injectable)
│   │   │   │   └── Constants.kt                 # App-wide constants (FPS caps, thresholds)
│   │   │   │
│   │   │   ├── domain/
│   │   │   │   ├── model/
│   │   │   │   │   ├── MacroWorkflow.kt         # Domain model for a full workflow
│   │   │   │   │   ├── MacroStep.kt             # Sealed class: Click, Swipe, OCR, Detect, Wait, Branch
│   │   │   │   │   ├── DetectionResult.kt       # CV/OCR/ML detection output DTO
│   │   │   │   │   ├── GestureSpec.kt           # Typed gesture descriptor
│   │   │   │   │   ├── RLState.kt               # RL state vector model
│   │   │   │   │   └── ThermalProfile.kt        # Thermal/battery constraint model
│   │   │   │   ├── repository/
│   │   │   │   │   ├── WorkflowRepository.kt    # Interface
│   │   │   │   │   ├── TemplateRepository.kt    # Interface
│   │   │   │   │   └── TelemetryRepository.kt   # Interface
│   │   │   │   └── usecase/
│   │   │   │       ├── ExecuteWorkflowUseCase.kt
│   │   │   │       ├── ParseWorkflowUseCase.kt
│   │   │   │       ├── DetectTemplateUseCase.kt
│   │   │   │       └── EvaluateRLActionUseCase.kt
│   │   │   │
│   │   │   ├── data/
│   │   │   │   ├── db/
│   │   │   │   │   ├── TitanDatabase.kt         # Room DB with encryption
│   │   │   │   │   ├── WorkflowDao.kt
│   │   │   │   │   ├── TelemetryDao.kt
│   │   │   │   │   └── entity/
│   │   │   │   │       ├── WorkflowEntity.kt
│   │   │   │   │       └── TelemetryEventEntity.kt
│   │   │   │   ├── datastore/
│   │   │   │   │   ├── AppSettingsSerializer.kt # Proto DataStore serializer
│   │   │   │   │   └── AppSettings.proto        # Proto schema
│   │   │   │   ├── repository/
│   │   │   │   │   ├── WorkflowRepositoryImpl.kt
│   │   │   │   │   ├── TemplateRepositoryImpl.kt
│   │   │   │   │   └── TelemetryRepositoryImpl.kt
│   │   │   │   └── workflow/
│   │   │   │       ├── WorkflowParser.kt        # JSON → domain model parser
│   │   │   │       └── WorkflowSerializer.kt    # Domain model → JSON
│   │   │   │
│   │   │   ├── engine/
│   │   │   │   ├── MacroEngine.kt               # Core async workflow executor
│   │   │   │   ├── ExecutionGraph.kt            # DAG-based step dependency resolver
│   │   │   │   ├── RetryPolicy.kt               # Configurable retry + backoff
│   │   │   │   ├── CooldownScheduler.kt         # Per-action cooldown tracking
│   │   │   │   └── WorkflowCheckpoint.kt        # Crash-safe progress persistence
│   │   │   │
│   │   │   ├── accessibility/
│   │   │   │   ├── MacroAccessibilityService.kt # Gesture injection service
│   │   │   │   ├── GestureDispatcher.kt         # Priority queue + dispatch logic
│   │   │   │   ├── CoordinateNormalizer.kt      # DPI/orientation transform
│   │   │   │   └── AntiDetectionJitter.kt       # Gaussian offset + timing variance
│   │   │   │
│   │   │   ├── capture/
│   │   │   │   ├── ScreenCaptureService.kt      # MediaProjection foreground service
│   │   │   │   ├── FramePipeline.kt             # Channel-based frame routing
│   │   │   │   ├── FrameDifferenceFilter.kt     # Skip redundant static frames
│   │   │   │   ├── BitmapPool.kt                # Thread-safe bitmap pool
│   │   │   │   └── RoiCropper.kt               # Region-of-interest extractor
│   │   │   │
│   │   │   ├── vision/
│   │   │   │   ├── VisionEngine.kt              # Unified CV entry point
│   │   │   │   ├── TemplateMatcher.kt           # OpenCV template matching
│   │   │   │   ├── FeatureDetector.kt           # ORB/SIFT feature matching
│   │   │   │   ├── OcrProcessor.kt              # ML Kit OCR wrapper
│   │   │   │   ├── InferenceEngine.kt           # TFLite/ONNX inference manager
│   │   │   │   ├── ModelPrecisionManager.kt     # Thermal-aware model downgrade
│   │   │   │   └── DetectionStrategy.kt         # Strategy interface + registry
│   │   │   │
│   │   │   ├── rl/
│   │   │   │   ├── RLEngine.kt                  # RL subsystem entry point
│   │   │   │   ├── QTable.kt                    # Q-table with persistence
│   │   │   │   ├── DQNLite.kt                   # Lightweight neural Q-network
│   │   │   │   ├── ExperienceReplay.kt          # Circular replay buffer
│   │   │   │   ├── RewardEvaluator.kt           # Reward signal computation
│   │   │   │   └── StateEncoder.kt              # Screen state → compressed vector
│   │   │   │
│   │   │   ├── overlay/
│   │   │   │   ├── OverlayService.kt            # WindowManager overlay service
│   │   │   │   ├── OverlayViewModel.kt          # StateFlow-driven overlay state
│   │   │   │   └── ui/
│   │   │   │       ├── OverlayPanel.kt          # Compose overlay root
│   │   │   │       ├── FpsMeter.kt              # Realtime FPS display
│   │   │   │       ├── DetectionLogFeed.kt      # Live scrolling log
│   │   │   │       └── PanicButton.kt           # Emergency kill switch
│   │   │   │
│   │   │   ├── performance/
│   │   │   │   ├── ThermalGovernor.kt           # PowerManager thermal monitoring
│   │   │   │   ├── BatteryGuard.kt              # BatteryManager + degradation logic
│   │   │   │   └── MemoryPressureMonitor.kt     # ComponentCallbacks2 handler
│   │   │   │
│   │   │   ├── watchdog/
│   │   │   │   ├── WatchdogService.kt           # Coroutine watchdog + auto-recovery
│   │   │   │   └── DeadlockDetector.kt          # Stuck-thread heuristics
│   │   │   │
│   │   │   ├── plugins/
│   │   │   │   ├── PluginManager.kt             # Runtime plugin loader
│   │   │   │   ├── PluginContract.kt            # Plugin interface + metadata
│   │   │   │   └── PluginSandbox.kt             # Isolated execution context
│   │   │   │
│   │   │   ├── telemetry/
│   │   │   │   ├── TelemetryManager.kt          # Event collection + export
│   │   │   │   ├── ExecutionTimeline.kt         # Timeline reconstruction
│   │   │   │   └── CrashReporter.kt            # Local crash diagnostics
│   │   │   │
│   │   │   └── presentation/
│   │   │       ├── dashboard/
│   │   │       │   ├── DashboardScreen.kt
│   │   │       │   └── DashboardViewModel.kt
│   │   │       ├── editor/
│   │   │       │   ├── WorkflowEditorScreen.kt
│   │   │       │   └── WorkflowEditorViewModel.kt
│   │   │       ├── debug/
│   │   │       │   ├── DebugScreen.kt           # Frame preview, OCR overlay, coord inspector
│   │   │       │   └── DebugViewModel.kt
│   │   │       └── settings/
│   │   │           ├── SettingsScreen.kt
│   │   │           └── SettingsViewModel.kt
│   │   │
│   │   └── assets/
│   │       └── models/
│   │           ├── ui_classifier_int8.tflite
│   │           └── scene_detector_fp16.tflite
│   │
│   └── build.gradle.kts
│
├── build.gradle.kts                             # Root build file
├── settings.gradle.kts
├── gradle/libs.versions.toml                    # Version catalog
└── proguard-rules.pro
```

---

## 5. GRADLE CONFIGURATION (build.gradle.kts)

Generate both the root `build.gradle.kts` and the `app/build.gradle.kts`.

### app/build.gradle.kts must include:

```kotlin
// Exact version pins required — do not use "latest.release"
// OpenCV: integrate as a local AAR module from https://sourceforge.net/projects/opencvlibrary/
// (opencv-4.8.0-android-sdk.zip → OpenCV-android-sdk/sdk/java → import as module)

android {
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        targetSdk = 35
    }
    buildFeatures { compose = true }
    
    // ABI split: ship arm64-v8a + armeabi-v7a only (exclude x86 for size)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }
    
    // ProGuard must preserve OpenCV JNI bridges and TFLite ops
}

dependencies {
    // Hilt
    // Room + encryption (SQLCipher)
    // Proto DataStore
    // Coroutines
    // Compose BOM
    // ML Kit Text Recognition v2
    // TensorFlow Lite + GPU delegate (optional, CPU fallback mandatory)
    // OpenCV (local module)
    // kotlinx.serialization
    // Timber for logging
    // LeakCanary (debugImplementation only)
}
```

Provide ALL version numbers explicitly. Do not use `+` wildcards.

---

## 6. ANDROIDMANIFEST.XML — COMPLETE SPECIFICATION

Generate a complete `AndroidManifest.xml` that includes:

### Permissions:
```xml
<!-- Standard -->
BIND_ACCESSIBILITY_SERVICE
FOREGROUND_SERVICE
FOREGROUND_SERVICE_MEDIA_PROJECTION
FOREGROUND_SERVICE_SPECIAL_USE
POST_NOTIFICATIONS (API 33+)
SYSTEM_ALERT_WINDOW
RECEIVE_BOOT_COMPLETED

<!-- Runtime-requested -->
RECORD_AUDIO (optional, for future voice trigger support)
```

### Service Declarations:
```xml
<!-- Accessibility Service -->
<service android:name=".accessibility.MacroAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService"/>
    </intent-filter>
    <meta-data android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config"/>
</service>

<!-- Screen Capture — requires foregroundServiceType=mediaProjection -->
<service android:name=".capture.ScreenCaptureService"
    android:foregroundServiceType="mediaProjection"
    android:exported="false"/>

<!-- Overlay — foregroundServiceType=specialUse -->
<service android:name=".overlay.OverlayService"
    android:foregroundServiceType="specialUse"
    android:exported="false"/>

<!-- Watchdog -->
<service android:name=".watchdog.WatchdogService"
    android:exported="false"/>
```

### accessibility_service_config.xml:
```xml
<accessibility-service
    android:accessibilityEventTypes="typeAllMask"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagDefault|flagReportViewIds|flagRequestEnhancedWebAccessibility"
    android:canPerformGestures="true"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="100"
    android:packageNames=""
    android:settingsActivity=".MainActivity"/>
```

---

## 7. CORE SOURCE FILES — IMPLEMENTATION SPECIFICATIONS

Each file below must be generated as complete, compiling Kotlin source. No stubs.

---

### 7.1 `MacroAccessibilityService.kt`

**Architectural role:** The only gateway for touch injection into the Android input system.

**Implementation requirements:**

```kotlin
class MacroAccessibilityService : AccessibilityService() {

    // Gesture dispatch with priority queue
    // Priority: CRITICAL (panic) > HIGH (macro step) > NORMAL (scheduled) > LOW (background)
    private val gestureQueue = PriorityBlockingQueue<PrioritizedGesture>()
    
    // Coroutine scope tied to service lifecycle
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // Coordinate normalizer (injected)
    @Inject lateinit var coordinateNormalizer: CoordinateNormalizer
    
    // Anti-detection jitter (injected)
    @Inject lateinit var jitter: AntiDetectionJitter
```

**Required method signatures and behaviors:**

```
dispatchClick(x: Float, y: Float, priority: GesturePriority = NORMAL): Boolean
  - Apply Gaussian jitter from AntiDetectionJitter (σ = 2–4px, clamped to screen bounds)
  - Apply timing variance (±15–45ms randomized delay before dispatch)
  - Build GestureDescription with single PATH stroke (not tap, for compatibility)
  - dispatchGesture() with ResultCallback
  - Return true only when callback confirms COMPLETED (not just dispatched)
  - On failure: enqueue to retry queue with exponential backoff (max 3 retries)

dispatchSwipe(startX, startY, endX, endY, durationMs, priority): Boolean
  - Generate bezier curve path (not straight line) through intermediate control points
  - Divide into N intermediate waypoints (N = durationMs / 16, capped at 60)
  - Each waypoint added as Path.lineTo() on GestureDescription stroke
  - Apply natural acceleration curve (ease-in-out via cubic bezier timing)
  - Timing variance: ±10% of durationMs

dispatchLongPress(x, y, durationMs = 600, priority): Boolean
  - Use single stroke with duration = durationMs
  - Apply same jitter as dispatchClick
  - Ensure minimum 500ms hold duration (Android system requirement)

dispatchMultiTouch(pointers: List<TouchPointer>, durationMs): Boolean
  - Build GestureDescription with multiple simultaneous strokes
  - Each TouchPointer: {startX, startY, endX, endY, startTimeMs, endTimeMs}
  - Synchronize stroke start times to within 16ms of each other
  - Required for pinch-to-zoom: two pointers with diverging paths

dispatchGestureBatch(gestures: List<GestureSpec>): Flow<GestureResult>
  - Execute multiple gestures as an atomic batch
  - Emit GestureResult for each completed gesture
  - Cancel entire batch on first FAILED result (configurable)
```

**Service lifecycle:**
```
onServiceConnected() → initialize scope, start gesture dispatch loop, bind to MacroEngine
onUnbind() → cancel scope, flush gesture queue, notify MacroEngine of disconnection
onAccessibilityEvent() → forward relevant events to MacroEngine state machine
onInterrupt() → pause gesture dispatch, DO NOT crash
```

**CoordinateNormalizer** (in `accessibility/CoordinateNormalizer.kt`):
```
Must handle:
- Portrait ↔ Landscape rotation (swap X/Y, recalculate bounds)
- Density-independent coordinates → pixel coordinates (multiply by displayMetrics.density)
- Safe area insets (navigation bar, status bar, notch)
- Multi-window / split-screen mode detection and bounds adjustment
```

**AntiDetectionJitter** (in `accessibility/AntiDetectionJitter.kt`):
```
Gaussian distribution offset:  nextGaussian() * sigma, clamped to ±maxOffset
Timing variance:  Random.nextLong(minDelayMs, maxDelayMs)
Both sigma and delay range must be configurable per-workflow via WorkflowSchema
```

---

### 7.2 `ScreenCaptureService.kt`

**Architectural role:** Zero-overhead frame acquisition feeding the vision pipeline.

**Implementation requirements:**

```kotlin
@AndroidEntryPoint
class ScreenCaptureService : LifecycleService() {

    private lateinit var mediaProjection: MediaProjection
    private lateinit var virtualDisplay: VirtualDisplay
    private lateinit var imageReader: ImageReader
    
    // Emits frames to all subscribers
    private val _frameFlow = MutableSharedFlow<CapturedFrame>(
        replay = 0,
        extraBufferCapacity = 2,  // Never block producer
        onBufferOverflow = BufferOverflow.DROP_OLDEST  // Skip stale frames
    )
    val frameFlow: SharedFlow<CapturedFrame> = _frameFlow
    
    // Adaptive FPS controller
    private val fpsController = AdaptiveFpsController(targetFps = 8)
    
    // Bitmap pool: preallocate N bitmaps at init, reuse forever
    @Inject lateinit var bitmapPool: BitmapPool
    
    // Frame differencing filter
    @Inject lateinit var frameDiffFilter: FrameDifferenceFilter
```

**Frame acquisition loop — critical implementation details:**

```
ImageReader setup:
  - Format: PixelFormat.RGBA_8888
  - maxImages: 3 (triple buffering — never less)
  - Width/Height: capture at HALF device resolution (e.g., 720p on 1440p screen)
    → Full resolution only if template requires it (configurable per-step)
  
Acquisition loop (runs on Dispatchers.IO, never Main):
  - Block on ImageReader.acquireLatestImage() — DO NOT use acquireNextImage()
    (acquireLatestImage discards stale frames automatically)
  - If image == null: continue (no frame available yet)
  - Copy pixel data to pooled Bitmap via Image.Plane copyPixelsFromBuffer()
    → Use direct ByteBuffer, never intermediate byte array
  - IMMEDIATELY close() the Image object (within same try block)
  - Apply FrameDifferenceFilter: if similarity > 0.97, skip processing, return bitmap to pool
  - Wrap in CapturedFrame(bitmap, timestamp, displayRotation)
  - Emit to frameFlow
  - Rate-limit: calculate elapsed since last emission, sleep remainder to hit target FPS
  
HardwareBuffer path (API 31+):
  - If device supports USAGE_GPU_SAMPLED_IMAGE, acquire as HardwareBuffer
  - Wrap in Bitmap.wrapHardwareBuffer() for zero-copy path to GPU-accelerated OpenCV ops
  - Fallback to software path on older devices / when OpenCV requires software Bitmap
  
Memory safety:
  - Track all outstanding Images in a WeakReference set
  - Override onTrimMemory() → reduce maxImages to 1, flush pool
  - Never let bitmapPool hold more than 8 entries (memory cap)
```

**VirtualDisplay teardown safety:**
```
onDestroy():
  1. Stop ImageReader callbacks first
  2. Cancel coroutine scope
  3. Release VirtualDisplay
  4. Release MediaProjection
  5. Flush BitmapPool
  6. Clear _frameFlow (emit sentinel CapturedFrame.EOF)
  → Order is CRITICAL. Reversing steps 3/4 causes native crash on some OEMs.
```

**Orientation change handling:**
```
Register DisplayManager.DisplayListener
On orientation change:
  1. Pause frame emission (mutex)
  2. Release VirtualDisplay
  3. Re-query display metrics (new width/height/rotation)
  4. Recreate VirtualDisplay with new dimensions
  5. Notify CoordinateNormalizer of new transform matrix
  6. Resume emission
```

---

### 7.3 `VisionEngine.kt`

**Architectural role:** Unified detection façade routing requests to CV/OCR/ML backends.

**Implementation requirements:**

```kotlin
@Singleton
class VisionEngine @Inject constructor(
    private val templateMatcher: TemplateMatcher,
    private val ocrProcessor: OcrProcessor,
    private val inferenceEngine: InferenceEngine,
    private val dispatchers: TitanDispatchers
) {
    // All detection methods return ExecutionResult<DetectionResult>
    // Never throw — all errors wrapped in ExecutionResult.Failure
}
```

**TemplateMatcher.kt — OpenCV implementation:**

```
findTemplate(frame: Bitmap, template: Bitmap, threshold: Float = 0.8f): DetectionResult
  - Convert both Bitmaps to Mat (via Utils.bitmapToMat())
  - Convert to GRAY (Imgproc.cvtColor) unless color matching explicitly required
  - Call Imgproc.matchTemplate() with method = TM_CCOEFF_NORMED
  - Call Core.minMaxLoc() → get maxVal and maxLoc
  - If maxVal >= threshold: return DetectionResult.Found(
      confidence = maxVal,
      bounds = Rect(maxLoc.x, maxLoc.y, template.width, template.height),
      centerX = maxLoc.x + template.width / 2,
      centerY = maxLoc.y + template.height / 2
    )
  - Else: return DetectionResult.NotFound(confidence = maxVal)
  - ALWAYS recycle Mat objects in finally block (mat.release())
  - DO NOT hold Mat references beyond method scope

findAllTemplates(frame: Bitmap, template: Bitmap, threshold: Float, maxResults: Int): List<DetectionResult>
  - Same as above but collect ALL locations above threshold (not just maximum)
  - Apply Non-Maximum Suppression to remove duplicate detections within 20px radius
  - Return up to maxResults sorted by confidence descending

Multi-scale matching (findTemplateMultiScale):
  - Try scales: [0.7, 0.85, 1.0, 1.15, 1.3]
  - For each scale: resize template, call findTemplate
  - Return best match across all scales with scale factor in result

ORB Feature Matching (in FeatureDetector.kt):
  - Use Features2d.ORB.create(nfeatures = 500)
  - Detect + compute keypoints and descriptors for both frame and template
  - Match with BFMatcher (NORM_HAMMING, crossCheck = true)
  - Filter matches with ratio test (Lowe's ratio = 0.75)
  - Min matches threshold: 10 (configurable)
  - If sufficient matches: compute homography with RANSAC
  - Return bounding box from transformed template corners
```

**OcrProcessor.kt — ML Kit implementation:**

```
recognizeText(frame: Bitmap, region: Rect? = null): OcrResult
  - Crop frame to region if provided (avoid full-frame OCR when possible)
  - Create InputImage.fromBitmap()
  - Call TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process()
  - Collect results as Flow (use suspendCancellableCoroutine + addOnCompleteListener)
  - Map TextBlock → OcrBlock(text, confidence, boundingBox)
  - Return OcrResult(blocks = list, fullText = concatenated)

findText(frame: Bitmap, query: String, fuzzyThreshold: Float = 0.85f, region: Rect? = null): DetectionResult
  - Run recognizeText()
  - For each OcrBlock: compute Levenshtein similarity to query
  - If similarity >= fuzzyThreshold: return DetectionResult.Found with block bounds

findTextRegex(frame: Bitmap, pattern: Regex, region: Rect? = null): List<DetectionResult>
  - Run recognizeText()
  - Apply Regex to fullText
  - Map each match to its OcrBlock bounding box
  - Return all matches

Confidence threshold: default 0.7 (configurable per OCR step in workflow JSON)
Language: detect automatically via TextRecognizerOptions; do not hardcode Latin only
```

**InferenceEngine.kt — TFLite implementation:**

```
Model management:
  - Load model from assets using MappedByteBuffer (memory-mapped, avoids heap allocation)
  - Interpreter options: numThreads = min(4, Runtime.availableProcessors() - 1)
  - Add GPU delegate on API 28+ (fall back silently to CPU)
  - Warm-up: run single inference with zero input tensor on init

classify(frame: Bitmap, modelType: ModelType): InferenceResult
  - Resize Bitmap to model input dimensions (e.g., 224×224 for MobileNet-class)
  - Convert to ByteBuffer: normalize pixel values to [-1, 1] range (float) or [0, 255] (INT8)
  - Run interpreter.run(inputBuffer, outputBuffer)
  - Map output index → label via labels file loaded from assets
  - Return InferenceResult(label, confidence, latencyMs)

Thermal-aware scaling (ModelPrecisionManager):
  - ThermalStatus.THERMAL_STATUS_MODERATE → switch to INT8 model if FP16 active
  - ThermalStatus.THERMAL_STATUS_SEVERE → disable inference entirely, emit Warning
  - ThermalStatus.THERMAL_STATUS_CRITICAL → emergency stop macro execution
  - Use PowerManager.OnThermalStatusChangedListener (API 29+)
```

---

### 7.4 `RLEngine.kt`

**Architectural role:** Adaptive decision-making layer that learns timing and retry patterns.

**Design philosophy:** Must be toggleable at runtime. When disabled, MacroEngine falls back to deterministic execution. Adds zero overhead when disabled.

**Implementation:**

```kotlin
@Singleton
class RLEngine @Inject constructor(
    private val qTable: QTable,
    private val experienceReplay: ExperienceReplay,
    private val rewardEvaluator: RewardEvaluator,
    private val stateEncoder: StateEncoder,
    private val thermalGovernor: ThermalGovernor
) {
    private var isEnabled: Boolean = true
    private var epsilon: Float = 0.3f  // Exploration rate (decays over time)
    private val epsilonDecay = 0.995f
    private val epsilonMin = 0.05f
    private val learningRate = 0.1f
    private val discountFactor = 0.9f
```

**State representation (StateEncoder.kt):**
```
Input: DetectionResult list + current MacroState + execution history (last N steps)
Output: compressed Float array of fixed length (e.g., 64 dimensions)

Encoding:
  - Slot 0–15: binary feature flags (template_found, ocr_match, ui_state_changed, etc.)
  - Slot 16–31: normalized confidence values from last detections
  - Slot 32–47: timing features (elapsed since last success, retry count, cooldown remaining)
  - Slot 48–63: workflow position features (step index normalized, branch depth)
  
Compression: L2-normalize the output vector before Q-table lookup
```

**Action space:**
```kotlin
sealed class RLAction {
    data class ExecuteStep(val stepId: String) : RLAction()
    data class AdjustDelay(val deltaMs: Long) : RLAction()   // ±50ms
    data class RetryStep(val stepId: String) : RLAction()
    data class SkipStep(val stepId: String) : RLAction()
    data class SwitchBranch(val branchId: String) : RLAction()
}
```

**QTable.kt:**
```
Implementation: HashMap<StateHash, FloatArray> where FloatArray.size = RLAction.count
StateHash: SHA-256 of state vector, truncated to 16 bytes (balance precision vs memory)
Persistence: serialize to Room DB every 100 updates (not every step)
Max entries: 10,000 (evict LRU when full)
```

**ExperienceReplay.kt:**
```
Circular buffer of Experience(state, action, reward, nextState, done)
Capacity: 1,000 experiences
Mini-batch size: 32 for DQN training update
Training trigger: every 50 new experiences
Training runs on Dispatchers.Default with CPU priority BACKGROUND
NEVER train during active gesture dispatch
```

**RewardEvaluator.kt — reward signals:**
```
+1.0:  DetectionResult.Found after < 2 retries
+0.5:  DetectionResult.Found after 2–4 retries  
+2.0:  Full workflow loop completed successfully
-0.5:  DetectionResult.NotFound (timeout)
-1.0:  Gesture dispatch failed
-2.0:  Workflow step exceeded max retries → escalated failure
+0.3:  Execution latency improved vs rolling average (faster = better)
-0.3:  Execution latency degraded
```

**Battery/thermal compliance:**
```
Battery < 20%: disable RL training (inference still active)
Battery < 10%: disable RL entirely (isEnabled = false)
ThermalStatus >= MODERATE: disable training
ThermalStatus >= SEVERE: disable RL entirely
```

---

### 7.5 `MacroEngine.kt`

**Architectural role:** The brain — orchestrates all subsystems into coherent workflow execution.

```kotlin
@Singleton
class MacroEngine @Inject constructor(
    private val visionEngine: VisionEngine,
    private val rlEngine: RLEngine,
    private val gestureDispatcher: GestureDispatcher,
    private val workflowCheckpoint: WorkflowCheckpoint,
    private val telemetry: TelemetryManager,
    private val thermalGovernor: ThermalGovernor,
    private val dispatchers: TitanDispatchers
) {
    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState
    
    private val engineScope = CoroutineScope(
        SupervisorJob() + 
        dispatchers.default + 
        CoroutineExceptionHandler { _, throwable -> handleUnhandledException(throwable) }
    )
```

**Execution loop — step-by-step logic:**

```
executeWorkflow(workflow: MacroWorkflow):
  1. Validate workflow schema (non-null steps, valid conditions)
  2. Restore checkpoint if exists (resume from last saved step)
  3. _executionState.emit(ExecutionState.Running)
  4. For each step in workflow.steps (using ExecutionGraph for dependency order):
     a. Check thermalGovernor.canContinue() → if not: pause + wait for cool-down
     b. Emit telemetry event: StepStarted(stepId, timestamp)
     c. Execute step via executeStep(step, frame)
     d. Apply RetryPolicy on ExecutionResult.Failure
     e. Feed result to RLEngine.observe(state, action, reward)
     f. Save checkpoint after each successful step
     g. Emit telemetry event: StepCompleted(stepId, result, latencyMs)
  5. On completion: _executionState.emit(ExecutionState.Completed)
  6. Clear checkpoint

executeStep(step: MacroStep, frame: CapturedFrame): ExecutionResult<StepOutput>:
  - MacroStep.Click → coordinateNormalizer.transform(step.x, step.y) → gestureDispatcher.dispatchClick()
  - MacroStep.Swipe → gestureDispatcher.dispatchSwipe()
  - MacroStep.DetectTemplate → visionEngine.findTemplate(frame, step.templatePath, step.threshold)
  - MacroStep.OcrCheck → visionEngine.findText(frame, step.query, step.fuzzy)
  - MacroStep.Wait → delay(step.durationMs) — respects cancellation
  - MacroStep.Branch → evaluate condition → select sub-workflow → recurse
  - MacroStep.Loop → execute child steps N times (or until condition met)
  - MacroStep.Parallel → launch all child steps in parallel coroutines → awaitAll()
  - MacroStep.InvokePlugin → pluginManager.invoke(step.pluginId, step.params)
```

**Cancellation and panic:**
```
panicStop():
  1. Cancel engineScope children (NOT the scope itself — keep supervisor alive)
  2. gestureDispatcher.clearQueue()  
  3. workflowCheckpoint.clear()
  4. _executionState.emit(ExecutionState.Stopped)
  5. Emit telemetry: MacroPanicked

pause() / resume(): toggle a Mutex that executeStep awaits before each step
```

**JSON workflow schema (WorkflowParser.kt):**

```json
{
  "version": "1.2",
  "id": "battle-loop-001",
  "name": "Auto Battle Loop",
  "description": "Automated game battle sequence with OCR victory detection",
  "rl_enabled": true,
  "rl_params": {
    "epsilon": 0.2,
    "learning_rate": 0.1,
    "discount_factor": 0.9
  },
  "retry_policy": {
    "max_retries": 3,
    "backoff_ms": 500,
    "backoff_multiplier": 2.0
  },
  "steps": [
    {
      "id": "find-play-btn",
      "type": "detect_template",
      "template": "templates/play_button.png",
      "threshold": 0.82,
      "roi": { "x": 0, "y": 0.7, "width": 1.0, "height": 0.3 },
      "on_found": { "next": "click-play" },
      "on_not_found": { "retry": true, "timeout_ms": 5000 }
    },
    {
      "id": "click-play",
      "type": "click",
      "x_ratio": 0.5,
      "y_ratio": 0.85,
      "jitter_sigma": 3.0,
      "delay_before_ms": 120,
      "delay_after_ms": 300,
      "next": "wait-for-battle"
    },
    {
      "id": "wait-for-battle",
      "type": "wait",
      "duration_ms": 2000,
      "next": "check-victory"
    },
    {
      "id": "check-victory",
      "type": "ocr_check",
      "query": "Victory",
      "fuzzy_threshold": 0.85,
      "region": { "x": 0.2, "y": 0.1, "width": 0.6, "height": 0.2 },
      "on_found": { "next": "collect-reward" },
      "on_not_found": { "next": "retry-battle" }
    },
    {
      "id": "collect-reward",
      "type": "branch",
      "conditions": [
        {
          "detect_template": "templates/chest_icon.png",
          "threshold": 0.75,
          "action": { "type": "click", "x_ratio": 0.5, "y_ratio": 0.6 }
        }
      ],
      "fallback_next": "find-play-btn"
    },
    {
      "id": "retry-battle",
      "type": "detect_template",
      "template": "templates/retry_button.png",
      "threshold": 0.8,
      "on_found": {
        "type": "click",
        "target": "detected_center",
        "next": "wait-for-battle"
      },
      "on_not_found": { "next": "find-play-btn" }
    }
  ]
}
```

---

### 7.6 `OverlayService.kt`

**Architectural role:** System-level floating control surface that never interferes with automated touches.

```kotlin
@AndroidEntryPoint
class OverlayService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: ComposeView
    private val overlayViewModel: OverlayViewModel by lazy {
        ViewModelProvider.create(this)[OverlayViewModel::class.java]
    }
```

**WindowManager params:**
```kotlin
WindowManager.LayoutParams(
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.WRAP_CONTENT,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    // FLAG_NOT_FOCUSABLE: overlay never steals key events from game
    // FLAG_NOT_TOUCH_MODAL: touches outside overlay pass through to game
    // FLAG_LAYOUT_IN_SCREEN: respect screen edges
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = 16  // Initial position (persisted to DataStore)
    y = 200
}
```

**Compose overlay UI (OverlayPanel.kt):**
```
Minimized mode: 48×48dp translucent button (TITAN logo), tap to expand
Expanded mode: 
  - Draggable header bar (touch listener on WindowManager params)
  - Real-time FPS counter (updates every 500ms from frameFlow)
  - Current step indicator ("Detecting: play_button.png")
  - Last detection result with confidence
  - Scrollable detection log (last 20 entries, SharedFlow-backed)
  - Start/Pause toggle button
  - Profile selector dropdown
  - PANIC STOP button (red, full width, sends immediate panicStop())

Panic button implementation:
  - Background: Color.Red with 0.9f alpha
  - onClick: macroEngine.panicStop() + gestureDispatcher.clearAll()
  - Haptic feedback: HapticFeedbackConstants.LONG_PRESS
  - Confirmation: NOT required (speed is critical in panic scenario)
  - Must respond in < 100ms
```

**Touch pass-through (critical for gaming):**
```
The overlay ComposeView must set:
  view.setOnTouchListener { _, event ->
      // Only consume touches that hit interactive Compose elements
      // Pass all other touches through to the game below
      false  // Return false = pass through by default
  }
  
Drag implementation: modify WindowManager.LayoutParams.x/y directly
(Do NOT use translationX/Y — those don't move the window, only the view within it)
Persist last position to Proto DataStore on drag end
```

**Memory safety:**
```
onDestroy():
  1. overlayView.disposeComposition()
  2. windowManager.removeView(overlayView)
  3. Set overlayView reference to null
  → windowManager.removeViewImmediate() ONLY if called from non-main thread
  → windowManager.removeView() if called from main thread (both cases must be handled)
```

---

## 8. REMAINING REQUIRED FILES

All of the following must also be fully implemented (not stubbed):

### `ThermalGovernor.kt`
```
Responsibilities:
- Register PowerManager.OnThermalStatusChangedListener
- Map thermal status to ThermalProfile enum: NORMAL, WARM, HOT, CRITICAL
- On WARM: reduce target FPS by 30%, emit FpsScaled event
- On HOT: disable RL training, reduce FPS by 50%, downgrade inference to INT8
- On CRITICAL: emit EmergencyStop, call macroEngine.panicStop()
- canContinue(): Boolean — queried before each MacroEngine step
- currentProfile(): StateFlow<ThermalProfile> — observed by OverlayViewModel
```

### `BatteryGuard.kt`
```
Responsibilities:
- Register BroadcastReceiver for ACTION_BATTERY_CHANGED
- Track level, isCharging, temperature
- Degrade table:
  < 30%: disable RL training
  < 20%: reduce FPS to minimum (5)
  < 15%: disable inference, OCR only
  < 10%: pause macro, notify user
  charging=true: restore all capabilities regardless of level
- Emit BatteryEvent to TelemetryManager
```

### `WatchdogService.kt`
```
Architecture: Separate Foreground Service that monitors MacroEngine health

Heartbeat mechanism:
- MacroEngine emits heartbeat token every 5 seconds during active execution
- WatchdogService expects token within 10-second window
- On timeout: attempt graceful restart via engineScope.cancel() + re-init
- On 3 consecutive failures: emit WatchdogGaveUp, notify user via notification

Deadlock detection:
- Track last step start time
- If same step running > step.timeoutMs * 2: force-cancel that step's Job
- Log DeadlockDetected event to TelemetryManager

Coroutine leak detection:
- Count active coroutines in engineScope (via CoroutineScope.coroutineContext)
- Alert if count exceeds 50 (configurable)
```

### `WorkflowParser.kt`
```
Input: JSON String or File
Output: MacroWorkflow domain model

Validation:
- Every step's "next" reference must resolve to an existing step ID
- No circular references (depth-first cycle detection)
- Template file paths must exist in app's files directory
- x_ratio and y_ratio must be in [0.0, 1.0]
- threshold must be in [0.0, 1.0]

On parse error: return ExecutionResult.Failure with detailed error message including
JSON path of the failing field (e.g., "steps[2].on_found.next references unknown step 'foo'")
```

### `PluginManager.kt`
```
Plugin contract (PluginContract.kt):
interface TitanPlugin {
    val id: String
    val version: Int
    val requiredPermissions: List<String>
    
    suspend fun execute(params: JsonObject, context: PluginContext): PluginResult
    fun onLoad(context: PluginContext)
    fun onUnload()
}

Loading mechanism:
- Plugins are .jar files in app's /plugins directory
- Load via PathClassLoader with parent = application classLoader
- Sandbox: each plugin gets isolated CoroutineScope (cancelled on plugin unload)
- Plugin cannot access other plugins' state
- Plugin cannot call gestureDispatcher directly (must request via PluginContext interface)
- Validate plugin signature before loading (self-signed cert from developer)
```

### `TelemetryManager.kt`
```
Event types: StepStarted, StepCompleted, StepFailed, DetectionResult, GestureDispatched,
             GestureFailed, MacroStarted, MacroCompleted, MacroPanicked, ThermalEvent,
             BatteryEvent, WatchdogEvent, CrashEvent

Storage: Room DB (TelemetryEventEntity) — rotate logs older than 7 days
Export: JSON file to app's cache directory, shareable via FileProvider
Real-time feed: SharedFlow<TelemetryEvent> with replay=50 (for OverlayService log display)
Performance: batch inserts every 100ms using Room's suspend DAO insert (not individual rows)
```

---

## 9. END-TO-END EXECUTION FLOW

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TITAN EXECUTION PIPELINE                            │
└─────────────────────────────────────────────────────────────────────────────┘

  [User: Start Macro]
         │
         ▼
  OverlayService.startMacro()
         │
         ▼
  MacroEngine.executeWorkflow(workflow)
         │
         ├──── ThermalGovernor.canContinue()? ──NO──► Pause + Wait for cool-down
         │                │YES
         ▼
  WorkflowCheckpoint.restoreOrStart()
         │
         ▼
  ┌──────────────────────────────────────────┐
  │           STEP EXECUTION LOOP            │
  │                                          │
  │  FramePipeline.nextFrame()               │
  │         │                                │
  │         ▼                                │
  │  FrameDifferenceFilter                   │
  │  (skip if static, >97% similar)          │
  │         │                                │
  │         ▼                                │
  │  VisionEngine.detect(frame, step)        │
  │    ├── TemplateMatcher (OpenCV)          │
  │    ├── OcrProcessor (ML Kit)             │
  │    └── InferenceEngine (TFLite)          │
  │         │                                │
  │         ▼                                │
  │  DetectionResult                         │
  │         │                                │
  │         ▼                                │
  │  RLEngine.selectAction(state, result)    │
  │  (or deterministic if RL disabled)       │
  │         │                                │
  │         ▼                                │
  │  GestureDispatcher.dispatch(action)      │
  │    └── MacroAccessibilityService         │
  │          ├── CoordinateNormalizer        │
  │          ├── AntiDetectionJitter         │
  │          └── GestureDescription dispatch │
  │         │                                │
  │         ▼                                │
  │  GestureResult (COMPLETED / FAILED)      │
  │         │                                │
  │         ▼                                │
  │  RLEngine.observe(reward)                │
  │  TelemetryManager.emit(event)            │
  │  WorkflowCheckpoint.save(stepId)         │
  │         │                                │
  │         └──► Next Step / Branch / Retry  │
  └──────────────────────────────────────────┘
         │
         ▼
  WatchdogService (parallel coroutine)
    monitors heartbeat every 5s
    auto-restarts on timeout

  BatteryGuard + ThermalGovernor (parallel)
    continuously adjusts FPS + model precision

  OverlayService (system window, always visible)
    shows live FPS, last detection, panic button
```

---

## 10. PERFORMANCE ENGINEERING REQUIREMENTS

### Zero-Allocation Hot Path Targets
```
Frame acquisition loop:
  ✗ NEVER call: Bitmap.createBitmap(), new byte[], ByteArray(size)
  ✓ ALWAYS use: BitmapPool.acquire(), preallocated ByteBuffer
  ✓ ALWAYS call: BitmapPool.release(bitmap) after detection completes

Mat lifecycle (OpenCV):
  ✓ Preallocate frame Mat and result Mat at service init
  ✓ Reuse across frames (Mat.create() is cheap if same dimensions)
  ✗ NEVER: create new Mat() per frame in hot path

TFLite inference:
  ✓ Preallocate TensorBuffer for input and output at model load
  ✓ Reuse across inferences
  ✓ Use ByteBuffer.rewind() between runs, never re-allocate
```

### Adaptive FPS Controller
```kotlin
class AdaptiveFpsController(private var targetFps: Int) {
    // Smoothed frame interval with PID-like correction
    // When behind: skip next frame processing (but still acquire + discard)
    // When ahead: sleep precisely using Looper.getMainLooper() wake mechanism
    // Adjustment triggers:
    //   ThermalGovernor.WARM → targetFps = max(5, targetFps * 0.7)
    //   ThermalGovernor.HOT  → targetFps = 5 (minimum)
    //   BatteryGuard.CRITICAL → targetFps = 3
}
```

### Memory Pressure Response (ComponentCallbacks2)
```
TRIM_MEMORY_RUNNING_LOW:  flush BitmapPool to 2 entries
TRIM_MEMORY_RUNNING_CRITICAL: flush BitmapPool entirely, pause ML inference
TRIM_MEMORY_UI_HIDDEN: normal (we're a service, ignore)
onLowMemory(): emergency pause + notify user
```

---

## 11. PROTO DATASTORE SCHEMA

```protobuf
// app_settings.proto
syntax = "proto3";

message AppSettings {
    int32 target_fps = 1;
    float default_template_threshold = 2;
    float default_ocr_confidence = 3;
    bool rl_enabled = 4;
    bool charging_only_mode = 5;
    int32 battery_pause_threshold = 6;  // Default: 10
    float jitter_sigma = 7;
    int64 min_gesture_delay_ms = 8;
    int64 max_gesture_delay_ms = 9;
    string active_profile_id = 10;
    int32 overlay_x = 11;
    int32 overlay_y = 12;
    bool overlay_minimized = 13;
    int32 max_retry_count = 14;
    int64 retry_backoff_ms = 15;
}
```

---

## 12. ROOM DATABASE SCHEMA

```kotlin
// WorkflowEntity.kt
@Entity(tableName = "workflows")
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val jsonSchema: String,     // Encrypted AES-256
    val version: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean,
    val runCount: Int,
    val lastRunAt: Long?,
    val lastRunResult: String?  // "success" | "failed" | "stopped"
)

// TelemetryEventEntity.kt
@Entity(tableName = "telemetry_events",
        indices = [Index("workflowId"), Index("timestamp")])
data class TelemetryEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val workflowId: String,
    val stepId: String?,
    val eventType: String,
    val payload: String,   // JSON
    val timestamp: Long
)

// QTableEntity.kt (for RL persistence)
@Entity(tableName = "q_table_entries")
data class QTableEntity(
    @PrimaryKey val stateHash: String,
    val qValues: FloatArray,  // Stored as BLOB via TypeConverter
    val updateCount: Int,
    val lastUpdated: Long
)
```

---

## 13. DEPENDENCY INJECTION MODULES

Provide complete Hilt module definitions for:

```kotlin
@Module @InstallIn(SingletonComponent::class)
object EngineModule {
    // Provide: MacroEngine, VisionEngine, RLEngine, GestureDispatcher
    // All as @Singleton bindings
    // Provide TitanDispatchers with named qualifiers (@IODispatcher, @DefaultDispatcher)
}

@Module @InstallIn(SingletonComponent::class)
object StorageModule {
    // Provide: TitanDatabase, WorkflowDao, TelemetryDao
    // Provide: DataStore<AppSettings>
    // Provide: EncryptionManager (AES-256 via Jetpack Security)
}

@Module @InstallIn(SingletonComponent::class)
object VisionModule {
    // Provide: TemplateMatcher, OcrProcessor, InferenceEngine
    // Initialize OpenCV via OpenCVLoader.initAsync() in @Provides method
    // Provide: BitmapPool with max size calculated from available RAM
}
```

---

## 14. PROGUARD / R8 RULES

Generate `proguard-rules.pro` that:
- Keeps all `AccessibilityService` subclasses (prevents obfuscation breaking XML binding)
- Keeps all Room `@Entity`, `@Dao`, `@Database` annotations
- Keeps all Proto DataStore serialized field names
- Keeps TFLite native bridge classes (`org.tensorflow.lite.**`)
- Keeps OpenCV JNI bridge (`org.opencv.**`)
- Keeps ML Kit classes used via reflection
- Keeps Plugin interface implementations (needed for runtime classloading)
- Aggressively shrinks everything else

---

## 15. OPENCV INTEGRATION GUIDE

Document the exact steps required:

```
1. Download OpenCV Android SDK 4.8.0 from https://sourceforge.net/projects/opencvlibrary/
2. Extract OpenCV-android-sdk.zip
3. In Android Studio: File → New → Import Module → select OpenCV-android-sdk/sdk
4. In app/build.gradle.kts: implementation(project(":opencv"))
5. In TitanApplication.onCreate():
   OpenCVLoader.initAsync("4.8.0", this, object : LoaderCallbackInterface {
       override fun onManagerConnected(status: Int) {
           if (status == LoaderCallbackInterface.SUCCESS) {
               // OpenCV ready — unblock VisionEngine initialization
           }
       }
   })
6. Alternatively for static init (no OpenCV Manager app required):
   System.loadLibrary("opencv_java4")  // In Application.onCreate()
   This bundles ~6MB native libs; use ABI splits to minimize APK size
7. ProGuard: -keep class org.opencv.** { *; }
              -keepclassmembers class org.opencv.** { *; }
```

---

## 16. FINAL OUTPUT CHECKLIST

The response is only complete when ALL of the following are present and non-stubbed:

- [ ] Complete directory tree (as specified in §4)
- [ ] `build.gradle.kts` (root + app) with exact version numbers
- [ ] `settings.gradle.kts` + `libs.versions.toml`
- [ ] `AndroidManifest.xml` (complete, no omissions)
- [ ] `accessibility_service_config.xml`
- [ ] `MacroAccessibilityService.kt` (all methods implemented)
- [ ] `ScreenCaptureService.kt` (full frame pipeline)
- [ ] `VisionEngine.kt` + `TemplateMatcher.kt` + `OcrProcessor.kt` + `InferenceEngine.kt`
- [ ] `RLEngine.kt` + `QTable.kt` + `ExperienceReplay.kt` + `RewardEvaluator.kt` + `StateEncoder.kt`
- [ ] `MacroEngine.kt` (full execution graph)
- [ ] `OverlayService.kt` + `OverlayPanel.kt` (Compose UI)
- [ ] `ThermalGovernor.kt` + `BatteryGuard.kt`
- [ ] `WatchdogService.kt`
- [ ] `WorkflowParser.kt` + `WorkflowSerializer.kt`
- [ ] `PluginManager.kt` + `PluginContract.kt`
- [ ] `TelemetryManager.kt`
- [ ] `BitmapPool.kt` + `FrameDifferenceFilter.kt`
- [ ] `CoordinateNormalizer.kt` + `AntiDetectionJitter.kt`
- [ ] `TitanDatabase.kt` + all DAO files + all Entity files
- [ ] `AppSettings.proto` + `AppSettingsSerializer.kt`
- [ ] All Hilt `@Module` definitions
- [ ] `proguard-rules.pro`
- [ ] Example macro JSON workflow (as specified in §7.5)
- [ ] End-to-end architecture diagram (as provided in §9)
- [ ] OpenCV integration steps (as specified in §15)
