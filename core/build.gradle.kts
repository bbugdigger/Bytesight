plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.bugdigger"
version = "1.0.0"

dependencies {
    // Bytecode Analysis
    implementation(libs.asm)
    implementation(libs.asm.util)

    // Decompilation
    implementation(libs.vineflower)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Serialization (project file format)
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
