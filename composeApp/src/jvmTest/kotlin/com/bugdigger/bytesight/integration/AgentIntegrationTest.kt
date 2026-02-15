package com.bugdigger.bytesight.integration

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.core.decompiler.VineflowerDecompiler
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.condition.EnabledIf
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the Bytesight agent attachment and communication.
 * 
 * These tests:
 * 1. Start the sample application with the Bytesight agent attached
 * 2. Connect to the agent via gRPC
 * 3. Test various agent operations (list classes, get bytecode, etc.)
 * 4. Test decompilation of actual classes from the sample app
 * 
 * Prerequisites:
 * - Agent JAR must be built: ./gradlew :agent:agentJar
 * - Sample JAR must be built: ./gradlew :sample:jar
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@EnabledIf("isIntegrationTestEnabled")
class AgentIntegrationTest {

    companion object {
        private const val AGENT_PORT = 50099 // Use a different port to avoid conflicts
        private const val CONNECTION_KEY = "localhost:$AGENT_PORT"
        
        private val projectRoot: File by lazy {
            // Navigate from test class location to project root
            var dir = File(System.getProperty("user.dir"))
            while (dir.parentFile != null && !File(dir, "settings.gradle.kts").exists()) {
                dir = dir.parentFile
            }
            dir
        }
        
        private val agentJarPath: String by lazy {
            File(projectRoot, "agent/build/libs").listFiles()
                ?.find { it.name.endsWith("-agent.jar") }
                ?.absolutePath
                ?: File(projectRoot, "agent/build/libs/agent-1.0.0-agent.jar").absolutePath
        }
        
        private val sampleJarPath: String by lazy {
            File(projectRoot, "sample/build/libs").listFiles()
                ?.find { it.name.endsWith(".jar") && !it.name.contains("-all") }
                ?.absolutePath
                ?: File(projectRoot, "sample/build/libs/sample-0.1.0-SNAPSHOT.jar").absolutePath
        }
        
        @JvmStatic
        fun isIntegrationTestEnabled(): Boolean {
            // Check if required JARs exist
            val agentExists = File(agentJarPath).exists()
            val sampleExists = File(sampleJarPath).exists()
            
            if (!agentExists) {
                System.err.println("Integration tests skipped: Agent JAR not found at $agentJarPath")
                System.err.println("Run: ./gradlew :agent:agentJar")
            }
            if (!sampleExists) {
                System.err.println("Integration tests skipped: Sample JAR not found at $sampleJarPath")
                System.err.println("Run: ./gradlew :sample:jar")
            }
            
            return agentExists && sampleExists
        }
    }
    
    private var sampleProcess: Process? = null
    private lateinit var agentClient: AgentClient
    private val decompiler = VineflowerDecompiler()
    
    @BeforeAll
    fun setUp() {
        println("=== Integration Test Setup ===")
        println("Project root: $projectRoot")
        println("Agent JAR: $agentJarPath")
        println("Sample JAR: $sampleJarPath")
        
        // Start sample application with agent attached
        startSampleWithAgent()
        
        // Initialize agent client
        agentClient = AgentClient()
    }
    
    @AfterAll
    fun tearDown() {
        println("=== Integration Test Teardown ===")
        
        // Disconnect from agent
        if (::agentClient.isInitialized) {
            agentClient.disconnectAll()
        }
        
        // Stop sample process
        stopSampleProcess()
    }
    
    private fun startSampleWithAgent() {
        println("Starting sample application with agent...")
        
        val javaHome = System.getProperty("java.home")
        val javaExe = if (System.getProperty("os.name").lowercase().contains("win")) {
            "$javaHome/bin/java.exe"
        } else {
            "$javaHome/bin/java"
        }
        
        val processBuilder = ProcessBuilder(
            javaExe,
            "-javaagent:$agentJarPath=port=$AGENT_PORT",
            "-jar",
            sampleJarPath
        )
        
        processBuilder.directory(projectRoot)
        processBuilder.redirectErrorStream(true)
        
        // Inherit output for debugging
        processBuilder.inheritIO()
        
        sampleProcess = processBuilder.start()
        
        // Wait for agent to start (check for gRPC server availability)
        println("Waiting for agent to initialize...")
        Thread.sleep(3000) // Give the agent time to start
        
        assertTrue(sampleProcess?.isAlive == true, "Sample process should be running")
        println("Sample process started with PID: ${sampleProcess?.pid()}")
    }
    
    private fun stopSampleProcess() {
        sampleProcess?.let { process ->
            println("Stopping sample process...")
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
            println("Sample process stopped")
        }
        sampleProcess = null
    }
    
    // ==================== Connection Tests ====================
    
    @Test
    @Order(1)
    fun `should connect to agent successfully`() = runBlocking {
        val result = withTimeout(10.seconds) {
            agentClient.connect(port = AGENT_PORT)
        }
        
        assertTrue(result.isSuccess, "Should connect successfully: ${result.exceptionOrNull()?.message}")
        assertEquals(CONNECTION_KEY, result.getOrNull())
    }
    
    @Test
    @Order(2)
    fun `should get agent info after connection`() = runBlocking {
        val result = agentClient.getAgentInfo(CONNECTION_KEY)
        
        assertTrue(result.isSuccess, "Should get agent info: ${result.exceptionOrNull()?.message}")
        
        val info = result.getOrThrow()
        assertTrue(info.agentVersion.isNotEmpty(), "Agent version should not be empty")
        assertTrue(info.targetPid > 0, "Target PID should be positive")
        
        println("Agent Info:")
        println("  Version: ${info.agentVersion}")
        println("  Target PID: ${info.targetPid}")
        println("  Java Version: ${info.targetJvmVersion}")
    }
    
    // ==================== Class Listing Tests ====================
    
    @Test
    @Order(3)
    fun `should list loaded classes`() = runBlocking {
        val result = agentClient.listClasses(
            connectionKey = CONNECTION_KEY,
            includeSystemClasses = false
        )
        
        assertTrue(result.isSuccess, "Should list classes: ${result.exceptionOrNull()?.message}")
        
        val classes = result.getOrThrow()
        assertTrue(classes.isNotEmpty(), "Should have loaded classes")
        
        println("Loaded ${classes.size} non-system classes")
    }
    
    @Test
    @Order(4)
    fun `should find sample application classes`() = runBlocking {
        val result = agentClient.listClasses(
            connectionKey = CONNECTION_KEY,
            packageFilter = "com.bugdigger.sample",
            includeSystemClasses = false
        )
        
        assertTrue(result.isSuccess, "Should list sample classes: ${result.exceptionOrNull()?.message}")
        
        val classes = result.getOrThrow()
        assertTrue(classes.isNotEmpty(), "Should find sample classes")
        
        val classNames = classes.map { it.name }
        println("Found ${classes.size} sample classes:")
        classNames.forEach { name -> println("  - $name") }
        
        // Verify expected classes are present
        assertTrue(
            classNames.any { name -> name.contains("SampleApplication") },
            "Should find SampleApplication class"
        )
        assertTrue(
            classNames.any { name -> name.contains("UserService") },
            "Should find UserService class"
        )
        assertTrue(
            classNames.any { name -> name.contains("ProductService") },
            "Should find ProductService class"
        )
    }
    
    @Test
    @Order(5)
    fun `should list classes with system classes included`() = runBlocking {
        val withSystem = agentClient.listClasses(
            connectionKey = CONNECTION_KEY,
            includeSystemClasses = true
        )
        
        val withoutSystem = agentClient.listClasses(
            connectionKey = CONNECTION_KEY,
            includeSystemClasses = false
        )
        
        assertTrue(withSystem.isSuccess && withoutSystem.isSuccess)
        
        val systemCount = withSystem.getOrThrow().size
        val nonSystemCount = withoutSystem.getOrThrow().size
        
        assertTrue(
            systemCount > nonSystemCount,
            "Should have more classes when including system classes ($systemCount vs $nonSystemCount)"
        )
        
        println("Classes with system: $systemCount, without: $nonSystemCount")
    }
    
    // ==================== Bytecode Retrieval Tests ====================
    
    @Test
    @Order(6)
    fun `should get bytecode for SampleApplication class`() = runBlocking {
        val className = "com.bugdigger.sample.SampleApplication"
        val result = agentClient.getClassBytecode(CONNECTION_KEY, className)
        
        assertTrue(result.isSuccess, "Should get bytecode: ${result.exceptionOrNull()?.message}")
        
        val response = result.getOrThrow()
        assertTrue(response.found, "Bytecode retrieval should be successful")
        assertTrue(response.bytecode.size() > 0, "Bytecode should not be empty")
        
        println("Got ${response.bytecode.size()} bytes for $className")
    }
    
    @Test
    @Order(7)
    fun `should get bytecode for User model class`() = runBlocking {
        val className = "com.bugdigger.sample.models.User"
        val result = agentClient.getClassBytecode(CONNECTION_KEY, className)
        
        assertTrue(result.isSuccess, "Should get bytecode: ${result.exceptionOrNull()?.message}")
        
        val response = result.getOrThrow()
        assertTrue(response.found, "Bytecode retrieval should be successful")
        assertTrue(response.bytecode.size() > 0, "Bytecode should not be empty")
        
        println("Got ${response.bytecode.size()} bytes for $className")
    }
    
    @Test
    @Order(8)
    fun `should fail gracefully for non-existent class`() = runBlocking {
        val className = "com.nonexistent.FakeClass"
        val result = agentClient.getClassBytecode(CONNECTION_KEY, className)
        
        assertTrue(result.isSuccess, "Request should complete without exception")
        
        val response = result.getOrThrow()
        assertFalse(response.found, "Should not find non-existent class")
        assertTrue(response.error.isNotEmpty(), "Should have error message")
        
        println("Expected error for non-existent class: ${response.error}")
    }
    
    // ==================== Decompilation Tests ====================
    
    @Test
    @Order(9)
    fun `should decompile SampleApplication class`() = runBlocking {
        val className = "com.bugdigger.sample.SampleApplication"
        
        // Get bytecode from agent
        val bytecodeResult = agentClient.getClassBytecode(CONNECTION_KEY, className)
        assertTrue(bytecodeResult.isSuccess)
        
        val bytecode = bytecodeResult.getOrThrow().bytecode.toByteArray()
        
        // Decompile
        val decompileResult = decompiler.decompile(className, bytecode)
        
        assertTrue(decompileResult is DecompilationResult.Success, "Decompilation should succeed")
        
        val source = (decompileResult as DecompilationResult.Success).sourceCode
        assertTrue(source.isNotEmpty(), "Source code should not be empty")
        
        // Verify expected content
        assertTrue(source.contains("class SampleApplication"), "Should contain class declaration")
        assertTrue(source.contains("UserService"), "Should reference UserService")
        assertTrue(source.contains("ProductService"), "Should reference ProductService")
        assertTrue(source.contains("start"), "Should contain start method")
        assertTrue(source.contains("stop"), "Should contain stop method")
        
        println("Successfully decompiled SampleApplication (${source.length} chars)")
        println("First 500 chars:\n${source.take(500)}...")
    }
    
    @Test
    @Order(10)
    fun `should decompile UserService with lambda`() = runBlocking {
        val className = "com.bugdigger.sample.services.UserService"
        
        // Get bytecode from agent
        val bytecodeResult = agentClient.getClassBytecode(CONNECTION_KEY, className)
        assertTrue(bytecodeResult.isSuccess)
        
        val bytecode = bytecodeResult.getOrThrow().bytecode.toByteArray()
        
        // Decompile
        val decompileResult = decompiler.decompile(className, bytecode)
        
        assertTrue(decompileResult is DecompilationResult.Success, "Decompilation should succeed")
        
        val source = (decompileResult as DecompilationResult.Success).sourceCode
        assertTrue(source.isNotEmpty(), "Source code should not be empty")
        
        // Verify class structure
        assertTrue(source.contains("class UserService"), "Should contain class declaration")
        assertTrue(source.contains("createUser"), "Should contain createUser method")
        
        println("Successfully decompiled UserService (${source.length} chars)")
    }
    
    @Test
    @Order(11)
    fun `should decompile enum class`() = runBlocking {
        val className = "com.bugdigger.sample.models.Status"
        
        // Get bytecode from agent
        val bytecodeResult = agentClient.getClassBytecode(CONNECTION_KEY, className)
        assertTrue(bytecodeResult.isSuccess)
        
        val bytecode = bytecodeResult.getOrThrow().bytecode.toByteArray()
        
        // Decompile
        val decompileResult = decompiler.decompile(className, bytecode)
        
        assertTrue(decompileResult is DecompilationResult.Success, "Decompilation should succeed")
        
        val source = (decompileResult as DecompilationResult.Success).sourceCode
        
        // Verify enum structure
        assertTrue(source.contains("enum") || source.contains("Status"), "Should contain enum declaration")
        
        println("Successfully decompiled Status enum (${source.length} chars)")
    }
    
    // ==================== Hook Tests (if implemented) ====================
    
    @Test
    @Order(20)
    fun `should list hooks (initially empty)`() = runBlocking {
        val result = agentClient.listHooks(CONNECTION_KEY)
        
        assertTrue(result.isSuccess, "Should list hooks: ${result.exceptionOrNull()?.message}")
        
        val response = result.getOrThrow()
        // Initially should have no hooks
        println("Active hooks: ${response.hooksList.size}")
    }
}
