---
name: TitanEvent GestureDispatched fields
description: Correct fields for TitanEvent.GestureDispatched — easy to get wrong
---

`TitanEvent.GestureDispatched` has exactly 4 fields:
```kotlin
data class GestureDispatched(
    val type: String, val x: Float, val y: Float, val success: Boolean
) : TitanEvent()
```

**x and y are normalized [0..1]** screen-space, NOT pixels. When logging, use `(event.x*100).toInt()` to show percentages.

**Why:** Previous session incorrectly emitted `GestureDispatched(description = "...")` which caused a compile error.

**How to apply:** Any emission must use `TitanEvent.GestureDispatched(type = "TAP", x = action.x, y = action.y, success = ok)`.
