plugins {
    id("java")
}

group = "com.bugdigger"
version = "1.0.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    // Protocol module (gRPC generated code)
    implementation(project(":protocol"))

    // gRPC Server
    implementation(libs.grpc.netty.shaded)

    // Byte Buddy for instrumentation
    implementation(libs.bytebuddy)
    implementation(libs.bytebuddy.agent)

    // Logging
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.junit5.api)
    testImplementation(libs.junit5.params)
    testRuntimeOnly(libs.junit5.engine)
}

tasks.test {
    useJUnitPlatform()
}

// Build a fat JAR with all dependencies for the agent
tasks.register<Jar>("agentJar") {
    archiveClassifier.set("agent")
    
    manifest {
        attributes(
            "Premain-Class" to "com.bugdigger.agent.BytesightAgent",
            "Agent-Class" to "com.bugdigger.agent.BytesightAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true"
        )
    }

    // Include all runtime dependencies
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())

    // Handle duplicate files
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // Exclude signature files from dependencies
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}

// Make agentJar run after jar
tasks.named("agentJar") {
    dependsOn(tasks.jar)
}

// Add agentJar to build
tasks.build {
    dependsOn("agentJar")
}
