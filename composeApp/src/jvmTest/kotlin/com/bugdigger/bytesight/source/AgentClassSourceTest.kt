package com.bugdigger.bytesight.source

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.protocol.BytecodeResponse
import com.bugdigger.protocol.ClassInfo
import com.google.protobuf.ByteString
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentClassSourceTest {

    private lateinit var agentClient: AgentClient
    private lateinit var source: AgentClassSource

    @BeforeEach
    fun setup() {
        agentClient = mockk()
        source = AgentClassSource(agentClient = agentClient, connectionKey = "localhost:50051")
    }

    @Nested
    @DisplayName("Capabilities and display name")
    inner class CapsAndName {
        @Test
        fun `declares all live capabilities`() {
            assertEquals(Capability.ALL, source.capabilities)
        }

        @Test
        fun `display name includes connection key`() {
            assertTrue(source.displayName.contains("localhost:50051"))
        }
    }

    @Nested
    @DisplayName("listClasses")
    inner class ListClasses {
        @Test
        fun `delegates to agentClient with includeSystemClasses=false by default`() = runTest {
            val klass = ClassInfo.newBuilder().setName("a.B").build()
            coEvery {
                agentClient.listClasses("localhost:50051", "", false)
            } returns Result.success(listOf(klass))

            val result = source.listClasses()

            assertTrue(result.isSuccess)
            assertEquals(listOf(klass), result.getOrNull())
        }

        @Test
        fun `passes includeSystemClasses=true through`() = runTest {
            coEvery {
                agentClient.listClasses("localhost:50051", "", true)
            } returns Result.success(emptyList())

            val result = source.listClasses(includeSystemClasses = true)

            assertTrue(result.isSuccess)
        }

        @Test
        fun `propagates failure from agentClient`() = runTest {
            coEvery {
                agentClient.listClasses(any(), any(), any())
            } returns Result.failure(IllegalStateException("not connected"))

            val result = source.listClasses()

            assertTrue(result.isFailure)
        }
    }

    @Nested
    @DisplayName("getBytecode")
    inner class GetBytecode {
        @Test
        fun `returns the bytes from the response`() = runTest {
            val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
            val response = BytecodeResponse.newBuilder()
                .setClassName("a.B")
                .setBytecode(ByteString.copyFrom(bytes))
                .setFound(true)
                .build()
            coEvery {
                agentClient.getClassBytecode("localhost:50051", "a.B")
            } returns Result.success(response)

            val result = source.getBytecode("a.B")

            assertTrue(result.isSuccess)
            assertEquals(bytes.toList(), result.getOrNull()!!.toList())
        }

        @Test
        fun `returns failure when found=false`() = runTest {
            val response = BytecodeResponse.newBuilder()
                .setFound(false)
                .setError("missing")
                .build()
            coEvery {
                agentClient.getClassBytecode(any(), any())
            } returns Result.success(response)

            val result = source.getBytecode("a.Missing")

            assertTrue(result.isFailure)
        }

        @Test
        fun `propagates rpc failure`() = runTest {
            coEvery {
                agentClient.getClassBytecode(any(), any())
            } returns Result.failure(IllegalStateException("not connected"))

            val result = source.getBytecode("a.B")

            assertTrue(result.isFailure)
        }
    }
}
