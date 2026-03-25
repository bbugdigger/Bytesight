plugins {
    id("java")
    id("application")
}

group = "com.bugdigger"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

application {
    mainClass.set("com.bugdigger.sample.SampleApplication")
}

repositories {
    mavenCentral()
}

// ProGuard configuration for obfuscation
val proguard: Configuration by configurations.creating

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // ProGuard for obfuscation
    proguard("com.guardsquare:proguard-base:7.6.1")
}

tasks.test {
    useJUnitPlatform()
}

// Add Main-Class to standard jar
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.bugdigger.sample.SampleApplication"
    }
}

// Create a fat JAR for easy distribution
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Creates a fat JAR with all dependencies"
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)

    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.name.endsWith("jar") }
            .map { zipTree(it) }
    })

    manifest {
        attributes["Main-Class"] = "com.bugdigger.sample.SampleApplication"
    }
}

// =============================================================================
// ProGuard Obfuscation Tasks
// =============================================================================

val obfuscatedDir: Provider<Directory> = layout.buildDirectory.dir("obfuscated")

// Task to run ProGuard obfuscation
val obfuscateTask = tasks.register<JavaExec>("obfuscate") {
    group = "build"
    description = "Creates an obfuscated JAR using ProGuard"

    dependsOn(tasks.jar)

    val inputJar = tasks.jar.flatMap { it.archiveFile }
    val outputJar = obfuscatedDir.map { it.file("sample-obfuscated.jar") }
    val mappingFile = obfuscatedDir.map { it.file("mapping.txt") }
    val configFile = obfuscatedDir.map { it.file("proguard.pro") }

    inputs.file(inputJar)
    outputs.file(outputJar)
    outputs.file(mappingFile)

    mainClass.set("proguard.ProGuard")
    classpath = proguard

    argumentProviders.add(CommandLineArgumentProvider {
        listOf("@${configFile.get().asFile.absolutePath}")
    })

    doFirst {
        obfuscatedDir.get().asFile.mkdirs()

        val javaHome = System.getProperty("java.home")
        val inputPath = inputJar.get().asFile.absolutePath.replace("\\", "/")
        val outputPath = outputJar.get().asFile.absolutePath.replace("\\", "/")
        val mappingPath = mappingFile.get().asFile.absolutePath.replace("\\", "/")
        val javaBaseMod = "$javaHome/jmods/java.base.jmod".replace("\\", "/")
        val javaLoggingMod = "$javaHome/jmods/java.logging.jmod".replace("\\", "/")

        // Write ProGuard configuration file
        val config = """
            # ProGuard configuration for Sample Application
            
            # Input/Output
            -injars "$inputPath"
            -outjars "$outputPath"
            
            # JDK libraries
            -libraryjars "$javaBaseMod"(!**.jar;!module-info.class)
            -libraryjars "$javaLoggingMod"(!**.jar;!module-info.class)
            
            # Output mapping file for debugging/analysis
            -printmapping "$mappingPath"
            
            # Keep the main class entry point
            -keep public class com.bugdigger.sample.SampleApplication {
                public static void main(java.lang.String[]);
            }
            
            # Obfuscation settings - aggressive but still functional
            -overloadaggressively
            -flattenpackagehierarchy "o"
            -repackageclasses "o"
            -allowaccessmodification
            
            # Optimization settings
            -optimizationpasses 3
            
            # Don't generate warnings for missing classes
            -dontwarn java.**
            -dontwarn javax.**
            -dontwarn sun.**
            
            # Don't note about duplicate class definitions
            -dontnote **
            
            # Keep some attributes for stack traces (optional - remove for more obfuscation)
            -keepattributes SourceFile,LineNumberTable
            
            # Adapt string constants that reference class names
            -adaptclassstrings
        """.trimIndent()

        configFile.get().asFile.writeText(config)
    }
}

// Task to run the obfuscated JAR
tasks.register<JavaExec>("runObfuscated") {
    group = "application"
    description = "Runs the obfuscated sample application"

    dependsOn(obfuscateTask)

    val outputJar = obfuscatedDir.map { it.file("sample-obfuscated.jar") }
    classpath = files(outputJar)
    mainClass.set("com.bugdigger.sample.SampleApplication")

    // Enable for debugging
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}