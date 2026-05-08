package com.bugdigger.bytesight.source

import com.bugdigger.protocol.ClassInfo

/**
 * Abstraction over "where class bytes come from". Hides the difference
 * between a live attached agent, a JAR/APK on disk (added in Step 2), and
 * a previously-saved `.bts` project file (Step 3).
 *
 * ViewModels that only need class metadata + bytecode read through this
 * interface so they don't have to care about the source. Runtime tabs
 * (Trace, Heap, Debugger) keep talking to AgentClient directly because
 * their RPCs aren't part of this contract.
 */
interface ClassSource {
    /** What this source supports beyond static analysis. */
    val capabilities: Set<Capability>

    /** Short label for the title bar / status bar (e.g. "PID 1234", "sample.jar"). */
    val displayName: String

    /**
     * Returns the list of classes this source knows about. Mirrors
     * [com.bugdigger.bytesight.service.AgentClient.listClasses].
     *
     * @param includeSystemClasses include `java.*`/`javax.*`/`sun.*`. Static
     *   sources may ignore this and return everything in the JAR/APK.
     */
    suspend fun listClasses(includeSystemClasses: Boolean = false): Result<List<ClassInfo>>

    /**
     * Returns raw bytecode for the given fully-qualified class name, or a
     * failure if the class is unknown to this source.
     */
    suspend fun getBytecode(className: String): Result<ByteArray>

    /** Release any held resources (close JarFile handles, etc.). Default: no-op. */
    fun close() {}
}
