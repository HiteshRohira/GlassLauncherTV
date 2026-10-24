// Built-in AirPlay receiver engine. Native UxPlay core, renderers, discovery
// and the receiver service are adapted from jqssun/android-airplay-server
// (GPL-3.0, see LICENSE in this module); the launcher supplies the UI.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.jqssun.airplay"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { prefab = true }
}

kotlin { jvmToolchain(17) }

// UxPlay is pinned as a submodule; apply the receiver's compatibility patches
// to its working tree before CMake configures.
tasks.register("applyUxplayPatches") {
    val uxplay = file("src/main/cpp/third_party/UxPlay")
    val patchDir = file("src/main/cpp/patches/UxPlay")
    doLast {
        fun git(vararg args: String): String {
            val proc = ProcessBuilder("git", "-C", uxplay.path, *args).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            check(proc.waitFor() == 0) { "git ${args.joinToString(" ")} failed:\n$out" }
            return out
        }
        val patches = patchDir.listFiles { f -> f.extension == "patch" }!!.sorted()
        val touched = patches.flatMap { git("apply", "--numstat", it.path).trim().lines() }
            .map { it.substringAfterLast("\t") }.distinct()
        git("checkout", "--", *touched.toTypedArray())
        patches.forEach { git("apply", "--unidiff-zero", it.path) }
    }
}
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn("applyUxplayPatches")
}

dependencies {
    implementation("androidx.core:core:1.16.0")
    api(libs.androidx.lifecycle.service)
    implementation(libs.androidx.media)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.kotlinx.coroutines)
    implementation(libs.oboe)
}
