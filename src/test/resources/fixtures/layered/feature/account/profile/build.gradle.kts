plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.feature.account.profile"
}

// Type-safe project accessors instead of project(":...") paths.
dependencies {
    implementation(projects.component.auth)
    implementation(projects.sharedComponent.pricing)
    compileOnly(projects.core.ui)
}
