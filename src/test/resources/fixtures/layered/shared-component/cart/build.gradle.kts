plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.shared.component.cart"
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:network"))
}
