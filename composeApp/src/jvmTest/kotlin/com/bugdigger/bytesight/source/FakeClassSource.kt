package com.bugdigger.bytesight.source

import com.bugdigger.protocol.ClassInfo

/** Test fake. Configure with a class list and a per-class bytecode map. */
class FakeClassSource(
    private val classes: List<ClassInfo> = emptyList(),
    private val bytecode: Map<String, ByteArray> = emptyMap(),
    override val capabilities: Set<Capability> = Capability.ALL,
    override val displayName: String = "fake",
    private val listFailure: Throwable? = null,
    private val bytecodeFailure: Throwable? = null,
) : ClassSource {
    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> =
        listFailure?.let { Result.failure(it) } ?: Result.success(classes)

    override suspend fun getBytecode(className: String): Result<ByteArray> =
        bytecodeFailure?.let { Result.failure(it) }
            ?: bytecode[className]?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Class not found: $className"))
}
