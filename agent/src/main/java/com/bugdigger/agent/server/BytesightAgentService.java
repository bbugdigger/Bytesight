package com.bugdigger.agent.server;

import com.bugdigger.agent.collector.ClassCollector;
import com.bugdigger.agent.collector.LoadedClassInfo;
import com.bugdigger.agent.hook.HookManager;
import com.bugdigger.agent.hook.TraceEventBuffer;
import com.bugdigger.protocol.*;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

/**
 * Implementation of the BytesightAgent gRPC service.
 * Handles all RPC calls from the UI client.
 */
public class BytesightAgentService extends BytesightAgentGrpc.BytesightAgentImplBase {
    private static final Logger logger = LoggerFactory.getLogger(BytesightAgentService.class);
    private static final String AGENT_VERSION = "1.0.0";
    
    private final Instrumentation instrumentation;
    private final ClassCollector classCollector;
    private final HookManager hookManager;
    private final long startTime;
    
    public BytesightAgentService(Instrumentation instrumentation, ClassCollector classCollector,
                                 HookManager hookManager) {
        this.instrumentation = instrumentation;
        this.classCollector = classCollector;
        this.hookManager = hookManager;
        this.startTime = System.currentTimeMillis();
    }
    
    @Override
    public void getAgentInfo(GetAgentInfoRequest request, StreamObserver<AgentInfo> responseObserver) {
        logger.debug("getAgentInfo called");
        
        AgentInfo info = AgentInfo.newBuilder()
            .setAgentVersion(AGENT_VERSION)
            .setTargetJvmVersion(System.getProperty("java.version"))
            .setTargetJvmVendor(System.getProperty("java.vendor"))
            .setTargetMainClass(getMainClassName())
            .setTargetPid(ProcessHandle.current().pid())
            .setStartTime(startTime)
            .build();
        
        responseObserver.onNext(info);
        responseObserver.onCompleted();
    }
    
    @Override
    public void ping(PingRequest request, StreamObserver<PingResponse> responseObserver) {
        PingResponse response = PingResponse.newBuilder()
            .setTimestamp(System.currentTimeMillis())
            .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void getLoadedClasses(GetClassesRequest request, StreamObserver<ClassInfo> responseObserver) {
        logger.debug("getLoadedClasses called with filter: {}", request.getPackageFilter());
        
        String packageFilter = request.getPackageFilter();
        boolean includeSystem = request.getIncludeSystem();
        
        Map<String, LoadedClassInfo> classes = classCollector.getLoadedClasses();
        
        for (LoadedClassInfo info : classes.values()) {
            // Apply filters
            if (!includeSystem && isSystemClass(info.getClassName())) {
                continue;
            }
            
            if (!packageFilter.isEmpty() && !info.getClassName().startsWith(packageFilter)) {
                continue;
            }
            
            try {
                ClassInfo classInfo = buildClassInfo(info);
                responseObserver.onNext(classInfo);
            } catch (Exception e) {
                logger.warn("Failed to build ClassInfo for {}: {}", info.getClassName(), e.getMessage());
            }
        }
        
        responseObserver.onCompleted();
    }
    
    @Override
    public void getClassBytecode(GetBytecodeRequest request, StreamObserver<BytecodeResponse> responseObserver) {
        String className = request.getClassName();
        logger.debug("getClassBytecode called for: {}", className);
        
        byte[] bytecode = classCollector.getBytecode(className);
        
        BytecodeResponse.Builder builder = BytecodeResponse.newBuilder()
            .setClassName(className);
        
        if (bytecode != null) {
            builder.setBytecode(com.google.protobuf.ByteString.copyFrom(bytecode))
                   .setFound(true);
        } else {
            builder.setFound(false)
                   .setError("Class not found or bytecode not captured");
        }
        
        responseObserver.onNext(builder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void subscribeClassLoading(SubscribeRequest request, StreamObserver<ClassLoadEvent> responseObserver) {
        logger.debug("subscribeClassLoading called with filter: {}", request.getFilter());
        
        // Register listener with class collector
        String filter = request.getFilter();
        classCollector.addClassLoadListener((classInfo, bytecode) -> {
            if (filter.isEmpty() || classInfo.getClassName().contains(filter)) {
                try {
                    ClassLoadEvent event = ClassLoadEvent.newBuilder()
                        .setClassInfo(buildClassInfo(classInfo))
                        .setTimestamp(System.currentTimeMillis())
                        .setEventType(ClassLoadEvent.EventType.LOADED)
                        .build();
                    
                    responseObserver.onNext(event);
                } catch (Exception e) {
                    logger.warn("Failed to send class load event: {}", e.getMessage());
                }
            }
        });
        
        // Keep the stream open - will be closed when client disconnects
    }
    
    @Override
    public void addHook(AddHookRequest request, StreamObserver<HookResponse> responseObserver) {
        logger.info("addHook called for {}.{}", request.getClassName(), request.getMethodName());
        
        HookManager.HookResult result = hookManager.addHook(
                request.getId(),
                request.getClassName(),
                request.getMethodName(),
                request.getMethodSignature().isEmpty() ? null : request.getMethodSignature(),
                request.getHookType()
        );
        
        HookResponse.Builder responseBuilder = HookResponse.newBuilder()
                .setSuccess(result.isSuccess());
        
        if (result.isSuccess()) {
            responseBuilder.setHookId(result.getHookId());
        } else {
            responseBuilder.setError(result.getError());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void removeHook(RemoveHookRequest request, StreamObserver<HookResponse> responseObserver) {
        logger.info("removeHook called for id: {}", request.getHookId());
        
        HookManager.HookResult result = hookManager.removeHook(request.getHookId());
        
        HookResponse.Builder responseBuilder = HookResponse.newBuilder()
                .setSuccess(result.isSuccess());
        
        if (result.isSuccess()) {
            responseBuilder.setHookId(result.getHookId());
        } else {
            responseBuilder.setError(result.getError());
        }
        
        responseObserver.onNext(responseBuilder.build());
        responseObserver.onCompleted();
    }
    
    @Override
    public void listHooks(ListHooksRequest request, StreamObserver<ListHooksResponse> responseObserver) {
        logger.debug("listHooks called");
        
        ListHooksResponse response = ListHooksResponse.newBuilder()
                .addAllHooks(hookManager.listHooks())
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void startTracing(StartTracingRequest request, StreamObserver<TracingResponse> responseObserver) {
        logger.info("startTracing called for class filter: {}, method filter: {}", 
                request.getClassFilter(), request.getMethodFilter());
        
        // Generate a unique trace ID
        String traceId = "trace-" + System.currentTimeMillis();
        
        // For now, tracing is activated by adding hooks - the TraceEventBuffer will
        // broadcast events to all subscribers. Full pattern-based tracing can be added later.
        TracingResponse response = TracingResponse.newBuilder()
                .setSuccess(true)
                .setTraceId(traceId)
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void stopTracing(StopTracingRequest request, StreamObserver<TracingResponse> responseObserver) {
        logger.info("stopTracing called for id: {}", request.getTraceId());
        
        // For now, stopping tracing doesn't do much - hooks need to be removed individually
        TracingResponse response = TracingResponse.newBuilder()
                .setSuccess(true)
                .setTraceId(request.getTraceId())
                .build();
        
        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
    
    @Override
    public void subscribeMethodTraces(SubscribeRequest request, StreamObserver<MethodTraceEvent> responseObserver) {
        logger.debug("subscribeMethodTraces called with filter: {}", request.getFilter());
        
        String filter = request.getFilter();
        
        // Register listener with TraceEventBuffer to receive all trace events
        Runnable unregister = TraceEventBuffer.getInstance().addListener(event -> {
            // Apply filter if specified
            if (!filter.isEmpty()) {
                if (!event.getClassName().contains(filter) && !event.getMethodName().contains(filter)) {
                    return;
                }
            }
            
            try {
                responseObserver.onNext(event);
            } catch (Exception e) {
                logger.warn("Failed to send trace event: {}", e.getMessage());
            }
        });
        
        // Note: The stream stays open until the client disconnects.
        // In a more robust implementation, we'd handle onCancel/onComplete from the client
        // to properly unregister the listener.
        logger.info("Trace event subscriber registered");
    }
    
    // ========== Helper Methods ==========
    
    private ClassInfo buildClassInfo(LoadedClassInfo info) {
        ClassInfo.Builder builder = ClassInfo.newBuilder()
            .setName(info.getClassName())
            .setPackageName(getPackageName(info.getClassName()))
            .setSimpleName(getSimpleName(info.getClassName()))
            .setLoadedAt(info.getLoadedAt());
        
        Class<?> clazz = info.getLoadedClass();
        if (clazz != null) {
            builder.setModifiers(clazz.getModifiers())
                   .setIsInterface(clazz.isInterface())
                   .setIsEnum(clazz.isEnum())
                   .setIsAnnotation(clazz.isAnnotation())
                   .setIsSynthetic(clazz.isSynthetic());
            
            if (clazz.getSuperclass() != null) {
                builder.setSuperclass(clazz.getSuperclass().getName());
            }
            
            for (Class<?> iface : clazz.getInterfaces()) {
                builder.addInterfaces(iface.getName());
            }
            
            ClassLoader classLoader = clazz.getClassLoader();
            builder.setClassLoader(classLoader != null ? classLoader.toString() : "bootstrap");
            
            // Add method info
            try {
                for (Method method : clazz.getDeclaredMethods()) {
                    MethodInfo methodInfo = MethodInfo.newBuilder()
                        .setName(method.getName())
                        .setSignature(getMethodSignature(method))
                        .setReturnType(method.getReturnType().getName())
                        .addAllParameterTypes(
                            Arrays.stream(method.getParameterTypes())
                                  .map(Class::getName)
                                  .toList())
                        .setModifiers(method.getModifiers())
                        .setIsSynthetic(method.isSynthetic())
                        .setIsBridge(method.isBridge())
                        .build();
                    
                    builder.addMethods(methodInfo);
                }
            } catch (Exception e) {
                logger.trace("Could not get methods for {}: {}", info.getClassName(), e.getMessage());
            }
            
            // Add field info
            try {
                for (Field field : clazz.getDeclaredFields()) {
                    FieldInfo fieldInfo = FieldInfo.newBuilder()
                        .setName(field.getName())
                        .setType(field.getType().getName())
                        .setModifiers(field.getModifiers())
                        .setIsSynthetic(field.isSynthetic())
                        .build();
                    
                    builder.addFields(fieldInfo);
                }
            } catch (Exception e) {
                logger.trace("Could not get fields for {}: {}", info.getClassName(), e.getMessage());
            }
        }
        
        return builder.build();
    }
    
    private String getMethodSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append("(");
        for (Class<?> param : method.getParameterTypes()) {
            sb.append(getTypeDescriptor(param));
        }
        sb.append(")");
        sb.append(getTypeDescriptor(method.getReturnType()));
        return sb.toString();
    }
    
    private String getTypeDescriptor(Class<?> type) {
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        if (type.isArray()) return "[" + getTypeDescriptor(type.getComponentType());
        return "L" + type.getName().replace('.', '/') + ";";
    }
    
    private boolean isSystemClass(String className) {
        return className.startsWith("java.") ||
               className.startsWith("javax.") ||
               className.startsWith("sun.") ||
               className.startsWith("jdk.") ||
               className.startsWith("com.sun.") ||
               className.startsWith("org.grpc.") ||
               className.startsWith("io.grpc.") ||
               className.startsWith("io.netty.") ||
               className.startsWith("com.google.") ||
               className.startsWith("net.bytebuddy.") ||
               className.startsWith("ch.qos.logback.") ||
               className.startsWith("org.slf4j.");
    }
    
    private String getPackageName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(0, lastDot) : "";
    }
    
    private String getSimpleName(String className) {
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(lastDot + 1) : className;
    }
    
    private String getMainClassName() {
        // Try to determine the main class from stack traces
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            if (element.getMethodName().equals("main")) {
                return element.getClassName();
            }
        }
        
        // Fallback: check sun.java.command system property
        String command = System.getProperty("sun.java.command");
        if (command != null && !command.isEmpty()) {
            String[] parts = command.split(" ");
            if (parts.length > 0) {
                return parts[0];
            }
        }
        
        return "unknown";
    }
}
