---
name: GovernorState fields
description: Correct field names on GovernorState from ThermalGovernor
---

```kotlin
data class GovernorState(
    val thermalLevel : ThermalLevel = ThermalLevel.NORMAL,
    val targetFps    : Int          = 10,
    val rlEnabled    : Boolean      = true,
    ...
)
enum class ThermalLevel { NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL }
```

**The field is `thermalLevel` (NOT `level`).** Access ordinal via `thermal.thermalLevel.ordinal`.

ThermalGovernor exposes: `val state: StateFlow<GovernorState>`, `val isCritical: Boolean`, `val targetFps: Int`, `val rlEnabled: Boolean`.

**Why:** SettingsTab originally used `thermal.level` which doesn't exist on GovernorState.
