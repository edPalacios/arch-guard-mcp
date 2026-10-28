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
}
