# ─── Titan Automation ProGuard / R8 rules ────────────────────────────────────

# Keep AccessibilityService subclass — Android framework reflectively instantiates it
-keep class com.titan.automation.engine.accessibility.MacroAccessibilityService { *; }
-keep class com.titan.automation.engine.capture.ScreenCaptureService { *; }
-keep class com.titan.automation.engine.overlay.OverlayService { *; }
-keep class com.titan.automation.engine.watchdog.WatchdogService { *; }
-keep class com.titan.automation.engine.watchdog.BootReceiver { *; }

# Keep Hilt-generated components
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keepclassmembers class * {
    @dagger.hilt.android.AndroidEntryPoint *;
}

# Room — keep entity classes and DAOs
-keep class com.titan.automation.data.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }

# TensorFlow Lite JNI — prevent stripping of native bindings
-keep class org.tensorflow.lite.** { *; }
-keepclassmembers class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# ML Kit OCR — reflection-loaded classes
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# OpenCV JNI — native loader
-keep class org.opencv.** { *; }
-keepclassmembers class org.opencv.** { native <methods>; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.titan.automation.**$$serializer { *; }
-keepclassmembers class com.titan.automation.** {
    *** Companion;
}
-keepclasseswithmembers class com.titan.automation.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# DataStore — keep proto and preferences classes
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Kotlin reflection
-keepattributes RuntimeVisibleAnnotations
-keepattributes SourceFile, LineNumberTable
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
