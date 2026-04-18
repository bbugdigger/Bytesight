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

// Build the native JVMTI helper via CMake and copy it into src/main/resources/.
// The fat-JAR picks it up automatically as part of its resource include.
// Requires `cmake` on PATH and a JDK pointed to by JAVA_HOME (gradle's toolchain
// JDK is used if the environment variable isn't already set). Split into three
// tasks so each stays compatible with Gradle's configuration cache — nested
// `exec {}` blocks inside `doLast` don't serialise.
val cppSrcDir = layout.projectDirectory.dir("src/main/cpp")
val nativeBuildDir = layout.buildDirectory.dir("native")
val nativeResDir = layout.projectDirectory.dir("src/main/resources/native/win-x64")
val nativeDllName = "bytesight_heap.dll"

val jdkHomeForNative: String = System.getenv("JAVA_HOME")
    ?: javaToolchains.launcherFor(java.toolchain).get().metadata.installationPath.asFile.absolutePath

// Ensure build + resource output dirs exist at config time — doFirst closures
// capture the script class, which breaks the configuration cache.
nativeBuildDir.get().asFile.mkdirs()
nativeResDir.asFile.mkdirs()

val configureNative by tasks.registering(Exec::class) {
    val src = cppSrcDir.asFile
    val buildOut = nativeBuildDir.get().asFile

    inputs.dir(src)
    outputs.file(File(buildOut, "CMakeCache.txt"))

    environment("JAVA_HOME", jdkHomeForNative)
    commandLine(
        "cmake",
        "-S", src.absolutePath,
        "-B", buildOut.absolutePath,
        "-A", "x64",
    )
}

val compileNative by tasks.registering(Exec::class) {
    dependsOn(configureNative)
    val src = cppSrcDir.asFile
    val buildOut = nativeBuildDir.get().asFile

    inputs.dir(src)
    outputs.file(File(buildOut, "Release/$nativeDllName"))

    environment("JAVA_HOME", jdkHomeForNative)
    commandLine("cmake", "--build", buildOut.absolutePath, "--config", "Release")
}

val buildNative by tasks.registering(Copy::class) {
    dependsOn(compileNative)
    val buildOut = nativeBuildDir.get().asFile

    from(File(buildOut, "Release/$nativeDllName"))
    into(nativeResDir.asFile)
}

tasks.named("processResources") { dependsOn(buildNative) }

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
