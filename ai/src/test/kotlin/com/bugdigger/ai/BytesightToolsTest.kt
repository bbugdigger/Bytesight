package com.bugdigger.ai

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@DisplayName("BytesightTools")
class BytesightToolsTest {

    private lateinit var services: BytesightAgentServices
    private lateinit var tools: BytesightTools

    @BeforeEach
    fun setUp() {
        services = mockk(relaxed = true)
        tools = BytesightTools(services)
        every { services.isConnected() } returns true
    }

    @Nested
    @DisplayName("when disconnected")
    inner class WhenDisconnected {

        @BeforeEach
        fun disconnect() {
            every { services.isConnected() } returns false
        }

        @Test
        fun `listClasses returns NOT_CONNECTED message`() = runTest {
            val result = tools.listClasses()
            assertEquals(BytesightTools.NOT_CONNECTED, result)
        }

        @Test
        fun `decompileClass returns NOT_CONNECTED message`() = runTest {
            val result = tools.decompileClass("com.example.Foo")
            assertEquals(BytesightTools.NOT_CONNECTED, result)
        }

        @Test
        fun `getRecentTraces returns NOT_CONNECTED message`() = runTest {
            val result = tools.getRecentTraces()
            assertEquals(BytesightTools.NOT_CONNECTED, result)
        }
    }

    @Nested
    @DisplayName("listClasses")
    inner class ListClasses {

        @Test
        fun `formats result with class name and tags`() = runTest {
            coEvery { services.listClasses(any(), any(), any()) } returns listOf(
                ClassSummary("com.example.Foo"),
                ClassSummary("com.example.Bar", isInterface = true),
                ClassSummary("com.example.Baz", isAbstract = true),
            )

            val result = tools.listClasses(filter = "com.example")

            assertTrue(result.contains("com.example.Foo"))
            assertTrue(result.contains("com.example.Bar"))
            assertTrue(result.contains("[interface]"))
            assertTrue(result.contains("[abstract]"))
        }

        @Test
        fun `returns friendly message when empty`() = runTest {
            coEvery { services.listClasses(any(), any(), any()) } returns emptyList()

            val result = tools.listClasses(filter = "doesNotExist")

            assertEquals("No matching classes.", result)
        }
    }

    @Nested
    @DisplayName("decompileClass")
    inner class DecompileClass {

        @Test
        fun `returns decompiled source on success`() = runTest {
            coEvery { services.decompileClass("com.example.Foo") } returns "class Foo {}"

            val result = tools.decompileClass("com.example.Foo")

            assertEquals("class Foo {}", result)
        }

        @Test
        fun `reports failure when source is blank`() = runTest {
            coEvery { services.decompileClass(any()) } returns ""

            val result = tools.decompileClass("com.example.Missing")

            assertTrue(result.contains("not found") || result.contains("failed"))
        }
    }

    @Nested
    @DisplayName("getClassInfo")
    inner class GetClassInfo {

        @Test
        fun `formats fields and methods`() = runTest {
            coEvery { services.getClassInfo("com.example.Foo") } returns ClassDetail(
                name = "com.example.Foo",
                superName = "java.lang.Object",
                interfaces = listOf("java.lang.Runnable"),
                methods = listOf(
                    MethodSummary("run", "()V"),
                    MethodSummary("<init>", "()V"),
                ),
                fields = listOf(
                    FieldSummary("count", "I"),
                    FieldSummary("INSTANCE", "Lcom/example/Foo;", isStatic = true),
                ),
            )

            val result = tools.getClassInfo("com.example.Foo")

            assertTrue(result.contains("Super: java.lang.Object"))
            assertTrue(result.contains("java.lang.Runnable"))
            assertTrue(result.contains("run()V"))
            assertTrue(result.contains("static INSTANCE"))
        }

        @Test
        fun `reports missing class`() = runTest {
            coEvery { services.getClassInfo(any()) } returns null

            val result = tools.getClassInfo("com.example.Missing")

            assertEquals("Class not found: com.example.Missing", result)
        }
    }

    @Nested
    @DisplayName("searchStrings")
    inner class SearchStrings {

        @Test
        fun `formats matches with class and value`() = runTest {
            coEvery { services.searchStrings("secret", any()) } returns listOf(
                StringMatch("com.example.Auth", "super-secret-key"),
                StringMatch("com.example.Log", "secret rotation"),
            )

            val result = tools.searchStrings("secret")

            assertTrue(result.contains("com.example.Auth"))
            assertTrue(result.contains("super-secret-key"))
        }

        @Test
        fun `rejects blank query`() = runTest {
            val result = tools.searchStrings("")
            assertEquals("Query must not be blank.", result)
        }
    }

    @Nested
    @DisplayName("renameSymbol")
    inner class RenameSymbolTool {

        @Test
        fun `delegates to services and confirms`() = runTest {
            val result = tools.renameSymbol("com.example.a.b", "decrypt")

            verify { services.renameSymbol("com.example.a.b", "decrypt") }
            assertTrue(result.contains("com.example.a.b"))
            assertTrue(result.contains("decrypt"))
        }

        @Test
        fun `rejects blank new name`() = runTest {
            val result = tools.renameSymbol("com.example.a.b", "")
            assertTrue(result.contains("must not be blank"))
            verify(exactly = 0) { services.renameSymbol(any(), any()) }
        }
    }

    @Nested
    @DisplayName("getRenames")
    inner class GetRenamesTool {

        @Test
        fun `lists current rename map`() = runTest {
            every { services.getRenames() } returns mapOf(
                "com.example.a" to "AuthService",
                "com.example.b" to "Decoder",
            )

            val result = tools.getRenames()

            assertTrue(result.contains("com.example.a → AuthService"))
            assertTrue(result.contains("com.example.b → Decoder"))
        }

        @Test
        fun `reports empty map`() = runTest {
            every { services.getRenames() } returns emptyMap()

            val result = tools.getRenames()

            assertEquals("No renames yet.", result)
        }
    }

    @Nested
    @DisplayName("getHeapHistogram")
    inner class GetHeapHistogramTool {

        @Test
        fun `formats histogram rows`() = runTest {
            coEvery { services.getHeapHistogram(any(), any()) } returns listOf(
                HeapHistogramRow("byte[]", 1234, 987_654),
                HeapHistogramRow("java.lang.String", 500, 32_000),
            )

            val result = tools.getHeapHistogram()

            assertTrue(result.contains("byte[]"))
            assertTrue(result.contains("1234 instances"))
            assertTrue(result.contains("987654 bytes"))
        }

        @Test
        fun `prompts user to capture snapshot when empty`() = runTest {
            coEvery { services.getHeapHistogram(any(), any()) } returns emptyList()

            val result = tools.getHeapHistogram()

            assertTrue(result.contains("Capture a heap snapshot"))
        }
    }

    @Nested
    @DisplayName("getRecentTraces")
    inner class GetRecentTracesTool {

        @Test
        fun `formats trace events with indentation by depth`() = runTest {
            coEvery { services.getRecentTraces(any()) } returns listOf(
                TraceSummary("com.example.Foo", "bar", "ENTRY", depth = 0, arguments = "x=1"),
                TraceSummary("com.example.Foo", "baz", "ENTRY", depth = 1),
                TraceSummary("com.example.Foo", "baz", "EXIT", depth = 1, returnValue = "42", durationNanos = 2_000_000L),
            )

            val result = tools.getRecentTraces()

            assertTrue(result.contains("[ENTRY] com.example.Foo#bar"))
            assertTrue(result.contains("args=(x=1)"))
            assertTrue(result.contains("ret=42"))
            assertTrue(result.contains("µs"))
        }

        @Test
        fun `prompts to add hooks when empty`() = runTest {
            coEvery { services.getRecentTraces(any()) } returns emptyList()

            val result = tools.getRecentTraces()

            assertTrue(result.contains("Add hooks"))
        }
    }
}
