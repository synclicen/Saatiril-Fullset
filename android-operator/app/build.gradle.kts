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
        versionCode = 11
        versionName = "1.0.11-socketio-fix"

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
    // socket.io-client 2.1.0 depends on engine.io-client 2.1.0 → OkHttp 3.12.12
    // Coil has been removed to avoid OkHttp 3.x/4.x version conflict.
    // If adding Coil later, use coil-compose-base (no OkHttp) or handle version conflict.
    implementation("io.socket:socket.io-client:2.1.0")

    // CameraX — camera preview and capture for built-in cameras
    // Camera2CameraInfo interop is included in camera-camera2 module
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.camera:camera-view:1.3.1")

    // UVCCamera — Native USB camera support (v10)
    // Talks directly to USB video devices via USB Host API, bypassing
    // Android's broken Camera2 HAL. This is the ONLY approach that works
    // with USB HDMI capture cards on Xiaomi/Redmi devices.
    implementation("com.herohan:UVCAndroid:1.0.13")

    // Image loading — using coil-base without OkHttp dependency to avoid
    // OkHttp 3.x/4.x version conflict with socket.io-client's engine.io-client
    // If image loading is needed later, use AsyncImage with a custom fetcher
    // or switch to a library that doesn't depend on OkHttp (e.g., Glance)
    // For now, Coil is NOT used in the codebase — removed to prevent runtime conflicts

    // JSON parsing
    implementation("com.google.code.gson:gson:2.10.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
