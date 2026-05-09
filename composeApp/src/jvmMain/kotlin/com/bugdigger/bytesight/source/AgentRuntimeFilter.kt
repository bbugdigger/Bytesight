package com.bugdigger.bytesight.source

/**
 * Classes that should be hidden from the UI when listing what's loaded in a
 * live target JVM. Two distinct categories, both unwanted noise:
 *
 *   1. **Bytesight agent runtime** — classes the agent's fat JAR pulls into
 *      the target the moment we attach (gRPC, bytebuddy, ASM, our own code,
 *      logging stack, etc.). Without filtering, the Class browser is a sea
 *      of `io.grpc.*` + `net.bytebuddy.*` on top of the user's actual code.
 *
 *   2. **Runtime-generated classes with no on-disk bytecode** — most
 *      importantly Java lambdas, which the JVM synthesizes on the fly as
 *      hidden classes (named `OuterClass$$Lambda$NN` or, on JDK 17+,
 *      `OuterClass$$Lambda/0xNNN`). The agent reports them via
 *      `getAllLoadedClasses()`, but `getClassBytecode` always fails because
 *      these classes are never written to disk and bypass our
 *      `ClassFileTransformer` hook. Surfacing them just gives the user a
 *      list of clickable items that all error on click.
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
        // JDK internal namespace not caught by the agent's
        // `includeSystemClasses=false` filter (which only excludes
        // java.*/javax.*/sun.*). org.jcp.* is JDK reference impl
        // (XML signatures, etc.) — pure infrastructure.
        "org.jcp",
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

    /**
     * `true` when [className] is a JVM-synthesized class that has no
     * on-disk bytecode our agent could fetch. Currently covers Java
     * lambda classes in both naming schemes:
     *
     *   - `OuterClass$$Lambda$NN`    (JDK 8 — 15)
     *   - `OuterClass$$Lambda/0x...` (JDK 16+, hidden classes)
     *
     * Anonymous inner classes (`Outer$1`, `Outer$2`) are NOT caught —
     * those have real on-disk bytecode and are worth showing.
     */
    fun isRuntimeGenerated(className: String): Boolean =
        className.contains("\$\$Lambda")

    /** Combined predicate: hide if either category applies. */
    fun shouldHide(className: String): Boolean =
        isAgentRuntime(className) || isRuntimeGenerated(className)
}
