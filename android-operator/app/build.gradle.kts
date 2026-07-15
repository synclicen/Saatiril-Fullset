plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.saatiril.operator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.saatiril.operator"
        minSdk = 24
        targetSdk = 34
        versionCode = 19
        versionName = "1.0.19-frame-overlay-fix"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Disable lint to save memory during build
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Socket.io — Saatiril protocol communication
    implementation("io.socket:socket.io-client:2.1.0")

    // ═══════════════════════════════════════════════════════════════
    // UVCCamera — Direct USB Video Class access for HDMI capture cards
    // ═══════════════════════════════════════════════════════════════
    // v17: Using alexey-pelykh/UVCCamera fork (org.uvccamera:lib) on Maven Central.
    // This is a maintained hard fork of the original saki4510t/UVCCamera.
    // Same com.serenegiant.usb.* package namespace — no code changes needed.
    //
    // CRITICAL: Camera2/CameraX API CANNOT access USB HDMI video capture
    // cards on Android. USB capture cards are UVC (USB Video Class) devices
    // and require a dedicated UVC library to access them via USB Host API.
    //
    // v17 FIX: MacroSilicon (VID:345F) black screen fix:
    //   1. FORCE MJPEG format + lock 720p (never YUYV)
    //   2. setBandwidthFactor(1.0f) immediately after open
    //   3. Proper TextureView surface setup before startPreview()
    implementation("org.uvccamera:lib:0.0.13")

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
