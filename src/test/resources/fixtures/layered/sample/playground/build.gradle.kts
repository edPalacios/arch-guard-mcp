plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.layered.sample.playground"
}

// A sample is excluded from the layers, so these dependencies are nobody's violation.
dependencies {
    implementation(project(":app"))
    implementation(project(":core:ui"))
}
