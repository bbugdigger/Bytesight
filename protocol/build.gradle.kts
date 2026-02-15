import com.google.protobuf.gradle.id

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.protobuf)
}

group = "com.bugdigger"
version = "1.0.0"

dependencies {
    // gRPC & Protobuf
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.grpc.protobuf)
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin)

    // Coroutines for gRPC-Kotlin
    implementation(libs.kotlinx.coroutines.core)

    // Testing
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit5.api)
    testRuntimeOnly(libs.junit5.engine)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.28.2"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.68.0"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            task.builtins {
                id("kotlin")
            }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
}
