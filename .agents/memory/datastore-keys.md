---
name: WorkflowDataStore keys for macro defaults
description: All macro-default keys that exist in WorkflowDataStore
---

All keys exist in `WorkflowDataStore.Keys`:
- `DEFAULT_SHOW_DOTS` (Boolean) — default true
- `DEFAULT_JITTER_ENABLED` (Boolean) — default true
- `DEFAULT_JITTER_RADIUS` (Float) — default 3f, clamped 0..15
- `DEFAULT_SPEED` (Float) — default 1f, clamped 0.25..4
- `RESPECT_THERMAL` (Boolean) — default true
- `TOUCH_NOISE_STDDEV` (Float) — default 3f, clamped 0..10

Read flows and write methods all added to WorkflowDataStore for each.

**How to apply:** TitanSettingsViewModel exposes all as StateFlows with 5s WhileSubscribed timeout.
