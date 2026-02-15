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

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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