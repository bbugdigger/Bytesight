package com.bugdigger.core.decompiler

/**
 * Interface for bytecode decompilers.
 * Implementations can use different decompilation engines (Vineflower, CFR, Procyon, etc.)
 */
interface Decompiler {
    /**
     * Decompiles a single class from its bytecode.
     *
     * @param className Fully qualified class name (e.g., "com.example.MyClass")
     * @param bytecode The raw class bytecode
     * @return Decompiled Java source code
     * @throws DecompilationException if decompilation fails
     */
    suspend fun decompile(className: String, bytecode: ByteArray): DecompilationResult

    /**
     * Decompiles multiple classes that may have interdependencies.
     * This allows the decompiler to resolve cross-references for better output.
     *
     * @param classes Map of class name to bytecode
     * @return Map of class name to decompiled source
     */
    suspend fun decompileAll(classes: Map<String, ByteArray>): Map<String, DecompilationResult>
}

/**
 * Result of a decompilation operation.
 */
sealed class DecompilationResult {
    /**
     * Successful decompilation.
     */
    data class Success(
        val sourceCode: String,
        val warnings: List<String> = emptyList()
    ) : DecompilationResult()

    /**
     * Failed decompilation.
     */
    data class Failure(
        val error: String,
        val exception: Throwable? = null
    ) : DecompilationResult()
}

/**
 * Exception thrown when decompilation fails.
 */
class DecompilationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
