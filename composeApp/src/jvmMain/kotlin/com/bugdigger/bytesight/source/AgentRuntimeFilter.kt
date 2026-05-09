package com.bugdigger.bytesight.source

/**
 * Packages shipped inside the Bytesight agent's fat JAR. When the agent is
 * injected into a target JVM via the Attach API, classes from these
 * packages are loaded into the target alongside the application's own
 * classes — without filtering, the user sees a flood of grpc/bytebuddy/asm
 * noise on top of the actual target classes they want to reverse-engineer.
 *
 * The "borderline" packages (protobuf, slf4j, logback) are included
 * because the typical reverse-engineering workflow does not benefit from
 * seeing logging-framework internals, even when the target app legitimately
 * ships its own copy. The trade-off: a target's `ch.qos.logback.*` classes
 * are hidden too. If a concrete use case needs them, add a Settings toggle
 * later — the filter is a static list, easy to override.
 *
 * Static sources (JAR / APK / .bts) deliberately do NOT apply this filter:
 * those files contain only target-app classes, so the filter would be a
 * no-op at best, and at worst would mis-filter apps whose own packages
 * collide with a Bytesight-runtime prefix.
 */
object AgentRuntimeFilter {

    val EXCLUDED_PREFIXES: List<String> = listOf(
        // Our own code
        "com.bugdigger.agent",
        "com.bugdigger.protocol",
        // gRPC core + shaded Netty (grpc-netty-shaded re-roots netty under io.grpc.netty.shaded)
        "io.grpc",
        "io.perfmark",
        // Instrumentation runtime
        "net.bytebuddy",
        "org.objectweb.asm",
        // Protobuf runtime
        "com.google.protobuf",
        // Logging stack (agent uses slf4j + logback for its own diagnostics)
        "org.slf4j",
        "ch.qos.logback",
    )

    /**
     * `true` when [className] belongs to the Bytesight agent's runtime
     * footprint and should be hidden from the UI.
     *
     * Uses dot-bounded prefix matching so adversarial sibling packages
     * (e.g. `com.bugdigger.agentless`, `io.grpcfoo`) are *not* caught.
     */
    fun isAgentRuntime(className: String): Boolean =
        EXCLUDED_PREFIXES.any { prefix ->
            className == prefix || className.startsWith("$prefix.")
        }
}
