package com.bugdigger.bytesight.service

import com.bugdigger.protocol.*
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * gRPC client for communicating with the Bytesight agent injected into target JVMs.
 */
class AgentClient {

    private val logger = LoggerFactory.getLogger(AgentClient::class.java)

    private val connections = ConcurrentHashMap<String, AgentConnection>()

    private data class AgentConnection(
        val channel: ManagedChannel,
        val stub: BytesightAgentGrpcKt.BytesightAgentCoroutineStub,
    )

    /**
     * Connects to an agent running on the specified port.
     *
     * @param host The host where the agent is running (usually localhost)
     * @param port The port the agent's gRPC server is listening on
     * @return The connection key to use for subsequent operations
     */
    suspend fun connect(host: String = "localhost", port: Int): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val key = "$host:$port"

            // Close existing connection if any
            disconnect(key)

            val channel = ManagedChannelBuilder
                .forAddress(host, port)
                .usePlaintext()
                .build()

            val stub = BytesightAgentGrpcKt.BytesightAgentCoroutineStub(channel)

            // Verify connection by getting agent info
            val infoRequest = getAgentInfoRequest { }
            val info = stub.getAgentInfo(infoRequest)
            logger.info("Connected to agent: version=${info.agentVersion}, target PID=${info.targetPid}")

            connections[key] = AgentConnection(channel, stub)
            key
        }.onFailure { e ->
            logger.error("Failed to connect to agent at port $port", e)
        }
    }

    /**
     * Disconnects from the specified agent.
     */
    fun disconnect(connectionKey: String) {
        connections.remove(connectionKey)?.let { conn ->
            try {
                conn.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                logger.warn("Error shutting down channel: ${e.message}")
                conn.channel.shutdownNow()
            }
        }
    }

    /**
     * Disconnects from all agents.
     */
    fun disconnectAll() {
        connections.keys.toList().forEach { disconnect(it) }
    }

    /**
     * Gets information about the connected agent.
     */
    suspend fun getAgentInfo(connectionKey: String): Result<AgentInfo> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.getAgentInfo(getAgentInfoRequest { })
        }
    }

    /**
     * Lists all classes loaded in the target JVM.
     * Note: This is a streaming RPC that returns ClassInfo objects one by one.
     */
    suspend fun listClasses(
        connectionKey: String,
        packageFilter: String = "",
        includeSystemClasses: Boolean = false,
    ): Result<List<ClassInfo>> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            val request = getClassesRequest {
                this.packageFilter = packageFilter
                this.includeSystem = includeSystemClasses
            }
            stub.getLoadedClasses(request).toList()
        }
    }

    /**
     * Gets the bytecode of a specific class.
     */
    suspend fun getClassBytecode(
        connectionKey: String,
        className: String,
    ): Result<BytecodeResponse> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.getClassBytecode(getBytecodeRequest {
                this.className = className
            })
        }
    }

    /**
     * Adds a hook to a method for tracing.
     */
    suspend fun addHook(
        connectionKey: String,
        hookId: String,
        className: String,
        methodName: String,
        methodSignature: String = "",
        hookType: HookType = HookType.LOG_ENTRY_EXIT,
    ): Result<HookResponse> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.addHook(addHookRequest {
                this.id = hookId
                this.className = className
                this.methodName = methodName
                this.methodSignature = methodSignature
                this.hookType = hookType
            })
        }
    }

    /**
     * Removes a hook from a method.
     */
    suspend fun removeHook(
        connectionKey: String,
        hookId: String,
    ): Result<HookResponse> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.removeHook(removeHookRequest {
                this.hookId = hookId
            })
        }
    }

    /**
     * Streams method trace events from the agent.
     */
    fun streamTraceEvents(connectionKey: String): Result<Flow<MethodTraceEvent>> {
        val conn = connections[connectionKey]
            ?: return Result.failure(IllegalStateException("Not connected: $connectionKey"))

        return runCatching {
            conn.stub.subscribeMethodTraces(subscribeRequest { })
        }
    }

    /**
     * Lists all active hooks.
     */
    suspend fun listHooks(connectionKey: String): Result<ListHooksResponse> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.listHooks(listHooksRequest { })
        }
    }

    /**
     * Captures a heap snapshot in the target JVM. Safepoint-heavy — the target pauses
     * briefly while the native helper walks the heap.
     */
    suspend fun captureHeapSnapshot(connectionKey: String): Result<HeapSnapshotInfo> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.captureHeapSnapshot(captureHeapSnapshotRequest { })
        }
    }

    /**
     * Fetches the class histogram for a previously captured snapshot.
     */
    suspend fun getClassHistogram(
        connectionKey: String,
        snapshotId: Long,
        nameFilter: String = "",
    ): Result<List<ClassHistogramEntry>> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.getClassHistogram(getClassHistogramRequest {
                this.snapshotId = snapshotId
                this.nameFilter = nameFilter
            }).toList()
        }
    }

    /**
     * Lists instances of a specific class in a snapshot.
     */
    suspend fun listInstances(
        connectionKey: String,
        snapshotId: Long,
        className: String,
        limit: Int = 500,
    ): Result<List<InstanceSummary>> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.listInstances(listInstancesRequest {
                this.snapshotId = snapshotId
                this.className = className
                this.limit = limit
            }).toList()
        }
    }

    /**
     * Gets detailed information about a single object by its JVMTI tag.
     */
    suspend fun getObject(
        connectionKey: String,
        snapshotId: Long,
        tag: Long,
    ): Result<ObjectDetail> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.getObject(getObjectRequest {
                this.snapshotId = snapshotId
                this.tag = tag
            })
        }
    }

    /**
     * Searches for values in a heap snapshot. Uses string-contains mode if
     * [stringContains] is non-empty, otherwise field-equals mode.
     */
    suspend fun searchValues(
        connectionKey: String,
        snapshotId: Long,
        stringContains: String = "",
        fieldClassName: String = "",
        fieldName: String = "",
        fieldValue: String = "",
        limit: Int = 500,
    ): Result<List<ValueMatch>> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.searchValues(searchValuesRequest {
                this.snapshotId = snapshotId
                this.stringContains = stringContains
                this.fieldClassName = fieldClassName
                this.fieldName = fieldName
                this.fieldValue = fieldValue
                this.limit = limit
            }).toList()
        }
    }

    /**
     * Finds duplicate java.lang.String instances in the snapshot.
     */
    suspend fun findDuplicateStrings(
        connectionKey: String,
        snapshotId: Long,
        minCount: Int = 2,
        minLength: Int = 1,
        limitGroups: Int = 100,
    ): Result<List<DuplicateStringGroup>> = withContext(Dispatchers.IO) {
        withConnection(connectionKey) { stub ->
            stub.findDuplicateStrings(findDuplicateStringsRequest {
                this.snapshotId = snapshotId
                this.minCount = minCount
                this.minLength = minLength
                this.limitGroups = limitGroups
            }).toList()
        }
    }

    private inline fun <T> withConnection(
        connectionKey: String,
        block: (BytesightAgentGrpcKt.BytesightAgentCoroutineStub) -> T,
    ): Result<T> {
        val conn = connections[connectionKey]
            ?: return Result.failure(IllegalStateException("Not connected: $connectionKey"))
        return runCatching { block(conn.stub) }
    }
}
