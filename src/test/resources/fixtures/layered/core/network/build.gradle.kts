plugins {
    kotlin("jvm")
}

val serializationModule = ":core:serialization"

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Deliberately unresolvable: the module path is a variable, so only Gradle knows it.
    implementation(project(serializationModule))
}
