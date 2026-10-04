import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// GeckoView pinned to the latest stable from maven.mozilla.org
val geckoViewVersion = "157.0.20260924084938"

// AGP 9.1.0 bundles Kotlin compiler 2.2.0. Any library that drags in a
// kotlin-stdlib newer than that will trigger "incompatible metadata
// version" errors at compile time. Force everything to match.
val kotlinStdlibVersion = "2.2.0"

android {
    namespace = "com.spoongecko.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.spoongecko.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        vectorDrawables { useSupportLibrary = true }

        ndk {
            // GeckoView ships a ~130 MB libxul.so per CPU architecture.
            // Unfiltered APKs carry four copies (~550 MB total).
            // Xiaomi 2410CRP4CI reports SUPPORTED_ABIS: arm64-v8a, and every
            // Android phone from ~2017 onward is arm64. Ship only that.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (!path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
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
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES"
        )
        jniLibs {
            // Android's loader cannot reliably mmap GeckoView's 130 MB libxul.so
            // from inside the APK. Force the installer to extract native libs
            // to disk. Fixes silent SIGSEGV on MIUI/HyperOS and several other ROMs.
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Force kotlin-stdlib (and its jdk7/jdk8 shims) down to the compiler's version.
configurations.all {
    resolutionStrategy {
        force(
            "org.jetbrains.kotlin:kotlin-stdlib:$kotlinStdlibVersion",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:$kotlinStdlibVersion",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinStdlibVersion",
            "org.jetbrains.kotlin:kotlin-reflect:$kotlinStdlibVersion"
        )
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoViewVersion")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
