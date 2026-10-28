plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.shared.component.pricing"
}

dependencies {
    implementation(project(":core:network"))
}
