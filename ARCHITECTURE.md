# TITAN Automation Framework — Architecture

## Overview

TITAN is a production-grade, rootless Android automation framework. It provides:
- Deterministic macro execution via a JSON-DSL workflow engine
- Computer vision (OpenCV template matching, Canny edge detection, ORB features)
- On-device OCR (ML Kit Text Recognition v2)
- TFLite inference (INT8 quantized scene + button classifiers)
- On-device Q-learning RL with experience replay (no cloud dependency)
- Bézier-curve gesture dispatch with Gaussian noise (anti-detection)
- Thermal/battery-aware adaptive throttling
- Floating Compose overlay control panel
- Cross-process watchdog supervisor with exponential backoff restart

---

## Component Map

```
TitanApplication
    ├── ThermalGovernor        — thermal + battery degradation ladder
    ├── TitanEventBus          — SharedFlow event backbone (256-item buffer)
    ├── TelemetryManager       — ring-buffer logging, crash handler, metrics
    │
    ├── engine/
    │   ├── accessibility/
    │   │   └── MacroAccessibilityService   — Bézier gesture dispatch + screen reading
    │   ├── capture/
    │   │   └── ScreenCaptureService        — zero-copy capture (API 30: A11y; API 29: MediaProjection)
    │   ├── vision/
    │   │   ├── VisionEngine                — template match, OCR, contour tracking
    │   │   └── TFLiteInferenceEngine       — INT8 scene/button classifier
    │   ├── ml/
    │   │   └── RLEngine                    — Q-learning + replay buffer + SPA reward
    │   ├── workflow/
    │   │   ├── MacroEngine                 — coroutine state machine, checkpoint recovery
    │   │   └── WorkflowParser              — Kotlinx Serialization JSON ↔ DSL
    │   ├── overlay/
    │   │   └── OverlayService              — Compose floating panel over all apps
    │   ├── governor/
    │   │   └── ThermalGovernor             — PowerManager.getThermalHeadroom (API 31+)
    │   └── watchdog/
    │       ├── WatchdogService             — :watchdog process, heartbeat supervisor
    │       └── BootReceiver                — restart watchdog after device reboot
    │
    ├── domain/
    │   ├── model/WorkflowDSL.kt            — JSON-serializable workflow definition
    │   └── repository/WorkflowRepository   — clean architecture storage boundary
    │
    ├── data/
    │   ├── db/MacroDatabase                — Room: workflows, sessions, templates
    │   ├── store/WorkflowDataStore         — DataStore: engine preferences + checkpoints
    │   └── repository/WorkflowRepositoryImpl
    │
    ├── plugins/PluginManager               — JSON workflow pack installer
    ├── security/IntegrityGuard             — signature + debugger + root detection
    ├── di/AppModule                        — Hilt DI wiring
    └── ui/
        ├── MainActivity                    — Compose launcher (workflow list, live log, settings)
        └── PermissionViewModel             — real-time permission status
```

---

## Execution Flow

```
ScreenCaptureService (10 FPS) ──→ CapturedFrame (StateFlow)
         │
         ▼
MacroEngine.executeState()
  1. captureService.latestFrame.first()          ← wait for fresh frame
  2. visionEngine.findTemplate(frame, rule)      ← OpenCV multi-scale TM_CCOEFF_NORMED
  3. visionEngine.runOcr(frame, ocrRule)         ← ML Kit OCR, regex match
  4. rlEngine.getBestAction(state, actions)      ← ε-greedy Q-lookup
  5. accessibility.dispatchSwipe/Tap(...)        ← GestureDescription Bézier path
  6. rlEngine.learn(s, a, r, s')                 ← Q-update from replay batch
  7. repository.saveSession(checkpoint)          ← Room + DataStore
  8. branch to next state or retry
```

---

## Workflow DSL Schema

```json
{
  "workflow_id": "my_workflow",
  "version": 1,
  "initial_state": "STATE_A",
  "global_timeout_ms": 300000,
  "rl_global_enabled": false,
  "states": {
    "STATE_A": {
      "rl_enabled": false,
      "max_retries": 3,
      "cooldown_ms": 500,
      "timeout_ms": 10000,
      "on_success": "STATE_B",
      "on_failure": "END",
      "vision_match_rule": {
        "template_id": "my_template",
        "min_confidence": 0.85,
        "multi_scale": true,
        "action_intent": "MY_ACTION"
      }
    },
    "STATE_B": { ... }
  },
  "actions": {
    "MY_ACTION": {
      "interaction_type": "TAP",
      "x": 0.5, "y": 0.5,
      "delay_after_ms": 300
    }
  }
}
```

All coordinates are normalised `[0.0 – 1.0]` screen-space. The engine converts to
physical pixels at dispatch time, so workflows are resolution-independent.

---

## Thermal Degradation Ladder

| Level    | targetFps | RL  | Capture Scale | Trigger (API 31+)                |
|----------|-----------|-----|---------------|----------------------------------|
| NORMAL   | 10        | ✓   | 100%          | headroom ≥ 1.0 (or NaN)         |
| LIGHT    | 8         | ✓   | 100%          | headroom ≥ 0.85                  |
| MODERATE | 5         | ✓   | 75%           | headroom ≥ 0.70                  |
| SEVERE   | 2         | ✗   | 50%           | headroom ≥ 0.50                  |
| CRITICAL | 0         | ✗   | 50%           | headroom < 0.50 → halt engine    |

---

## Dependencies

| Library               | Version  | Purpose                                |
|-----------------------|----------|----------------------------------------|
| Kotlin                | 2.0.21   | Language                               |
| Hilt                  | 2.52     | Dependency injection                   |
| Jetpack Compose BOM   | 2024.10  | Overlay UI                             |
| Room                  | 2.6.1    | Workflow + session + template storage  |
| DataStore             | 1.1.1    | Engine settings + checkpoints          |
| TFLite                | 2.14.0   | INT8 scene/button inference            |
| ML Kit OCR v2         | 16.0.1   | On-device text recognition             |
| OpenCV                | 4.9.0    | Template matching, contour detection   |
| Kotlinx Serialization | 1.7.3    | WorkflowDSL JSON codec                 |

---

## OpenCV Setup

OpenCV is NOT bundled in the repo (AAR is ~35 MB).

Download: https://github.com/opencv/opencv/releases/tag/4.9.0
File: `OpenCV-4.9.0-android-sdk.zip`

Steps:
1. Extract zip → `OpenCV-android-sdk/sdk/`
2. Copy `sdk/java/` → anywhere, or use the AAR:
3. `sdk/static/` → Not needed (we use the shared AAR)
4. Copy `OpenCV-android-sdk/sdk/OpenCV-Release.aar` → `titan-automation/app/libs/opencv.aar`

The build file already references `fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar")))`.

---

## Building

```bash
# Requires: JDK 17+, Android SDK (API 35), NDK r26+

cd titan-automation
./gradlew assembleDebug      # debug APK
./gradlew assembleRelease    # release APK (requires KEYSTORE_PATH env vars)
./gradlew lint               # lint check
```

Install on device:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

After installing:
1. Open TITAN Automation
2. Tap the accessibility icon → enable TitanAutomation in Accessibility settings
3. Tap the overlay icon → grant "Display over other apps"
4. (API 29 only) Tap "Grant Screen Capture"
5. Import a workflow JSON from the Workflows tab
6. Tap ▶ to start

---

## Adding a New Workflow

1. Create a JSON file following the DSL schema (see `assets/workflows/` for examples)
2. Import via the UI or copy to `assets/workflows/`
3. Place template PNG images in `assets/templates/<template_id>.png`
4. Tap ▶ to run

The engine will:
- Match templates at runtime using OpenCV
- Dispatch Bézier gestures through the AccessibilityService
- Use RL to optimise action selection if `rl_enabled: true`
- Checkpoint state to Room on every transition for crash recovery
