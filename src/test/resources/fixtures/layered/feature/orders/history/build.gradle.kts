plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.feature.orders.history"
}

dependencies {
    implementation(project(":component:orders:history"))
    implementation(project(":shared-component:cart"))
    implementation(project(":core:ui"))
    implementation(project(":core:network"))
    // Deliberate violation: a feature module depending on another feature module.
    implementation(project(":feature:orders:tracking"))
    // A dependency helper: the modules it adds are invisible to the guard.
    commonLibs(project, "implementation")

    androidTestImplementation(project(":core:network"))
}
