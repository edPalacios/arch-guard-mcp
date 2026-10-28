plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.component.orders.history"
}

dependencies {
    implementation(project(":shared-component:cart"))
    implementation(project(":core:ui"))
    implementation(project(":core:network"))
}
