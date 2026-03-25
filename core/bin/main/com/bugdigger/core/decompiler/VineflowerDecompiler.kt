package com.bugdigger.core.decompiler

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.java.decompiler.main.Fernflower
import org.jetbrains.java.decompiler.main.extern.IContextSource
import org.jetbrains.java.decompiler.main.extern.IFernflowerLogger
import org.jetbrains.java.decompiler.main.extern.IResultSaver
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.Manifest

/**
 * Decompiler implementation using Vineflower (formerly Quiltflower/Fernflower).
 *
 * Vineflower is a modern fork of Fernflower with improved output quality,
 * better pattern matching support, and enhanced readability.
 */
class VineflowerDecompiler(
    private val options: DecompilerOptions = DecompilerOptions()
) : Decompiler {

    private val logger = LoggerFactory.getLogger(VineflowerDecompiler::class.java)

    override suspend fun decompile(className: String, bytecode: ByteArray): DecompilationResult {
        return decompileAll(mapOf(className to bytecode))[className]
            ?: DecompilationResult.Failure("No result returned for class: $className")
    }

    override suspend fun decompileAll(classes: Map<String, ByteArray>): Map<String, DecompilationResult> {
        if (classes.isEmpty()) {
            return emptyMap()
        }

        return withContext(Dispatchers.IO) {
            try {
                decompileInternal(classes)
            } catch (e: Exception) {
                logger.error("Decompilation failed", e)
                classes.keys.associateWith {
                    DecompilationResult.Failure("Decompilation failed: ${e.message}", e)
                }
            }
        }
    }

    private fun decompileInternal(classes: Map<String, ByteArray>): Map<String, DecompilationResult> {
        val results = ConcurrentHashMap<String, DecompilationResult>()
        val warnings = ConcurrentHashMap<String, MutableList<String>>()

        val resultSaver = InMemoryResultSaver(results, warnings)
        val fernflowerLogger = VineflowerLogger(warnings)

        // Use the new 3-argument constructor (without deprecated IBytecodeProvider)
        val fernflower = Fernflower(
            resultSaver,
            options.toFernflowerOptions(),
            fernflowerLogger
        )

        try {
            // Add all classes via IContextSource with output sink
            val contextSource = InMemoryContextSource(classes, results, warnings)
            fernflower.addSource(contextSource)

            // Decompile all added sources
            fernflower.decompileContext()
        } finally {
            fernflower.clearContext()
        }

        // Ensure all classes have a result (even if empty)
        for (className in classes.keys) {
            if (!results.containsKey(className)) {
                results[className] = DecompilationResult.Failure(
                    "No decompilation output produced for class: $className"
                )
            }
        }

        return results
    }

    /**
     * Provides bytecode from memory using the modern IContextSource interface.
     */
    private class InMemoryContextSource(
        private val classes: Map<String, ByteArray>,
        private val results: ConcurrentHashMap<String, DecompilationResult>,
        private val warnings: ConcurrentHashMap<String, MutableList<String>>
    ) : IContextSource {

        override fun getName(): String = "InMemorySource"

        override fun getEntries(): IContextSource.Entries {
            val classEntries = classes.keys.map { className ->
                val path = className.replace('.', '/') // no .class suffix needed
                IContextSource.Entry.atBase(path)
            }
            return IContextSource.Entries(classEntries, emptyList(), emptyList())
        }

        override fun getInputStream(resource: String): InputStream? {
            // Resource comes in as "com/example/MyClass.class"
            val className = resource
                .removeSuffix(".class")
                .replace('/', '.')

            val bytecode = classes[className] ?: return null
            return ByteArrayInputStream(bytecode)
        }

        override fun getClassBytes(className: String): ByteArray? {
            // className comes as "com/example/MyClass" (no .class suffix)
            val dotName = className.replace('/', '.')
            return classes[dotName]
        }

        override fun createOutputSink(saver: IResultSaver): IContextSource.IOutputSink {
            return InMemoryOutputSink(results, warnings)
        }
    }

    /**
     * Output sink that writes decompilation results to memory.
     */
    private class InMemoryOutputSink(
        private val results: ConcurrentHashMap<String, DecompilationResult>,
        private val warnings: ConcurrentHashMap<String, MutableList<String>>
    ) : IContextSource.IOutputSink {

        override fun begin() {
            // No-op
        }

        override fun acceptClass(
            qualifiedName: String,
            fileName: String,
            content: String,
            mapping: IntArray?
        ) {
            val className = qualifiedName.replace('/', '.')
            val classWarnings = warnings[className] ?: emptyList()
            results[className] = DecompilationResult.Success(content, classWarnings.toList())
        }

        override fun acceptDirectory(directory: String) {
            // No-op
        }

        override fun acceptOther(path: String) {
            // No-op
        }

        override fun close() {
            // No-op
        }
    }

    /**
     * Saves decompilation results to memory.
     */
    private class InMemoryResultSaver(
        private val results: ConcurrentHashMap<String, DecompilationResult>,
        private val warnings: ConcurrentHashMap<String, MutableList<String>>
    ) : IResultSaver {

        override fun saveFolder(path: String) {
            // No-op for in-memory storage
        }

        override fun copyFile(source: String, path: String, entryName: String) {
            // No-op for in-memory storage
        }

        override fun saveClassFile(
            path: String,
            qualifiedName: String,
            entryName: String,
            content: String,
            mapping: IntArray?
        ) {
            val className = qualifiedName.replace('/', '.')
            val classWarnings = warnings[className] ?: emptyList()
            results[className] = DecompilationResult.Success(content, classWarnings.toList())
        }

        override fun createArchive(path: String, archiveName: String, manifest: Manifest?) {
            // No-op for in-memory storage
        }

        override fun saveDirEntry(path: String, archiveName: String, entryName: String) {
            // No-op for in-memory storage
        }

        override fun copyEntry(source: String, path: String, archiveName: String, entry: String) {
            // No-op for in-memory storage
        }

        override fun saveClassEntry(
            path: String,
            archiveName: String,
            qualifiedName: String,
            entryName: String,
            content: String
        ) {
            val className = qualifiedName.replace('/', '.')
            val classWarnings = warnings[className] ?: emptyList()
            results[className] = DecompilationResult.Success(content, classWarnings.toList())
        }

        override fun closeArchive(path: String, archiveName: String) {
            // No-op for in-memory storage
        }
    }

    /**
     * Custom logger that captures warnings per class.
     */
    private class VineflowerLogger(
        private val warnings: ConcurrentHashMap<String, MutableList<String>>
    ) : IFernflowerLogger() {

        private val logger = LoggerFactory.getLogger(VineflowerLogger::class.java)
        private var currentClass: String? = null

        override fun writeMessage(message: String, severity: Severity) {
            when (severity) {
                Severity.TRACE -> logger.trace(message)
                Severity.INFO -> logger.debug(message)
                Severity.WARN -> {
                    logger.warn(message)
                    currentClass?.let { className ->
                        warnings.getOrPut(className) { mutableListOf() }.add(message)
                    }
                }
                Severity.ERROR -> logger.error(message)
            }
        }

        override fun writeMessage(message: String, severity: Severity, t: Throwable) {
            when (severity) {
                Severity.TRACE -> logger.trace(message, t)
                Severity.INFO -> logger.debug(message, t)
                Severity.WARN -> {
                    logger.warn(message, t)
                    currentClass?.let { className ->
                        warnings.getOrPut(className) { mutableListOf() }.add("$message: ${t.message}")
                    }
                }
                Severity.ERROR -> logger.error(message, t)
            }
        }

        override fun startReadingClass(className: String) {
            currentClass = className.replace('/', '.')
            logger.debug("Reading class: {}", currentClass)
        }

        override fun endReadingClass() {
            logger.debug("Finished reading class: {}", currentClass)
            currentClass = null
        }

        override fun startClass(className: String) {
            currentClass = className.replace('/', '.')
            logger.debug("Decompiling class: {}", currentClass)
        }

        override fun endClass() {
            logger.debug("Finished decompiling class: {}", currentClass)
            currentClass = null
        }

        override fun startMethod(methodName: String) {
            logger.trace("Decompiling method: {}", methodName)
        }

        override fun endMethod() {
            logger.trace("Finished decompiling method")
        }

        override fun startWriteClass(className: String) {
            logger.debug("Writing class: {}", className)
        }

        override fun endWriteClass() {
            logger.debug("Finished writing class")
        }
    }
}

/**
 * Configuration options for VineflowerDecompiler.
 */
data class DecompilerOptions(
    /** Remove synthetic class members */
    val removeSyntheticMembers: Boolean = true,
    /** Decompile generic signatures */
    val decompileGenerics: Boolean = true,
    /** Remove empty try-catch blocks */
    val removeEmptyTryCatch: Boolean = true,
    /** Decompile lambda expressions */
    val decompileLambdas: Boolean = true,
    /** Show bytecode source as comments */
    val bytecodeSourceMapping: Boolean = false,
    /** Use record patterns (Java 16+) */
    val useRecordPatterns: Boolean = true,
    /** Indent string (spaces or tab) */
    val indentString: String = "    ",
    /** Include line numbers as comments */
    val includeLineNumbers: Boolean = false,
    /** Decompile inner classes */
    val decompileInnerClasses: Boolean = true,
    /** ASCII string characters only */
    val asciiStrings: Boolean = false
) {
    /**
     * Converts options to Fernflower's expected format.
     */
    fun toFernflowerOptions(): Map<String, Any> = buildMap {
        // Basic options
        put("rbr", if (removeSyntheticMembers) "1" else "0")  // Remove bridge methods
        put("rsy", if (removeSyntheticMembers) "1" else "0")  // Remove synthetic members
        put("dgs", if (decompileGenerics) "1" else "0")       // Decompile generic signatures
        put("din", if (decompileInnerClasses) "1" else "0")   // Decompile inner classes
        put("das", if (decompileLambdas) "1" else "0")        // Decompile assertions
        put("den", "1")                                        // Decompile enumerations
        put("uto", "1")                                        // Use toString for enums
        put("udv", "1")                                        // Use debug variable names
        put("rer", "1")                                        // Remove empty exception ranges
        put("fdi", "1")                                        // Decompile finally
        put("inn", "1")                                        // Check non-null assertions
        put("lac", "1")                                        // Decompile lambdas as anonymous classes
        put("bsm", if (bytecodeSourceMapping) "1" else "0")   // Bytecode source mapping
        put("nls", "1")                                        // New line separator
        put("ind", indentString)                               // Indentation string
        put("log", IFernflowerLogger.Severity.WARN.name)       // Log level
        put("mpm", "60")                                       // Max processing time per method (seconds)
        put("ren", "0")                                        // Rename ambiguous classes
        put("urc", if (useRecordPatterns) "1" else "0")       // Use record patterns
        put("asc", if (asciiStrings) "1" else "0")            // ASCII strings only
        put("lit", if (includeLineNumbers) "1" else "0")      // Include line number table
    }
}
