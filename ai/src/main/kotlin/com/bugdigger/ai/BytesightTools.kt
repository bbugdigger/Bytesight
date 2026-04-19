package com.bugdigger.ai

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet

/**
 * The set of tools the AI agent can call to inspect the target JVM and apply
 * user-visible changes in Bytesight. Each tool is a thin adapter over a method
 * on [BytesightAgentServices] that formats the result for the LLM.
 *
 * Register these with Koog like:
 * ```
 * val tools = BytesightTools(services)
 * ToolRegistry {
 *     tool(tools::listClasses)
 *     tool(tools::decompileClass)
 *     // …
 * }
 * ```
 *
 * Return types are plain `String` because the LLM consumes them as text. Long-form
 * data is truncated by the `limit` arguments so the context window stays usable.
 */
class BytesightTools(private val services: BytesightAgentServices) : ToolSet {

    @Tool
    @LLMDescription(
        "List classes loaded in the attached target JVM. Use this first to discover " +
            "what is in the process. Supports a case-insensitive substring filter over " +
            "the fully qualified class name."
    )
    suspend fun listClasses(
        @LLMDescription("Case-insensitive substring to filter FQNs. Empty means no filter.")
        filter: String = "",
        @LLMDescription("If true, include JDK/system classes. Default false.")
        includeSystem: Boolean = false,
        @LLMDescription("Maximum number of results. Keep small (<=200) to avoid flooding context.")
        limit: Int = 100,
    ): String {
        if (!services.isConnected()) return NOT_CONNECTED
        val classes = services.listClasses(filter, includeSystem, limit)
        if (classes.isEmpty()) return "No matching classes."
        return buildString {
            appendLine("Found ${classes.size} classes:")
            classes.forEach { c ->
                val tags = buildList {
                    if (c.isInterface) add("interface")
                    if (c.isAbstract) add("abstract")
                }.joinToString(",").let { if (it.isEmpty()) "" else " [$it]" }
                appendLine("- ${c.name}$tags")
            }
        }
    }

    @Tool
    @LLMDescription(
        "Decompile a single class to Java-like source code. Use this to understand " +
            "what a class does. If the user has applied renames, they appear in the source."
    )
    suspend fun decompileClass(
        @LLMDescription("Fully qualified class name, e.g. com.example.Foo")
        className: String,
    ): String {
        if (!services.isConnected()) return NOT_CONNECTED
        val source = services.decompileClass(className)
        return if (source.isBlank()) "Class not found or decompilation failed: $className" else source
    }

    @Tool
    @LLMDescription(
        "Get structural information about a class: super type, implemented interfaces, " +
            "methods with descriptors, and fields with types. Cheaper than decompile_class " +
            "when you only need the shape of the class."
    )
    suspend fun getClassInfo(
        @LLMDescription("Fully qualified class name, e.g. com.example.Foo")
        className: String,
    ): String {
        if (!services.isConnected()) return NOT_CONNECTED
        val info = services.getClassInfo(className) ?: return "Class not found: $className"
        return buildString {
            appendLine("Class: ${info.name}")
            info.superName?.let { appendLine("Super: $it") }
            if (info.interfaces.isNotEmpty()) {
                appendLine("Interfaces: ${info.interfaces.joinToString(", ")}")
            }
            if (info.fields.isNotEmpty()) {
                appendLine("Fields:")
                info.fields.forEach { f ->
                    appendLine("  ${if (f.isStatic) "static " else ""}${f.name}: ${f.descriptor}")
                }
            }
            if (info.methods.isNotEmpty()) {
                appendLine("Methods:")
                info.methods.forEach { m ->
                    appendLine("  ${if (m.isStatic) "static " else ""}${m.name}${m.descriptor}")
                }
            }
        }
    }

    @Tool
    @LLMDescription(
        "Search for string constants across loaded classes. Useful for locating " +
            "crypto keys, URLs, log messages, error strings, SQL, protocol tokens, etc."
    )
    suspend fun searchStrings(
        @LLMDescription("Case-insensitive substring to search for.")
        query: String,
        @LLMDescription("Maximum number of matches to return. Default 50.")
        limit: Int = 50,
    ): String {
        if (!services.isConnected()) return NOT_CONNECTED
        if (query.isBlank()) return "Query must not be blank."
        val matches = services.searchStrings(query, limit)
        if (matches.isEmpty()) return "No string matches for: $query"
        return buildString {
            appendLine("Found ${matches.size} string matches:")
            matches.forEach { m ->
                val escaped = m.value.replace("\n", "\\n").take(120)
                appendLine("- ${m.className}: \"$escaped\"")
            }
        }
    }

    @Tool
    @LLMDescription(
        "Get the most recent method-trace events captured by active hooks. Shows " +
            "which methods ran, in what order, with arguments/return values when " +
            "enabled. Returns empty if no hooks are active."
    )
    suspend fun getRecentTraces(
        @LLMDescription("Maximum number of events to return. Default 50.")
        limit: Int = 50,
    ): String {
        if (!services.isConnected()) return NOT_CONNECTED
        val traces = services.getRecentTraces(limit)
        if (traces.isEmpty()) return "No recent trace events. Add hooks in the Trace tab first."
        return buildString {
            appendLine("Recent trace events (oldest first):")
            traces.forEach { t ->
                val indent = "  ".repeat(t.depth.coerceIn(0, 10))
                val tail = buildString {
                    t.arguments?.let { append(" args=($it)") }
                    t.returnValue?.let { append(" ret=$it") }
                    t.durationNanos?.let { append(" ${it / 1_000}µs") }
                }
                appendLine("$indent[${t.eventType}] ${t.className}#${t.methodName}$tail")
            }
        }
    }

    @Tool
    @LLMDescription(
        "Apply a user-visible rename to an obfuscated symbol. Use fully qualified " +
            "names: 'com.example.A' for a class, 'com.example.A#b' for a method or " +
            "field (no descriptor needed for the display-layer rename)."
    )
    suspend fun renameSymbol(
        @LLMDescription("The original fully-qualified name to replace.")
        originalFqn: String,
        @LLMDescription("The new meaningful name. Must not be blank.")
        newName: String,
    ): String {
        if (newName.isBlank()) return "new_name must not be blank."
        services.renameSymbol(originalFqn, newName)
        return "Renamed $originalFqn → $newName"
    }

    @Tool
    @LLMDescription(
        "Apply many renames at once. Use this after analyzing a class and deciding on " +
            "meaningful names for all of its obfuscated members. Input is a pipe-separated " +
            "list of 'originalFqn=>newName' pairs — for example: " +
            "'com.a.b=>AuthService|com.a.b#a=>login|com.a.b#c=>logout'. " +
            "Blank entries are skipped. Prefer this over calling rename_symbol many times."
    )
    suspend fun batchRename(
        @LLMDescription(
            "Pipe-separated pairs of 'originalFqn=>newName'. Use '#' to denote method/field " +
                "on a class: 'com.a.b#fieldName=>NewName'."
        )
        renames: String,
    ): String {
        if (renames.isBlank()) return "renames must not be blank."
        val parsed = renames.split('|')
            .mapNotNull { entry ->
                val parts = entry.split("=>", limit = 2)
                if (parts.size != 2) return@mapNotNull null
                val fqn = parts[0].trim()
                val newName = parts[1].trim()
                if (fqn.isEmpty() || newName.isEmpty()) null else fqn to newName
            }
            .toMap()
        if (parsed.isEmpty()) {
            return "No valid rename pairs parsed. Expected 'fqn=>newName' separated by '|'."
        }
        services.batchRename(parsed)
        return buildString {
            appendLine("Applied ${parsed.size} renames:")
            parsed.forEach { (old, new) -> appendLine("- $old → $new") }
        }
    }

    @Tool
    @LLMDescription(
        "Decompile multiple classes at once for cross-reference analysis. Use this " +
            "when a flow spans several classes (caller + callee, or a family of " +
            "related classes) and you need to read them together to understand the " +
            "behavior. Keep the list small (<=6) to stay within context."
    )
    suspend fun decompileClasses(
        @LLMDescription(
            "Comma-separated list of fully qualified class names, e.g. " +
                "'com.a.Foo,com.a.Bar,com.a.Baz'."
        )
        classNames: String,
    ): String {
        if (!services.isConnected()) return NOT_CONNECTED
        val names = classNames.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (names.isEmpty()) return "class_names must contain at least one FQN."
        val sources = services.decompileMultiple(names)
        if (sources.isEmpty()) return "No classes decompiled."
        return buildString {
            sources.forEach { (name, source) ->
                appendLine("===== $name =====")
                appendLine(source)
                appendLine()
            }
        }
    }

    @Tool
    @LLMDescription("List all renames the user (or you) have applied so far.")
    suspend fun getRenames(): String {
        val renames = services.getRenames()
        if (renames.isEmpty()) return "No renames yet."
        return buildString {
            appendLine("Current renames:")
            renames.forEach { (old, new) -> appendLine("- $old → $new") }
        }
    }

    @Tool
    @LLMDescription(
        "Summarize the heap: instance count and byte totals per class. Requires a " +
            "heap snapshot to have been captured (Heap tab). Use this to spot classes " +
            "with unexpected footprint or to find caches / leaks."
    )
    suspend fun getHeapHistogram(
        @LLMDescription("Case-insensitive class-name substring filter. Empty = no filter.")
        nameFilter: String = "",
        @LLMDescription("Maximum rows to return. Default 30.")
        limit: Int = 30,
    ): String {
        if (!services.isConnected()) return NOT_CONNECTED
        val rows = services.getHeapHistogram(nameFilter, limit)
        if (rows.isEmpty()) return "No heap data. Capture a heap snapshot in the Heap tab first."
        return buildString {
            appendLine("Heap histogram (top ${rows.size}):")
            rows.forEach { r ->
                appendLine("- ${r.className}: ${r.instanceCount} instances, ${r.totalBytes} bytes")
            }
        }
    }

    companion object {
        internal const val NOT_CONNECTED =
            "No target JVM is attached. Ask the user to attach to a process first."
    }
}
