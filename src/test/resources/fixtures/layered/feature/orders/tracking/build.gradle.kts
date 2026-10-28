plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.feature.orders.tracking"
}

dependencies {
    implementation(project(":shared-component:cart"))
    implementation(project(":core:ui"))
    implementation(project(":core:network"))
    // The same edge declared twice is still one edge.
    implementation(project(":core:network"))
}
