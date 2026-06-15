---
name: MacroBuilderViewModel exportedJson state
description: The _exportedJson StateFlow must be declared; exportMacroJson sets it.
---

`MacroBuilderViewModel` calls `_exportedJson.value = null` in `clearExportedJson()`, so the backing field must be declared.

**Why:** The field was missing — compile error. `exportMacroJson` originally only returned a String but never set state.

**How to apply:** Declare in the VM alongside other state fields:
```kotlin
private val _exportedJson = MutableStateFlow<String?>(null)
val exportedJson: StateFlow<String?> = _exportedJson.asStateFlow()
```

And update `exportMacroJson` to both set and return:
```kotlin
fun exportMacroJson(macro: SimpleMacro): String {
    val json = exportJson.encodeToString(macro)
    _exportedJson.value = json
    return json
}
```
