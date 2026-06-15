# TITAN Automation Framework

A production-grade, rootless Android automation engine — Macrorify/Tasker-class platform with deterministic macros, computer vision (OpenCV), OCR (ML Kit v2), TFLite inference, on-device Q-learning RL, and a Jetpack Compose floating overlay UI. Runs on Android 10–15 without root.

## Run & Operate

- `bash scripts/github-push.sh "commit message"` — commit and push titan-automation/ to GitHub
- `bash scripts/github-push.sh` — auto-timestamped commit + push
- Workflow **Push to GitHub** in the Replit workflow panel does the same

Android build (requires JDK 17+, Android SDK API 35, NDK r26+):
```bash
cd titan-automation
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Stack

- Kotlin 2.0.21, AGP 8.5.2, minSdk 29 (Android 10), targetSdk 35
- Hilt 2.52 (DI), Room 2.6.1 (DB), DataStore 1.1.1 (settings + checkpoints)
- Jetpack Compose BOM 2024.10.00 (overlay UI)
- TFLite 2.14.0 (INT8 on-device inference)
- ML Kit Text Recognition v2 16.0.1 (on-device OCR)
- OpenCV 4.9.0 — **local AAR** at `app/libs/opencv.aar` (must be downloaded manually)
- NDK r26+ for JNI zero-copy AHardwareBuffer bridge
- Kotlinx Serialization 1.7.3 (WorkflowDSL JSON codec)

## Where things live

```
titan-automation/
├── app/src/main/kotlin/com/titan/automation/
│   ├── core/               ← CoroutineScopes, TitanResult sealed type, Extensions
│   ├── debug/              ← DebugSession, FrameDebugger, GestureTimeline, ExecutionTracer
│   ├── performance/        ← PerformanceMonitor (JVM/native heap), BatteryMonitor
│   ├── engine/
│   │   ├── accessibility/  ← MacroAccessibilityService (Bézier gesture dispatch)
│   │   ├── capture/        ← ScreenCaptureService, FrameProvider, NativeBridge, TemplateRepository
│   │   ├── vision/         ← VisionEngine (OpenCV), TFLiteInferenceEngine
│   │   ├── ml/             ← RLEngine (Q-learning + SPA + action masking)
│   │   ├── workflow/       ← MacroEngine, WorkflowParser, HotReloadManager
│   │   ├── overlay/        ← OverlayService (Compose floating panel)
│   │   ├── governor/       ← ThermalGovernor
│   │   └── watchdog/       ← WatchdogService + BootReceiver
│   ├── domain/             ← WorkflowDSL models, WorkflowRepository interface
│   ├── data/               ← MacroDatabase (Room), WorkflowDataStore, RepositoryImpl
│   ├── di/                 ← AppModule (Hilt wiring)
│   ├── plugins/            ← PluginManager
│   ├── security/           ← IntegrityGuard
│   ├── telemetry/          ← TelemetryManager
│   └── ui/                 ← MainActivity, PermissionViewModel
├── app/src/main/cpp/       ← titan_jni.cpp (AHardwareBuffer → ByteArray), CMakeLists.txt
├── app/src/main/assets/workflows/  ← Example JSON workflow files
└── ARCHITECTURE.md         ← Full component map, execution flow, DSL schema
```

## Architecture decisions

- **FrameProvider singleton** bridges `ScreenCaptureService` (Service, can't be Hilt-injected) and `MacroEngine`; both inject the singleton, producer publishes, consumer consumes via StateFlow.
- **RLEngine SPA** — reward adjusted as `R_base − ψ×N_steps` (ψ=0.05) to prevent endless retry loops; action masking filters Q-table to valid interactive regions only.
- **Thermal degradation ladder** — 5 levels (NORMAL→CRITICAL) controlled by `PowerManager.getThermalHeadroom(30)` on API 31+, fallback to `currentThermalStatus` on API 29-30; battery level adds a second degradation axis.
- **Zero-copy JNI bridge** — `NativeBridge.copyHardwareBufferToByteArray()` uses `AHardwareBuffer_lock` on API 31+ for GC-free frame transfer; graceful Kotlin fallback on older APIs.
- **Hot reload** — `HotReloadManager` polls `{externalFilesDir}/titan_workflows/` every 2s; on JSON change it re-parses, upserts to Room, and fires a `HotReloadEvent` that `MacroEngine` uses to swap the live workflow definition without stopping.
- **WatchdogService** runs in `:watchdog` separate process with BroadcastReceiver heartbeat IPC; `BootReceiver` restarts it after device reboot.

## Product

TITAN is a headless automation runtime for Android. Users:
1. Define workflows as JSON files (or import packs via the Plugin system)
2. Place template PNG images in `assets/templates/`
3. Enable the Accessibility Service and overlay permission
4. Tap ▶ in the floating overlay to start — the engine handles everything else

Supports: game automation, form-filling bots, UI testing, scheduled task loops.

## OpenCV Setup (required before building)

OpenCV is not bundled (AAR is ~35 MB):
1. Download [OpenCV-4.9.0-android-sdk.zip](https://github.com/opencv/opencv/releases/tag/4.9.0)
2. Copy the release AAR → `titan-automation/app/libs/opencv.aar`

## User preferences

- Kotlin only — no React Native, Flutter, or WebViews
- All on-device — no cloud inference, no Firebase dependency
- No root required — Accessibility + MediaProjection APIs only
- Production-grade code — no placeholders, stubs, or TODOs

## Gotchas

- Never run `pnpm dev` at workspace root — TITAN is a native Android project, not a Node app
- `opencv.aar` must be placed manually before Gradle sync (see OpenCV Setup above)
- Keystore env vars needed for release APK: `KEYSTORE_PATH`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
- `NativeBridge` gracefully degrades when `titan_jni.so` is missing — all features work via pure Kotlin paths
- `ScreenCaptureService.latestFrame` was removed — use `FrameProvider.latestFrame` instead

## Pointers

- GitHub: https://github.com/TITANICBHAI/titan-automation-framework
- Push script: `scripts/github-push.sh`
- Full architecture: `titan-automation/ARCHITECTURE.md`
