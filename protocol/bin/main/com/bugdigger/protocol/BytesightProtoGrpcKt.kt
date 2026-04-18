package com.bugdigger.protocol

import com.bugdigger.protocol.BytesightAgentGrpc.getServiceDescriptor
import io.grpc.CallOptions
import io.grpc.CallOptions.DEFAULT
import io.grpc.Channel
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.ServerServiceDefinition.builder
import io.grpc.ServiceDescriptor
import io.grpc.Status.UNIMPLEMENTED
import io.grpc.StatusException
import io.grpc.kotlin.AbstractCoroutineServerImpl
import io.grpc.kotlin.AbstractCoroutineStub
import io.grpc.kotlin.ClientCalls.serverStreamingRpc
import io.grpc.kotlin.ClientCalls.unaryRpc
import io.grpc.kotlin.ServerCalls.serverStreamingServerMethodDefinition
import io.grpc.kotlin.ServerCalls.unaryServerMethodDefinition
import io.grpc.kotlin.StubFor
import kotlin.String
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlinx.coroutines.flow.Flow

/**
 * Holder for Kotlin coroutine-based client and server APIs for bytesight.BytesightAgent.
 */
public object BytesightAgentGrpcKt {
  public const val SERVICE_NAME: String = BytesightAgentGrpc.SERVICE_NAME

  @JvmStatic
  public val serviceDescriptor: ServiceDescriptor
    get() = getServiceDescriptor()

  public val getAgentInfoMethod: MethodDescriptor<GetAgentInfoRequest, AgentInfo>
    @JvmStatic
    get() = BytesightAgentGrpc.getGetAgentInfoMethod()

  public val pingMethod: MethodDescriptor<PingRequest, PingResponse>
    @JvmStatic
    get() = BytesightAgentGrpc.getPingMethod()

  public val getLoadedClassesMethod: MethodDescriptor<GetClassesRequest, ClassInfo>
    @JvmStatic
    get() = BytesightAgentGrpc.getGetLoadedClassesMethod()

  public val getClassBytecodeMethod: MethodDescriptor<GetBytecodeRequest, BytecodeResponse>
    @JvmStatic
    get() = BytesightAgentGrpc.getGetClassBytecodeMethod()

  public val subscribeClassLoadingMethod: MethodDescriptor<SubscribeRequest, ClassLoadEvent>
    @JvmStatic
    get() = BytesightAgentGrpc.getSubscribeClassLoadingMethod()

  public val addHookMethod: MethodDescriptor<AddHookRequest, HookResponse>
    @JvmStatic
    get() = BytesightAgentGrpc.getAddHookMethod()

  public val removeHookMethod: MethodDescriptor<RemoveHookRequest, HookResponse>
    @JvmStatic
    get() = BytesightAgentGrpc.getRemoveHookMethod()

  public val listHooksMethod: MethodDescriptor<ListHooksRequest, ListHooksResponse>
    @JvmStatic
    get() = BytesightAgentGrpc.getListHooksMethod()

  public val startTracingMethod: MethodDescriptor<StartTracingRequest, TracingResponse>
    @JvmStatic
    get() = BytesightAgentGrpc.getStartTracingMethod()

  public val stopTracingMethod: MethodDescriptor<StopTracingRequest, TracingResponse>
    @JvmStatic
    get() = BytesightAgentGrpc.getStopTracingMethod()

  public val subscribeMethodTracesMethod: MethodDescriptor<SubscribeRequest, MethodTraceEvent>
    @JvmStatic
    get() = BytesightAgentGrpc.getSubscribeMethodTracesMethod()

  public val captureHeapSnapshotMethod:
      MethodDescriptor<CaptureHeapSnapshotRequest, HeapSnapshotInfo>
    @JvmStatic
    get() = BytesightAgentGrpc.getCaptureHeapSnapshotMethod()

  public val getClassHistogramMethod:
      MethodDescriptor<GetClassHistogramRequest, ClassHistogramEntry>
    @JvmStatic
    get() = BytesightAgentGrpc.getGetClassHistogramMethod()

  /**
   * A stub for issuing RPCs to a(n) bytesight.BytesightAgent service as suspending coroutines.
   */
  @StubFor(BytesightAgentGrpc::class)
  public class BytesightAgentCoroutineStub @JvmOverloads constructor(
    channel: Channel,
    callOptions: CallOptions = DEFAULT,
  ) : AbstractCoroutineStub<BytesightAgentCoroutineStub>(channel, callOptions) {
    override fun build(channel: Channel, callOptions: CallOptions): BytesightAgentCoroutineStub =
        BytesightAgentCoroutineStub(channel, callOptions)

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun getAgentInfo(request: GetAgentInfoRequest, headers: Metadata = Metadata()):
        AgentInfo = unaryRpc(
      channel,
      BytesightAgentGrpc.getGetAgentInfoMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun ping(request: PingRequest, headers: Metadata = Metadata()): PingResponse =
        unaryRpc(
      channel,
      BytesightAgentGrpc.getPingMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun getLoadedClasses(request: GetClassesRequest, headers: Metadata = Metadata()):
        Flow<ClassInfo> = serverStreamingRpc(
      channel,
      BytesightAgentGrpc.getGetLoadedClassesMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun getClassBytecode(request: GetBytecodeRequest, headers: Metadata =
        Metadata()): BytecodeResponse = unaryRpc(
      channel,
      BytesightAgentGrpc.getGetClassBytecodeMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun subscribeClassLoading(request: SubscribeRequest, headers: Metadata = Metadata()):
        Flow<ClassLoadEvent> = serverStreamingRpc(
      channel,
      BytesightAgentGrpc.getSubscribeClassLoadingMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun addHook(request: AddHookRequest, headers: Metadata = Metadata()):
        HookResponse = unaryRpc(
      channel,
      BytesightAgentGrpc.getAddHookMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun removeHook(request: RemoveHookRequest, headers: Metadata = Metadata()):
        HookResponse = unaryRpc(
      channel,
      BytesightAgentGrpc.getRemoveHookMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun listHooks(request: ListHooksRequest, headers: Metadata = Metadata()):
        ListHooksResponse = unaryRpc(
      channel,
      BytesightAgentGrpc.getListHooksMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun startTracing(request: StartTracingRequest, headers: Metadata = Metadata()):
        TracingResponse = unaryRpc(
      channel,
      BytesightAgentGrpc.getStartTracingMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun stopTracing(request: StopTracingRequest, headers: Metadata = Metadata()):
        TracingResponse = unaryRpc(
      channel,
      BytesightAgentGrpc.getStopTracingMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun subscribeMethodTraces(request: SubscribeRequest, headers: Metadata = Metadata()):
        Flow<MethodTraceEvent> = serverStreamingRpc(
      channel,
      BytesightAgentGrpc.getSubscribeMethodTracesMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Executes this RPC and returns the response message, suspending until the RPC completes
     * with [`Status.OK`][io.grpc.Status].  If the RPC completes with another status, a
     * corresponding
     * [StatusException] is thrown.  If this coroutine is cancelled, the RPC is also cancelled
     * with the corresponding exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return The single response from the server.
     */
    public suspend fun captureHeapSnapshot(request: CaptureHeapSnapshotRequest, headers: Metadata =
        Metadata()): HeapSnapshotInfo = unaryRpc(
      channel,
      BytesightAgentGrpc.getCaptureHeapSnapshotMethod(),
      request,
      callOptions,
      headers
    )

    /**
     * Returns a [Flow] that, when collected, executes this RPC and emits responses from the
     * server as they arrive.  That flow finishes normally if the server closes its response with
     * [`Status.OK`][io.grpc.Status], and fails by throwing a [StatusException] otherwise.  If
     * collecting the flow downstream fails exceptionally (including via cancellation), the RPC
     * is cancelled with that exception as a cause.
     *
     * @param request The request message to send to the server.
     *
     * @param headers Metadata to attach to the request.  Most users will not need this.
     *
     * @return A flow that, when collected, emits the responses from the server.
     */
    public fun getClassHistogram(request: GetClassHistogramRequest, headers: Metadata = Metadata()):
        Flow<ClassHistogramEntry> = serverStreamingRpc(
      channel,
      BytesightAgentGrpc.getGetClassHistogramMethod(),
      request,
      callOptions,
      headers
    )
  }

  /**
   * Skeletal implementation of the bytesight.BytesightAgent service based on Kotlin coroutines.
   */
  public abstract class BytesightAgentCoroutineImplBase(
    coroutineContext: CoroutineContext = EmptyCoroutineContext,
  ) : AbstractCoroutineServerImpl(coroutineContext) {
    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.GetAgentInfo.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getAgentInfo(request: GetAgentInfoRequest): AgentInfo = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.GetAgentInfo is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.Ping.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun ping(request: PingRequest): PingResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.Ping is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for bytesight.BytesightAgent.GetLoadedClasses.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun getLoadedClasses(request: GetClassesRequest): Flow<ClassInfo> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.GetLoadedClasses is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.GetClassBytecode.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun getClassBytecode(request: GetBytecodeRequest): BytecodeResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.GetClassBytecode is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for bytesight.BytesightAgent.SubscribeClassLoading.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun subscribeClassLoading(request: SubscribeRequest): Flow<ClassLoadEvent> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.SubscribeClassLoading is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.AddHook.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun addHook(request: AddHookRequest): HookResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.AddHook is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.RemoveHook.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun removeHook(request: RemoveHookRequest): HookResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.RemoveHook is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.ListHooks.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun listHooks(request: ListHooksRequest): ListHooksResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.ListHooks is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.StartTracing.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun startTracing(request: StartTracingRequest): TracingResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.StartTracing is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.StopTracing.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun stopTracing(request: StopTracingRequest): TracingResponse = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.StopTracing is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for bytesight.BytesightAgent.SubscribeMethodTraces.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun subscribeMethodTraces(request: SubscribeRequest): Flow<MethodTraceEvent> = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.SubscribeMethodTraces is unimplemented"))

    /**
     * Returns the response to an RPC for bytesight.BytesightAgent.CaptureHeapSnapshot.
     *
     * If this method fails with a [StatusException], the RPC will fail with the corresponding
     * [io.grpc.Status].  If this method fails with a [java.util.concurrent.CancellationException],
     * the RPC will fail
     * with status `Status.CANCELLED`.  If this method fails for any other reason, the RPC will
     * fail with `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open suspend fun captureHeapSnapshot(request: CaptureHeapSnapshotRequest):
        HeapSnapshotInfo = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.CaptureHeapSnapshot is unimplemented"))

    /**
     * Returns a [Flow] of responses to an RPC for bytesight.BytesightAgent.GetClassHistogram.
     *
     * If creating or collecting the returned flow fails with a [StatusException], the RPC
     * will fail with the corresponding [io.grpc.Status].  If it fails with a
     * [java.util.concurrent.CancellationException], the RPC will fail with status
     * `Status.CANCELLED`.  If creating
     * or collecting the returned flow fails for any other reason, the RPC will fail with
     * `Status.UNKNOWN` with the exception as a cause.
     *
     * @param request The request from the client.
     */
    public open fun getClassHistogram(request: GetClassHistogramRequest): Flow<ClassHistogramEntry>
        = throw
        StatusException(UNIMPLEMENTED.withDescription("Method bytesight.BytesightAgent.GetClassHistogram is unimplemented"))

    final override fun bindService(): ServerServiceDefinition = builder(getServiceDescriptor())
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getGetAgentInfoMethod(),
      implementation = ::getAgentInfo
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getPingMethod(),
      implementation = ::ping
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getGetLoadedClassesMethod(),
      implementation = ::getLoadedClasses
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getGetClassBytecodeMethod(),
      implementation = ::getClassBytecode
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getSubscribeClassLoadingMethod(),
      implementation = ::subscribeClassLoading
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getAddHookMethod(),
      implementation = ::addHook
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getRemoveHookMethod(),
      implementation = ::removeHook
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getListHooksMethod(),
      implementation = ::listHooks
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getStartTracingMethod(),
      implementation = ::startTracing
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getStopTracingMethod(),
      implementation = ::stopTracing
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getSubscribeMethodTracesMethod(),
      implementation = ::subscribeMethodTraces
    ))
      .addMethod(unaryServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getCaptureHeapSnapshotMethod(),
      implementation = ::captureHeapSnapshot
    ))
      .addMethod(serverStreamingServerMethodDefinition(
      context = this.context,
      descriptor = BytesightAgentGrpc.getGetClassHistogramMethod(),
      implementation = ::getClassHistogram
    )).build()
  }
}
