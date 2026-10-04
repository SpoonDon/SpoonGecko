plugins {
    id("com.android.application")
}

android {
    namespace = "com.spoongecko.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spoongecko.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // Target device: Xiaomi "uke" — arm64 only. Do not add x86 or armeabi-v7a.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Landmine #2 — libxul.so (~130 MB) cannot be mmap'd from inside the APK
    // on HyperOS. Force legacy packaging so the native libs get extracted to
    // the app's data dir on install.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.mozilla.geckoview:geckoview:157.0.20260924084938")
}
