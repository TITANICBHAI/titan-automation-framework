---
name: ScheduleMode enum location
description: Single source of truth for ScheduleMode to avoid duplicate enums
---

`ScheduleMode` is defined once in `com.titan.automation.domain.model.SimpleMacro.kt`:
```kotlin
enum class ScheduleMode { MANUAL, ONCE, INTERVAL, REPEAT }
```

`MacroScheduler.kt` imports from domain — it does NOT define its own `ScheduleMode`.
`ScheduledJob.progressLabel` must handle all 4 values (including MANUAL → "").

**Why:** Originally had duplicate ScheduleMode in MacroScheduler which caused conflicts.
