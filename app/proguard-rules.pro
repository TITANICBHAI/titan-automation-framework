# ─── Titan Automation ProGuard / R8 rules ────────────────────────────────────
# Spec §14 — full production ProGuard ruleset

# ── Android Services & Receivers ─────────────────────────────────────────────
# Framework instantiates these reflectively; never obfuscate or strip them.
-keep class com.titan.automation.engine.accessibility.MacroAccessibilityService { *; }
-keep class com.titan.automation.engine.capture.ScreenCaptureService { *; }
-keep class com.titan.automation.engine.overlay.OverlayService { *; }
-keep class com.titan.automation.engine.watchdog.WatchdogService { *; }
-keep class com.titan.automation.engine.watchdog.BootReceiver { *; }
-keep class com.titan.automation.TitanApplication { *; }
-keep public class * extends android.app.Application
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.accessibilityservice.AccessibilityService

# ── Hilt & Dagger Dependency Injection ───────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class dagger.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keepclassmembers class * {
    @dagger.hilt.android.AndroidEntryPoint *;
    @javax.inject.Inject <init>(...);
    @javax.inject.Inject <fields>;
}
-keepclassmembers @dagger.hilt.android.lifecycle.HiltViewModel class * {
    <init>(...);
}

# ── Room Database ─────────────────────────────────────────────────────────────
-keep class com.titan.automation.data.db.** { *; }
-keep class com.titan.automation.data.db.entity.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-keep @androidx.room.Database class * { *; }
-keep @androidx.room.TypeConverter class * { *; }
-keep @androidx.room.TypeConverters class * { *; }

# ── Kotlinx Serialization ─────────────────────────────────────────────────────
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-dontnote kotlinx.serialization.SerializationKt
-keep,includedescriptorclasses class com.titan.automation.**$$serializer { *; }
-keepclassmembers class com.titan.automation.** {
    *** Companion;
}
-keepclasseswithmembers class com.titan.automation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep all @Serializable model classes
-keep @kotlinx.serialization.Serializable class com.titan.automation.domain.model.** { *; }

# ── TensorFlow Lite JNI ───────────────────────────────────────────────────────
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**
-keep class org.tensorflow.** { *; }

# ── ML Kit Text Recognition v2 ────────────────────────────────────────────────
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text_latin.** { *; }
-dontwarn com.google.mlkit.**

# ── OpenCV JNI ────────────────────────────────────────────────────────────────
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { native <methods>; }
-keepclasseswithmembers class * {
    native <methods>;
}
# Titan JNI bridge
-keep class com.titan.automation.engine.capture.NativeBridge { *; }
-keepclassmembers class com.titan.automation.engine.capture.NativeBridge {
    native <methods>;
}

# ── DataStore ─────────────────────────────────────────────────────────────────
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class androidx.datastore.** { *; }
-keep class com.titan.automation.data.datastore.** { *; }

# ── Kotlin & Coroutines ───────────────────────────────────────────────────────
-keepattributes RuntimeVisibleAnnotations
-keepattributes SourceFile, LineNumberTable, Signature, Exceptions
-keep class kotlin.Metadata { *; }
-keep class kotlin.reflect.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**
-keep class kotlinx.coroutines.** { *; }
# Keep coroutine debug info for crash reports
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# ── Jetpack Compose ───────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class com.titan.automation.engine.overlay.ui.** { *; }
-keep class com.titan.automation.presentation.** { *; }

# ── Plugin system ─────────────────────────────────────────────────────────────
# Plugins are loaded at runtime via PathClassLoader — keep their contract interface
-keep interface com.titan.automation.plugins.TitanPlugin { *; }
-keep interface com.titan.automation.plugins.PluginContext { *; }
-keep class com.titan.automation.plugins.PluginResult { *; }
-keep class com.titan.automation.plugins.PluginInfo { *; }

# ── Security / Integrity ──────────────────────────────────────────────────────
-keep class com.titan.automation.security.IntegrityGuard { *; }

# ── Crash Reporter ────────────────────────────────────────────────────────────
# Keep stack trace element info (essential for readable crash reports)
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# ── General Android rules ─────────────────────────────────────────────────────
-dontwarn sun.misc.**
-dontwarn java.lang.instrument.**
-dontwarn okhttp3.**
-dontnote android.**
-dontnote com.google.android.**
