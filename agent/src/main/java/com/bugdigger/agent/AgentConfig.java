package com.bugdigger.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * Configuration for the Bytesight agent.
 * Parsed from the agent arguments string.
 * 
 * Format: key1=value1,key2=value2
 * Example: port=50051,debug=true
 */
public class AgentConfig {
    private static final int DEFAULT_PORT = 50051;
    
    private final int port;
    private final boolean debug;
    
    private AgentConfig(int port, boolean debug) {
        this.port = port;
        this.debug = debug;
    }
    
    public int getPort() {
        return port;
    }
    
    public boolean isDebug() {
        return debug;
    }
    
    /**
     * Parse agent arguments string into config.
     * 
     * @param agentArgs Arguments string (may be null)
     * @return Parsed configuration
     */
    public static AgentConfig parse(String agentArgs) {
        Map<String, String> args = parseArgs(agentArgs);
        
        int port = DEFAULT_PORT;
        if (args.containsKey("port")) {
            try {
                port = Integer.parseInt(args.get("port"));
            } catch (NumberFormatException e) {
                // Use default
            }
        }
        
        boolean debug = Boolean.parseBoolean(args.getOrDefault("debug", "false"));
        
        return new AgentConfig(port, debug);
    }
    
    private static Map<String, String> parseArgs(String agentArgs) {
        Map<String, String> result = new HashMap<>();
        
        if (agentArgs == null || agentArgs.isBlank()) {
            return result;
        }
        
        String[] pairs = agentArgs.split(",");
        for (String pair : pairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                result.put(keyValue[0].trim(), keyValue[1].trim());
            } else if (keyValue.length == 1) {
                // Flag without value, treat as true
                result.put(keyValue[0].trim(), "true");
            }
        }
        
        return result;
    }
    
    @Override
    public String toString() {
        return "AgentConfig{port=" + port + ", debug=" + debug + "}";
    }
}
