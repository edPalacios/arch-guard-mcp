plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.example.layered.component.auth"
}

dependencies {
    api(project(":shared-component:pricing"))
    implementation(project(":core:network"))
}
