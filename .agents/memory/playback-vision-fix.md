---
name: SimplePlaybackEngine VisionEngine call
description: How to correctly call VisionEngine.findTemplate from SimplePlaybackEngine.waitForImage
---

VisionEngine.findTemplate signature: `findTemplate(frame: Bitmap, template: Bitmap, rule: VisionMatchRule): MatchResult?`

MatchResult fields: `normX: Float`, `normY: Float`, `confidence: Float`, `templateId: String` — there are NO `cx`/`cy` fields.

**Why:** The original code called `findTemplate(rule, frame)` (wrong order, missing template arg) and accessed `match.cx`/`match.cy` (non-existent fields). Both are compile errors.

**How to apply:** In `waitForImage`, load the template bitmap first:
```kotlin
val templateEntity = db.templateDao().getById(action.templateId)
val templateBitmap = templateEntity?.let {
    BitmapFactory.decodeByteArray(it.bitmapBytes, 0, it.bitmapBytes.size)
} ?: return@withTimeoutOrNull null

val match = visionEngine.findTemplate(frame, templateBitmap, rule)
return@withTimeoutOrNull Pair(match.normX, match.normY)
```
MacroDatabase is already injected into SimplePlaybackEngine as `db`.
