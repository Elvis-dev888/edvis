plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.edvis.core"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file("../edvis.keystore")
            storePassword = "edvis12345"
            keyAlias = "edvis"
            keyPassword = "edvis12345"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.edvis.core"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-TACTICAL"

        ndk {
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += listOf("fst", "mdl", "ie", "conf", "stats", "bin", "md")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Coroutines for non-blocking audio & socket pipelines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OkHttp WebSocket client for full-duplex PCM & JSON streaming
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Vosk Offline Speech Recognition for local Wake Word ("edvis")
    implementation("com.alphacephei:vosk-android:0.3.75")

    // Google Play Services Location for continuous GPS Telemetry
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
