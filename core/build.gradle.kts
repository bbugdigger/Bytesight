plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "com.bugdigger"
version = "1.0.0"

dependencies {
    // Decompilation
    implementation(libs.vineflower)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

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
