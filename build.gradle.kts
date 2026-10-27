plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
    application
}

group = "com.archguard"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // Server-only artifact: we don't need the client half of the umbrella `kotlin-sdk`.
    implementation("io.modelcontextprotocol:kotlin-sdk-server:0.16.0")
    // SLF4J backend for the SDK's kotlin-logging. slf4j-simple writes to stderr,
    // which keeps stdout clean for the JSON-RPC stream.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.archguard.mcp.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("arch-guard-mcp")
    archiveClassifier.set("all")
    archiveVersion.set("")
    mergeServiceFiles()
}
