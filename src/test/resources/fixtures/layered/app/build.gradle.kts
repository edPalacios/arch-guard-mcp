plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "com.example.layered"
}

dependencies {
    implementation(project(":feature:account:profile"))
    implementation(project(":feature:orders:history"))
    implementation(project(":feature:orders:tracking"))
    implementation(project(":component:auth"))
    implementation(projects.sharedComponent.cart)
    implementation(project(":core:ui"))
    implementation("androidx.core:core-ktx:1.13.1")

    // A test configuration is not a module dependency.
    testImplementation(project(":core:network"))
    testImplementation("junit:junit:4.13.2")
}
