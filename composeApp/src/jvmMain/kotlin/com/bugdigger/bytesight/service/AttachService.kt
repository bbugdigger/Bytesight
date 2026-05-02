package com.bugdigger.bytesight.service

import com.sun.tools.attach.VirtualMachine
import com.sun.tools.attach.VirtualMachineDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.jar.JarFile

/**
 * Service for discovering and attaching to running JVM processes.
 * Uses the JVM Attach API to inject the Bytesight agent into target processes.
 */
class AttachService {

    private val logger = LoggerFactory.getLogger(AttachService::class.java)

    /**
     * Represents a discovered JVM process.
     */
    data class JvmProcess(
        val pid: String,
        val displayName: String,
        val isAttachable: Boolean = true,
    )

    /**
     * Lists all running JVM processes.
     */
    suspend fun listJvmProcesses(): List<JvmProcess> = withContext(Dispatchers.IO) {
        try {
            VirtualMachine.list().map { descriptor ->
                JvmProcess(
                    pid = descriptor.id(),
                    displayName = descriptor.displayName().ifEmpty { "<unknown>" },
                    isAttachable = isAttachable(descriptor),
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to list JVM processes", e)
            emptyList()
        }
    }

    /**
     * Attaches the Bytesight agent to the specified JVM process.
     *
     * @param pid The process ID of the target JVM
     * @param agentPort The port the agent's gRPC server should listen on
     * @return Result containing the port on success, or an error message on failure
     */
    suspend fun attachAgent(pid: String, agentPort: Int): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            logger.info("Attaching to JVM process: $pid on port $agentPort")

            val agentJarPath = resolveAgentJarPath()
            require(File(agentJarPath).exists()) { "Agent JAR not found at: $agentJarPath" }

            // Extract the debugger DLL once. The path goes to BOTH:
            //   (1) the Java agent as a `debuggerDllPath` arg, so it can
            //       System.load the file (binds JNI symbols), and
            //   (2) vm.loadAgentPath, which triggers Agent_OnAttach (where the
            //       Phase: onload-only JVMTI capabilities are acquired).
            // Same file path => one OS mapping; the JVM tracks two registrations.
            val dllPath: String? = runCatching { extractNativeDebuggerDll(agentJarPath) }
                .onFailure { logger.warn("Native debugger DLL not extracted: ${it.message}") }
                .getOrNull()

            val vm = VirtualMachine.attach(pid)
            try {
                val agentArgs = buildString {
                    append("port=").append(agentPort)
                    if (dllPath != null) append(",debuggerDllPath=").append(dllPath)
                }
                vm.loadAgent(agentJarPath, agentArgs)
                logger.info("Agent successfully attached to $pid")

                if (dllPath != null) {
                    runCatching {
                        logger.info("Loading native debugger helper into target: $dllPath")
                        vm.loadAgentPath(dllPath)
                        logger.info("Native debugger helper loaded (Agent_OnAttach succeeded)")
                    }.onFailure { logger.warn("loadAgentPath failed: ${it.message}") }
                }

                agentPort
            } finally {
                vm.detach()
            }
        }.onFailure { e ->
            logger.error("Failed to attach agent to $pid", e)
        }
    }

    /**
     * Resolves the path to the agent JAR file.
     * Looks in common locations relative to the application.
     */
    private fun resolveAgentJarPath(): String {
        // First, check system property
        val systemPath = System.getProperty("bytesight.agent.path", "")
        if (systemPath.isNotEmpty() && File(systemPath).exists()) {
            return normalizePathForAttachApi(File(systemPath))
        }

        // Development: look in agent build directory for *-agent.jar
        val devPaths = listOf(
            "agent/build/libs",
            "../agent/build/libs",
        )

        for (basePath in devPaths) {
            val dir = File(basePath)
            if (dir.exists() && dir.isDirectory) {
                val agentJar = dir.listFiles()?.find { it.name.endsWith("-agent.jar") }
                if (agentJar != null) {
                    val normalizedPath = normalizePathForAttachApi(agentJar)
                    logger.info("Found agent JAR: $normalizedPath")
                    return normalizedPath
                }
            }
        }

        // Production: bundled with application
        val prodPath = File("lib/agent.jar")
        if (prodPath.exists()) {
            return normalizePathForAttachApi(prodPath)
        }

        // Default fallback - return expected path for error message
        return "agent/build/libs/agent-*-agent.jar"
    }

    /**
     * Normalizes the file path for use with the JVM Attach API.
     * On Windows, the Attach API may have issues with backslashes,
     * so we convert to forward slashes for consistency.
     */
    private fun normalizePathForAttachApi(file: File): String {
        return file.absolutePath.replace('\\', '/')
    }

    private fun isAttachable(descriptor: VirtualMachineDescriptor): Boolean {
        return try {
            // Skip our own process
            val currentPid = ProcessHandle.current().pid().toString()
            descriptor.id() != currentPid
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Extracts the platform-specific debugger DLL from the agent JAR to a temp
     * file and returns its absolute path with forward-slash separators.
     */
    private fun extractNativeDebuggerDll(agentJarPath: String): String {
        val resourcePath = nativeDebuggerResourcePath()
            ?: error("Unsupported platform for native debugger helper")

        val jarFile = File(agentJarPath.replace('/', File.separatorChar))
        require(jarFile.exists()) { "Agent JAR missing: $jarFile" }

        val tempDll = JarFile(jarFile).use { jar ->
            val entry = jar.getJarEntry(resourcePath)
                ?: error("Agent JAR missing native helper: $resourcePath")
            val out = Files.createTempFile("bytesight_debugger_", suffixOf(resourcePath))
            out.toFile().deleteOnExit()
            jar.getInputStream(entry).use { Files.copy(it, out, StandardCopyOption.REPLACE_EXISTING) }
            out
        }

        return tempDll.toAbsolutePath().toString().replace('\\', '/')
    }

    private fun nativeDebuggerResourcePath(): String? {
        val os = System.getProperty("os.name", "").lowercase()
        val arch = System.getProperty("os.arch", "").lowercase()
        return when {
            os.contains("win") && (arch.contains("amd64") || arch.contains("x86_64") || arch.contains("x64")) ->
                "native/win-x64/bytesight_debugger.dll"
            else -> null
        }
    }

    private fun suffixOf(path: String): String {
        val dot = path.lastIndexOf('.')
        return if (dot < 0) ".lib" else path.substring(dot)
    }
}
