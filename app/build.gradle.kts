plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.titan.automation"
    compileSdk = 35

    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.titan.automation"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"

        ndk {
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                // Only pass OPENCV_DIR when the SDK is actually present.
                // An empty/missing OPENCV_SDK_DIR must NOT produce a non-empty
                // -DOPENCV_DIR string — that tricks CMake into linking a .so
                // that doesn't exist (causing ninja build failures on CI).
                val opencvSdkDir = System.getenv("OPENCV_SDK_DIR")
                if (!opencvSdkDir.isNullOrEmpty()) {
                    arguments += listOf("-DOPENCV_DIR=$opencvSdkDir/sdk/native")
                }
            }
        }

        javaCompileOptions {
            annotationProcessorOptions {
                arguments["room.schemaLocation"] = "$projectDir/schemas"
                arguments["room.incremental"]    = "true"
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile   = System.getenv("KEYSTORE_PATH")?.let { file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias    = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (System.getenv("KEYSTORE_PATH") != null) {
                signingConfig = signingConfigs["release"]
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/versions/**"
            )
            pickFirsts += setOf(
                "**/libc++_shared.so",
                "**/libtensorflowlite_jni.so",
                "**/libopencv_java4.so"
            )
        }
        jniLibs {
            // Keep native debug symbols in debug builds for crash symbolication
            keepDebugSymbols += setOf("**/*.so")
        }
    }

    // ── JNI / NDK ─────────────────────────────────────────────────────────────
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Core Kotlin
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.service)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Room Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore
    implementation(libs.datastore.preferences)
    implementation(libs.datastore.core)

    // Security (encrypted DataStore)
    implementation(libs.security.crypto)

    // TensorFlow Lite (INT8 on-device inference — optional, not on critical path)
    implementation(libs.tflite)
    implementation(libs.tflite.support)

    // ML Kit OCR v2
    implementation(libs.mlkit.text.recognition)

    // OpenCV 4.9.0
    // Local path: place opencv.aar in app/libs/ for native SO + full SDK
    //   → Download: https://github.com/opencv/opencv/releases/tag/4.9.0
    //   → Extract OpenCV-4.9.0-android-sdk/sdk/native → app/libs/opencv.aar
    // CI / no local AAR: falls back to org.opencv:opencv:4.9.0 on Maven Central
    //   (same Java API, slightly larger download, no pre-built native SO)
    val opencvLocalAar = file("$projectDir/libs/opencv.aar")
    if (opencvLocalAar.exists()) {
        implementation(files("$projectDir/libs/opencv.aar"))
        implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    } else {
        implementation("org.opencv:opencv:4.9.0")
        implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
    }
}
