plugins {
    alias(libs.plugins.kotlinJvm)
}

group = "com.bugdigger"
version = "1.0.0"

dependencies {
    // Project modules
    implementation(project(":protocol"))
    implementation(project(":core"))

    // Koog — JetBrains Kotlin-native AI agents framework
    implementation(libs.koog.agents)

    // Coroutines
    implementation(libs.kotlinx.coroutines.core)

    // Logging
    implementation(libs.slf4j.api)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.api)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit5.engine)
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
