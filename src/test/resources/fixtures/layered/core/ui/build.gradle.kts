plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.core.ui"
}

dependencies {
    implementation(project(":core:network"))
    // Deliberate violation: a core module depending on a feature module.
    implementation(project(":feature:account:profile"))
    // Deliberately dangling: settings.gradle.kts includes no such module, so Gradle fails on it.
    // Its path matches the feature layer, yet a dangling dependency is nobody's violation.
    implementation(project(":feature:account:favorites"))
}
