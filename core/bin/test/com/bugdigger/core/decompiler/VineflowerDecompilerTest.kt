package com.bugdigger.core.decompiler

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import javax.tools.ToolProvider

class VineflowerDecompilerTest {

    @Test
    fun `decompile simple class returns valid Java source`() = runBlocking {
        // Arrange
        val bytecode = compileSimpleClass()
        val decompiler = VineflowerDecompiler()

        // Act
        val result = decompiler.decompile("TestClass", bytecode)

        // Assert
        assertTrue(result is DecompilationResult.Success, "Expected successful decompilation")
        val success = result as DecompilationResult.Success
        assertTrue(success.sourceCode.contains("class TestClass"), "Source should contain class declaration")
        assertTrue(success.sourceCode.contains("hello"), "Source should contain method name")
    }

    @Test
    fun `decompile empty map returns empty result`() = runBlocking {
        // Arrange
        val decompiler = VineflowerDecompiler()

        // Act
        val result = decompiler.decompileAll(emptyMap())

        // Assert
        assertTrue(result.isEmpty())
    }

    @Test
    fun `decompile with custom options applies indentation`() = runBlocking {
        // Arrange
        val bytecode = compileSimpleClass()
        val options = DecompilerOptions(indentString = "\t")
        val decompiler = VineflowerDecompiler(options)

        // Act
        val result = decompiler.decompile("TestClass", bytecode)

        // Assert
        assertTrue(result is DecompilationResult.Success)
        val success = result as DecompilationResult.Success
        // Tab indentation should be present
        assertTrue(success.sourceCode.contains("\t"), "Source should use tab indentation")
    }

    @Test
    fun `decompile multiple classes preserves all results`() = runBlocking {
        // Arrange
        val bytecode1 = compileSimpleClass("TestClass1", "method1")
        val bytecode2 = compileSimpleClass("TestClass2", "method2")
        val decompiler = VineflowerDecompiler()
        val classes = mapOf(
            "TestClass1" to bytecode1,
            "TestClass2" to bytecode2
        )

        // Act
        val results = decompiler.decompileAll(classes)

        // Assert
        assertEquals(2, results.size, "Should have two results")
        assertTrue(results["TestClass1"] is DecompilationResult.Success)
        assertTrue(results["TestClass2"] is DecompilationResult.Success)

        val source1 = (results["TestClass1"] as DecompilationResult.Success).sourceCode
        val source2 = (results["TestClass2"] as DecompilationResult.Success).sourceCode

        assertTrue(source1.contains("TestClass1"))
        assertTrue(source1.contains("method1"))
        assertTrue(source2.contains("TestClass2"))
        assertTrue(source2.contains("method2"))
    }

    @Test
    fun `decompile invalid bytecode returns failure`() = runBlocking {
        // Arrange
        val invalidBytecode = byteArrayOf(0x00, 0x01, 0x02, 0x03)
        val decompiler = VineflowerDecompiler()

        // Act
        val result = decompiler.decompile("InvalidClass", invalidBytecode)

        // Assert
        assertTrue(result is DecompilationResult.Failure, "Should fail for invalid bytecode")
    }

    @Test
    fun `decompile class with generics produces valid output`() = runBlocking {
        // Arrange
        val bytecode = compileClassWithGenerics()
        val decompiler = VineflowerDecompiler(DecompilerOptions(decompileGenerics = true))

        // Act
        val result = decompiler.decompile("GenericClass", bytecode)

        // Assert
        assertTrue(result is DecompilationResult.Success)
        val source = (result as DecompilationResult.Success).sourceCode
        assertTrue(source.contains("GenericClass"), "Should contain class name")
        // Generic type parameter should be preserved
        assertTrue(source.contains("<T>") || source.contains("<T "), "Should contain generic type parameter")
    }

    @TempDir
    lateinit var tempDir: Path

    /**
     * Compiles a simple Java class to bytecode at runtime.
     */
    private fun compileSimpleClass(
        className: String = "TestClass",
        methodName: String = "hello"
    ): ByteArray {
        val sourceCode = """
            public class $className {
                public String $methodName() {
                    return "Hello, World!";
                }
            }
        """.trimIndent()

        return compileJavaSource(className, sourceCode)
    }

    /**
     * Compiles a class with generics.
     */
    private fun compileClassWithGenerics(): ByteArray {
        val sourceCode = """
            import java.util.List;
            import java.util.ArrayList;
            
            public class GenericClass<T> {
                private List<T> items = new ArrayList<>();
                
                public void add(T item) {
                    items.add(item);
                }
                
                public T get(int index) {
                    return items.get(index);
                }
            }
        """.trimIndent()

        return compileJavaSource("GenericClass", sourceCode)
    }

    /**
     * Compiles Java source code to bytecode using the system compiler.
     */
    private fun compileJavaSource(className: String, sourceCode: String): ByteArray {
        val compiler = ToolProvider.getSystemJavaCompiler()
            ?: throw IllegalStateException("Java compiler not available. Run tests with JDK, not JRE.")

        val sourceFile = tempDir.resolve("$className.java").toFile()
        sourceFile.writeText(sourceCode)

        val result = compiler.run(null, null, null, sourceFile.absolutePath)
        if (result != 0) {
            throw IllegalStateException("Compilation failed for $className")
        }

        val classFile = tempDir.resolve("$className.class").toFile()
        return classFile.readBytes()
    }
}
