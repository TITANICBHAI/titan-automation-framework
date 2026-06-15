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
  2. evaluateConditions(conditions[])            ← IF/ELSE branch list (first match wins)
       ├── VISION_MATCH  → VisionEngine.findTemplate()
       ├── ORB_MATCH     → VisionEngine.matchOrb()        ← rotation/scale-invariant
       ├── HISTOGRAM_MATCH → VisionEngine.compareHistogram() ← colour-based scene ID
       ├── OCR_CONTAINS  → VisionEngine.runOcr()
       ├── STATE_FLAG    → session.currentState == value
       ├── BATTERY_BELOW → ThermalGovernor.batteryPct
       └── THERMAL_ABOVE → ThermalGovernor.thermalLevel
  3. visionEngine.findTemplate / matchOrb (primary vision rule, if no branch matched)
  4. visionEngine.runOcr(frame, ocrRule)         ← ML Kit OCR, regex match
  5. visionEngine.compareHistogram(frame, ref)   ← histogram scan rule (optional)
  6. rlEngine.getBestAction(state, actions)      ← ε-greedy Q-lookup (if rl_enabled)
  7. accessibility.dispatchSwipe/Tap/TypeText()  ← GestureDescription Bézier path
  8. rlEngine.learn(s, a, r, s')                 ← Q-update + SPA step penalty
  9. repository.saveSession(checkpoint)          ← Room + DataStore
 10. branch to next state / retry / timeout-recovery
```

---

## Workflow DSL Schema

Every state supports up to three parallel sensing rules plus a conditional branch list:

| Field                | Type               | Purpose                                                 |
|----------------------|--------------------|---------------------------------------------------------|
| `vision_match_rule`  | `VisionMatchRule`  | Template or ORB match (set `match_mode: "ORB"`)        |
| `ocr_scan_rule`      | `OcrScanRule`      | ML Kit OCR regex match in ROI                           |
| `histogram_scan_rule`| `HistogramScanRule`| HSV histogram similarity vs reference screenshot        |
| `conditions`         | `ConditionalBranch[]` | IF/ELSE branch list — first match short-circuits    |

### VisionMatchRule — match modes

```json
{ "template_id": "icon",  "match_mode": "TEMPLATE", "min_confidence": 0.85, "action_intent": "TAP_IT" }
{ "template_id": "logo",  "match_mode": "ORB",      "min_confidence": 0.60, "action_intent": "TAP_IT" }
```

- `TEMPLATE` (default) — `TM_CCOEFF_NORMED` multi-scale; sensitive to rotation/scale
- `ORB` — ORB keypoints + BFMatcher Hamming + Lowe ratio test; **rotation/scale-invariant**

### HistogramScanRule — colour-based scene detection

```json
{
  "histogram_scan_rule": {
    "reference_template_id": "home_screen_reference",
    "region": { "left": 0, "top": 0, "right": 1080, "bottom": 400 },
    "min_similarity": 0.72,
    "action_intent": "WAIT_NO_ACTION"
  }
}
```

Compares 2-D HSV histogram (50 H × 60 S bins) of current frame vs reference using
`HISTCMP_CORREL`. Use for scene/state detection without a pixel-perfect template.

### ConditionalBranch — IF/ELSE

```json
{
  "conditions": [
    { "condition_type": "OCR_CONTAINS",    "value": "Game Over",         "action_intent": "TAP_RETRY",  "next_state": "PLAYING" },
    { "condition_type": "VISION_MATCH",    "value": "obstacle_left",     "action_intent": "SWIPE_RIGHT","next_state": "PLAYING" },
    { "condition_type": "ORB_MATCH",       "value": "loading_spinner",   "action_intent": "WAIT",       "next_state": "LOADING" },
    { "condition_type": "HISTOGRAM_MATCH", "value": "victory_screen_ref","action_intent": "COLLECT_REWARD","next_state": "REWARD" },
    { "condition_type": "BATTERY_BELOW",   "value": "10",                "action_intent": "WAIT",       "next_state": "END" },
    { "condition_type": "THERMAL_ABOVE",   "value": "SEVERE",            "action_intent": "WAIT",       "next_state": "END" }
  ]
}
```

### Action types

| `interaction_type` | Required fields       | Notes                                     |
|--------------------|----------------------|-------------------------------------------|
| `TAP`              | x, y                 | Bézier tap with Gaussian noise            |
| `LONG_PRESS`       | x, y, duration_ms    | Hold gesture                              |
| `SWIPE`            | x, y, end_x, end_y   | Cubic Bézier curve, configurable duration |
| `MULTI_TOUCH`      | x, y, end_x, end_y   | Two-pointer pinch/zoom gesture            |
| `TYPE_TEXT`        | x, y, text_input     | AccessibilityNodeInfo.ACTION_SET_TEXT     |
| `WAIT`             | duration_ms          | Sleep without gesture                     |

All coordinates are **absolute pixels** in the workflow JSON. The engine converts to
normalised [0..1] screen-space at dispatch time, so templates are DPI-independent.

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
