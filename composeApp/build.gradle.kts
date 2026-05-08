import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.testJunit5)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)

            // Project modules
            implementation(project(":protocol"))
            implementation(project(":core"))
            implementation(project(":ai"))

            // gRPC Client
            implementation(libs.grpc.netty.shaded)

            // DI - Koin
            implementation(libs.koin.core)
            implementation(libs.koin.compose)

            // Project file persistence
            implementation(libs.kotlinx.serialization.json)

            // Logging
            implementation(libs.slf4j.api)
            implementation(libs.logback.classic)
        }
        jvmTest.dependencies {
            implementation(libs.junit5.api)
            implementation(libs.mockk)
            implementation(libs.kotlinx.coroutines.test)
            // ASM is needed for synthesizing tiny test JARs (JarClassSourceTest, etc.).
            // core uses ASM with `implementation` scope so it doesn't leak transitively;
            // pull it in explicitly here.
            implementation(libs.asm)
            runtimeOnly(libs.junit5.engine)
        }
    }
}


compose.desktop {
    application {
        mainClass = "com.bugdigger.bytesight.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.bugdigger.bytesight"
            packageVersion = "1.0.0"

            // Include JDK modules needed for JVM Attach API
            modules("jdk.attach")
        }

        // JVM args for the application
        jvmArgs("-Djdk.attach.allowAttachSelf=true")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    
    // Ensure agent and sample JARs are built before integration tests
    dependsOn(":agent:agentJar", ":sample:jar")
    
    // Pass project root to tests
    systemProperty("user.dir", rootProject.projectDir.absolutePath)
}
