package com.bugdigger.bytesight.source

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.protocol.ClassInfo

/**
 * [ClassSource] backed by a live agent connection. Thin wrapper over
 * [AgentClient]; no caching here so behavior matches the pre-refactor flow
 * where every browse/select round-trips to the agent.
 *
 * The constructor takes the [connectionKey] returned by [AgentClient.connect];
 * [close] does not disconnect the underlying gRPC channel because the
 * connection is owned by [AgentClient] and may outlive this source if the
 * user simply switches sources.
 */
class AgentClassSource(
    private val agentClient: AgentClient,
    val connectionKey: String,
) : ClassSource {

    override val capabilities: Set<Capability> = Capability.ALL

    override val displayName: String = "JVM @ $connectionKey"

    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> {
        return agentClient.listClasses(
            connectionKey = connectionKey,
            packageFilter = "",
            includeSystemClasses = includeSystemClasses,
        )
    }

    override suspend fun getBytecode(className: String): Result<ByteArray> {
        val rpc = agentClient.getClassBytecode(connectionKey, className)
        return rpc.fold(
            onSuccess = { response ->
                if (response.found) {
                    Result.success(response.bytecode.toByteArray())
                } else {
                    Result.failure(NoSuchElementException(
                        if (response.error.isNotEmpty()) response.error
                        else "Class not found: $className"
                    ))
                }
            },
            onFailure = { Result.failure(it) },
        )
    }
}
