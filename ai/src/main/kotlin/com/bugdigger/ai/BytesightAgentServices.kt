package com.bugdigger.ai

/**
 * Abstraction over the data/actions the AI agent needs from the rest of Bytesight.
 * The `ai` module cannot depend on `composeApp`, so the host module implements this
 * interface and injects it when constructing the agent.
 *
 * All methods are suspend to allow non-blocking calls into the agent over gRPC,
 * bytecode analysis on a background dispatcher, etc.
 */
interface BytesightAgentServices {

    /** Is a target JVM currently attached? When false, every tool returns an empty result. */
    fun isConnected(): Boolean

    /**
     * List classes loaded in the target JVM.
     * @param filter case-insensitive substring match against the fully-qualified class name
     * @param includeSystem include JDK / system classes
     * @param limit cap on the number of classes returned (the target may have tens of thousands)
     */
    suspend fun listClasses(filter: String = "", includeSystem: Boolean = false, limit: Int = 200): List<ClassSummary>

    /** Get the decompiled Java source for a single class. */
    suspend fun decompileClass(className: String): String

    /** Inspect a single class: super, interfaces, methods, fields. */
    suspend fun getClassInfo(className: String): ClassDetail?

    /**
     * Search extracted string constants across all loaded classes (or a subset).
     * @param query case-insensitive substring match
     */
    suspend fun searchStrings(query: String, limit: Int = 100): List<StringMatch>

    /** Most recent method-trace events, newest last. Empty if no hooks are active. */
    suspend fun getRecentTraces(limit: Int = 100): List<TraceSummary>

    /**
     * Apply a user-visible rename. The agent uses this when it is confident about a
     * meaningful name for an obfuscated symbol. The display layer updates reactively.
     */
    fun renameSymbol(originalFqn: String, newName: String)

    /**
     * Apply many renames in one go. Used after a batch-rename analysis so the UI
     * does not churn on every single rename. Entries with blank new names are skipped.
     */
    fun batchRename(renames: Map<String, String>)

    /** Current rename map (originalFqn → newName). */
    fun getRenames(): Map<String, String>

    /**
     * Decompile multiple classes at once. The agent uses this for cross-reference
     * analysis where understanding a flow requires seeing several classes together.
     * Returned map keys are the requested FQNs; values are either the decompiled
     * source or an error message.
     */
    suspend fun decompileMultiple(classNames: List<String>): Map<String, String>

    /**
     * Heap histogram: instance count and total bytes per class, optionally filtered by name.
     * Returns empty if no heap snapshot is captured yet.
     */
    suspend fun getHeapHistogram(nameFilter: String = "", limit: Int = 50): List<HeapHistogramRow>
}

data class ClassSummary(
    val name: String,
    val isInterface: Boolean = false,
    val isAbstract: Boolean = false,
)

data class ClassDetail(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val methods: List<MethodSummary>,
    val fields: List<FieldSummary>,
)

data class MethodSummary(
    val name: String,
    val descriptor: String,
    val isStatic: Boolean = false,
)

data class FieldSummary(
    val name: String,
    val descriptor: String,
    val isStatic: Boolean = false,
)

data class StringMatch(
    val className: String,
    val value: String,
)

data class TraceSummary(
    val className: String,
    val methodName: String,
    val eventType: String,
    val depth: Int,
    val durationNanos: Long? = null,
    val arguments: String? = null,
    val returnValue: String? = null,
)

data class HeapHistogramRow(
    val className: String,
    val instanceCount: Long,
    val totalBytes: Long,
)
