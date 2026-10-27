plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Every experiment gets independent Gradle and native outputs. Never reuse the
// historical .cxx tree when comparing different native configurations.
val glassNativeProfile = providers.gradleProperty("nativeProfile").getOrElse("compact")
require(glassNativeProfile in listOf("baseline", "exports", "crypto-size", "compact")) {
    "nativeProfile must be baseline, exports, crypto-size, or compact"
}
val tvAbiOnly = providers.gradleProperty("tvAbiOnly").orNull?.let {
    if (it.isEmpty()) true else it.toBooleanStrict()
} ?: false
val glassAbis = providers.gradleProperty("targetAbis").orNull
    ?.split(",")?.map { it.trim() } ?: if (tvAbiOnly) listOf("armeabi-v7a")
    else listOf("armeabi-v7a", "arm64-v8a")
require(glassAbis.isNotEmpty() && glassAbis.distinct().size == glassAbis.size &&
        glassAbis.all { it in listOf("armeabi-v7a", "arm64-v8a") }) {
    "targetAbis must contain unique supported ARM ABIs"
}
extra["glassAbis"] = glassAbis
extra["glassNativeProfile"] = glassNativeProfile
allprojects {
    layout.buildDirectory.set(rootProject.layout.projectDirectory.dir(".build/$glassNativeProfile/${project.name}"))
}
