plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace  = "tv.glass.launcher"
    compileSdk = 36

    defaultConfig {
        applicationId = "tv.glass.launcher"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 23
        versionName   = "1.1.7-airplay.8"
        resourceConfigurations += listOf("en")

        // Instrumentation test runner. Required so :app:connectedDebugAndroidTest
        // (and any local emulator run) can find and execute the smoke test
        // under src/androidTest. JUnit 4 runner shipped with androidx.test.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ABIs for the AirPlay engine's native library: ARMv7 (this TCL and
        // most TV SoCs run 32-bit userspace) and ARM64 (newer TVs and the
        // Apple-silicon Android TV emulator). Each extra ABI rebuilds
        // OpenSSL and FFmpeg, so x86 emulators are intentionally omitted.
        ndk {
            // Glass: -PtvAbiOnly builds ARMv7 only (this TV's 32-bit userspace) for a
            // smaller APK and faster build; the default keeps ARM64 for emulators.
            abiFilters += if (project.hasProperty("tvAbiOnly")) listOf("armeabi-v7a") else listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Keep library metadata out of the APK; dadb itself is called from Java
    // but its implementation uses Kotlin and Okio, so their bytecode remains.
    packaging {
        resources {
            // Keep META-INF/services: coroutines (used by the AirPlay engine)
            // discovers its Android main dispatcher through ServiceLoader.
            excludes += setOf(
                "kotlin/**",
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/com.android.tools/**",
                "DebugProbesKt.bin",
                "**.properties"
            )
        }
        // Native libs come only from the AirPlay engine; keep them stored
        // uncompressed and page-aligned so they map straight from the APK.
        jniLibs {
            useLegacyPackaging = false
        }
    }

    dependenciesInfo {
        includeInApk    = false
        includeInBundle = false
    }

    buildFeatures {
        buildConfig = false
        resValues   = false
    }

    // Release lint fails on errors and writes local text/HTML/XML reports.
    lint {
        abortOnError       = true
        checkReleaseBuilds = true
        warningsAsErrors   = false
        // Check this module; dependency analysis is a separate lint option.
        checkDependencies  = false
        // Generate local reports; no CI upload workflow is configured.
        textReport         = true
        htmlReport         = true
        xmlReport          = true
        lintConfig         = file("lint.xml")
        // SyntheticAccessor: this codebase intentionally uses inner classes
        // (RecyclingShelfView, CellView) that touch outer-class fields. The
        // synthetic accessors lint flags are a real cost on Dalvik but
        // negligible on ART (minSdk 26 ⇒ ART always — ART has been the
        // default Android runtime since API 21 / Lollipop). Disable rather
        // than restructure, which would be far more invasive than the
        // saving justifies.
        disable += setOf("SyntheticAccessor")
    }
}

// Keep AndroidX KTX out of production. The local-ADB client is implemented
// in Kotlin internally, so kotlin-stdlib must remain on the production
// classpath alongside the Java launcher and Kotlin AirPlay module.
configurations.matching {
    val n = it.name
    !n.contains("Test", ignoreCase = true) &&
            !n.startsWith("androidTest") &&
            !n.startsWith("test")
}.configureEach {
    exclude(group = "androidx.core", module = "core-ktx")
}

dependencies {
    // Built-in AirPlay receiver (engine, renderers, player activity).
    implementation(project(":airplay"))

    // Local ADB for Home setup/restore and the phone-remote control bridge.
    // The client is managed code; native ABI support is set above.
    implementation("dev.mobile:dadb:1.2.9") {
        exclude(group = "org.graalvm.buildtools")
        exclude(group = "org.junit.platform")
    }

    // QR-code generation for phone pairing; JUnit below is host-only.
    implementation("com.google.zxing:core:3.5.3")

    // Installs the Baseline Profile (src/main/baseline-prof.txt) on
    // sideloaded builds, which get no Play cloud profile.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    testImplementation("junit:junit:4.13.2")

    // Instrumentation tests require separate androidTest compile/device tasks.
    // assembleRelease and JVM unit tests do not run device tests.
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // androidx.test:core hosts ActivityScenario / ActivityScenarioRule —
    // the modern, deterministic replacement for the deprecated
    // ActivityTestRule API. Pulled in transitively by ext:junit but
    // declared explicitly here because the smoke test imports
    // ActivityScenario directly; surfacing the dep makes the test's
    // contract self-evident in this file.
    androidTestImplementation("androidx.test:core:1.7.0")
}
