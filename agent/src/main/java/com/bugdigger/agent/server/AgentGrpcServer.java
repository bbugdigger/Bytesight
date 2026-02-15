package com.bugdigger.agent.server;

import com.bugdigger.agent.collector.ClassCollector;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.TimeUnit;

/**
 * Embedded gRPC server that runs inside the target JVM.
 * Provides the BytesightAgent service for remote inspection and instrumentation.
 */
public class AgentGrpcServer {
    private static final Logger logger = LoggerFactory.getLogger(AgentGrpcServer.class);
    
    private final int port;
    private final Server server;
    
    public AgentGrpcServer(int port, Instrumentation instrumentation, ClassCollector classCollector) {
        this.port = port;
        
        // Create the service implementation
        BytesightAgentService service = new BytesightAgentService(instrumentation, classCollector);
        
        // Build the gRPC server
        this.server = ServerBuilder.forPort(port)
            .addService(service)
            .build();
    }
    
    /**
     * Start the gRPC server.
     */
    public void start() throws IOException {
        server.start();
        logger.info("[Bytesight] gRPC server started on port {}", port);
    }
    
    /**
     * Stop the gRPC server gracefully.
     */
    public void stop() {
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
                logger.info("[Bytesight] gRPC server stopped");
            } catch (InterruptedException e) {
                logger.warn("[Bytesight] gRPC server shutdown interrupted");
                server.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Block until the server is terminated.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }
    
    public int getPort() {
        return port;
    }
    
    public boolean isRunning() {
        return server != null && !server.isShutdown();
    }
}
