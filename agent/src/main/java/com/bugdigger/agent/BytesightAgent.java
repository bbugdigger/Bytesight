package com.bugdigger.agent;

import com.bugdigger.agent.collector.ClassCollector;
import com.bugdigger.agent.server.AgentGrpcServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.instrument.Instrumentation;

/**
 * Bytesight Java Agent - Entry point for JVM instrumentation.
 * 
 * Can be loaded via:
 * 1. -javaagent:bytesight-agent.jar=port=50051 (at JVM startup)
 * 2. Attach API (to a running JVM)
 */
public class BytesightAgent {
    private static final Logger logger = LoggerFactory.getLogger(BytesightAgent.class);
    
    private static volatile Instrumentation instrumentation;
    private static volatile AgentGrpcServer grpcServer;
    private static volatile ClassCollector classCollector;
    private static volatile boolean initialized = false;
    
    /**
     * Called when agent is loaded at JVM startup via -javaagent flag.
     */
    public static void premain(String agentArgs, Instrumentation inst) {
        logger.info("[Bytesight] premain called with args: {}", agentArgs);
        initialize(agentArgs, inst);
    }
    
    /**
     * Called when agent is loaded dynamically via Attach API.
     */
    public static void agentmain(String agentArgs, Instrumentation inst) {
        logger.info("[Bytesight] agentmain called with args: {}", agentArgs);
        initialize(agentArgs, inst);
    }
    
    private static synchronized void initialize(String agentArgs, Instrumentation inst) {
        if (initialized) {
            logger.warn("[Bytesight] Agent already initialized, skipping");
            return;
        }
        
        try {
            instrumentation = inst;
            AgentConfig config = AgentConfig.parse(agentArgs);
            
            logger.info("[Bytesight] Initializing with config: port={}", config.getPort());
            
            // Initialize class collector
            classCollector = new ClassCollector(instrumentation);
            instrumentation.addTransformer(classCollector, true);
            
            // Capture already loaded classes
            classCollector.captureLoadedClasses(instrumentation.getAllLoadedClasses());
            
            // Start gRPC server
            grpcServer = new AgentGrpcServer(config.getPort(), instrumentation, classCollector);
            grpcServer.start();
            
            initialized = true;
            
            logger.info("[Bytesight] Agent initialized successfully on port {}", config.getPort());
            logger.info("[Bytesight] Target JVM: {} {}", 
                System.getProperty("java.vendor"),
                System.getProperty("java.version"));
            logger.info("[Bytesight] PID: {}", ProcessHandle.current().pid());
            
            // Register shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("[Bytesight] Shutting down...");
                if (grpcServer != null) {
                    grpcServer.stop();
                }
            }));
            
        } catch (Exception e) {
            logger.error("[Bytesight] Failed to initialize agent", e);
            throw new RuntimeException("Failed to initialize Bytesight agent", e);
        }
    }
    
    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
    
    public static ClassCollector getClassCollector() {
        return classCollector;
    }
    
    public static boolean isInitialized() {
        return initialized;
    }
}
