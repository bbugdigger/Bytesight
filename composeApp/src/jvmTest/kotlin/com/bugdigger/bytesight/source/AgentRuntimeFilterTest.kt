package com.bugdigger.bytesight.source

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentRuntimeFilterTest {

    @Test
    fun `flags our agent and protocol classes`() {
        assertTrue(AgentRuntimeFilter.isAgentRuntime("com.bugdigger.agent.BytesightAgent"))
        assertTrue(AgentRuntimeFilter.isAgentRuntime("com.bugdigger.agent.server.BytesightAgentService"))
        assertTrue(AgentRuntimeFilter.isAgentRuntime("com.bugdigger.protocol.ClassInfo"))
    }

    @Test
    fun `flags grpc and shaded netty`() {
        assertTrue(AgentRuntimeFilter.isAgentRuntime("io.grpc.MethodDescriptor"))
        assertTrue(AgentRuntimeFilter.isAgentRuntime("io.grpc.netty.shaded.io.netty.channel.Channel"))
        assertTrue(AgentRuntimeFilter.isAgentRuntime("io.perfmark.PerfMark"))
    }

    @Test
    fun `flags instrumentation libraries`() {
        assertTrue(AgentRuntimeFilter.isAgentRuntime("net.bytebuddy.ByteBuddy"))
        assertTrue(AgentRuntimeFilter.isAgentRuntime("org.objectweb.asm.ClassReader"))
    }

    @Test
    fun `flags borderline runtime packages`() {
        assertTrue(AgentRuntimeFilter.isAgentRuntime("com.google.protobuf.GeneratedMessage"))
        assertTrue(AgentRuntimeFilter.isAgentRuntime("org.slf4j.Logger"))
        assertTrue(AgentRuntimeFilter.isAgentRuntime("ch.qos.logback.classic.Logger"))
    }

    @Test
    fun `leaves target classes alone`() {
        assertFalse(AgentRuntimeFilter.isAgentRuntime("com.example.MyApp"))
        assertFalse(AgentRuntimeFilter.isAgentRuntime("org.springframework.boot.SpringApplication"))
        assertFalse(AgentRuntimeFilter.isAgentRuntime("java.lang.String"))
    }

    @Test
    fun `does not catch sibling-prefix collisions`() {
        // Adversarial cases — packages that LOOK like they share a prefix
        // but live in their own namespace. We must not eat these.
        assertFalse(AgentRuntimeFilter.isAgentRuntime("com.bugdigger.agentless.Foo"))
        assertFalse(AgentRuntimeFilter.isAgentRuntime("io.grpcfoo.Bar"))
        assertFalse(AgentRuntimeFilter.isAgentRuntime("org.slf4jstore.Baz"))
    }

    @Test
    fun `flags org_jcp JDK internal namespace`() {
        assertTrue(AgentRuntimeFilter.isAgentRuntime("org.jcp.xml.dsig.internal.dom.DOMReference"))
    }

    @Test
    fun `flags lambda classes - legacy naming JDK 8 to 15`() {
        assertTrue(AgentRuntimeFilter.isRuntimeGenerated("com.example.MyApp\$\$Lambda\$0"))
        assertTrue(AgentRuntimeFilter.isRuntimeGenerated("com.example.MyApp\$\$Lambda\$42"))
    }

    @Test
    fun `flags lambda classes - hidden-class naming JDK 16 plus`() {
        assertTrue(AgentRuntimeFilter.isRuntimeGenerated("com.example.MyApp\$\$Lambda/0x0000000800c00800"))
    }

    @Test
    fun `flags lambda classes nested inside inner classes`() {
        assertTrue(AgentRuntimeFilter.isRuntimeGenerated("com.example.Outer\$Inner\$\$Lambda\$5"))
    }

    @Test
    fun `does not flag anonymous inner classes`() {
        // Real on-disk bytecode, NOT synthesized lambdas.
        assertFalse(AgentRuntimeFilter.isRuntimeGenerated("com.example.MyApp\$1"))
        assertFalse(AgentRuntimeFilter.isRuntimeGenerated("com.example.Outer\$Inner"))
    }

    @Test
    fun `shouldHide combines both predicates`() {
        // Agent runtime
        assertTrue(AgentRuntimeFilter.shouldHide("com.bugdigger.agent.Foo"))
        assertTrue(AgentRuntimeFilter.shouldHide("io.grpc.Channel"))
        // Runtime-generated
        assertTrue(AgentRuntimeFilter.shouldHide("com.example.MyApp\$\$Lambda\$0"))
        // Target classes pass through
        assertFalse(AgentRuntimeFilter.shouldHide("com.example.MyApp"))
        assertFalse(AgentRuntimeFilter.shouldHide("com.example.MyApp\$Inner"))
    }
}
