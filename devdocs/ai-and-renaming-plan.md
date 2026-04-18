# Bytesight — Symbol Renaming & AI Agent Plan

> Two new feature areas for Bytesight: (1) interactive IDA-style symbol renaming for
> reversing obfuscated programs, and (2) AI-assisted reverse engineering using JetBrains Koog.

---

## Phase 1: Symbol Renaming (IDA-Style)

### Problem

Obfuscated JVM applications (ProGuard, R8, custom obfuscators) produce classes and methods
with names like `a.b.c`, `a()`, `b()` that are meaningless. Reverse engineers need to
rename these symbols to understand the code — the same way IDA Pro lets you press "N" on
a symbol to rename it.

### Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Approach | Display-layer rename | No bytecode rewriting, works on any target, simple to implement |
| Persistence | Session-only initially | Keeps scope small; persisted project files come later |
| ASM rewriting | Out of scope | Future enhancement for exporting cleaned JARs |
| Mapping.txt import | Out of scope | Future enhancement once core rename UX is solid |

### Architecture

#### New: `RenameStore` (in `composeApp`)

```kotlin
// composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/RenameStore.kt

class RenameStore {
    // Key format: "com.example.ClassName" for classes,
    //             "com.example.ClassName#methodName(Ljava/lang/String;)V" for methods,
    //             "com.example.ClassName#fieldName" for fields
    private val _renames = MutableStateFlow<Map<String, String>>(emptyMap())
    val renameMap: StateFlow<Map<String, String>> = _renames.asStateFlow()

    fun rename(originalFqn: String, newName: String)
    fun removeRename(originalFqn: String)
    fun clearAll()

    // Apply renames to decompiled source text
    fun applyToSource(source: String): String
}
```

Registered as a Koin singleton so all ViewModels share the same rename state.

#### Modified: `CodeViewer` → Interactive

The current `CodeViewer` is read-only text with syntax highlighting. It needs to become
interactive:

1. **Symbol detection** — Parse decompiled source to identify clickable symbols (class
   names, method names, field names). Use regex or a simple Java identifier parser on
   the decompiled output.
2. **Click handling** — On click, highlight the symbol. On pressing "N" (or via right-click
   context menu → "Rename Symbol..."), show a rename dialog.
3. **Rename overlay** — After rename, `RenameStore.applyToSource()` re-processes the
   decompiled text and the view updates reactively via `StateFlow`.
4. **Visual indicator** — Renamed symbols shown in a distinct color (e.g., cyan) so user
   knows which names are original vs. user-assigned.

#### Modified: `InspectorViewModel`

- Collect `RenameStore.renameMap` and re-apply to `decompiledSource` whenever renames change.
- Expose a `renameSymbol(originalFqn: String, newName: String)` function called by the UI.

#### Modified: Other Screens

- **Class Browser**: display renamed class names in the class list.
- **Trace view**: show renamed method names in trace events.
- **Hierarchy view**: show renamed class names in tree.
- **Strings view**: show renamed class/method names in locations.

### Implementation Tasks

**Task 1.1: Create `RenameStore`**
- Files: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/RenameStore.kt`
- Tests: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/RenameStoreTest.kt`
- Implement core rename map and `applyToSource()`.
- Test: rename a class, rename a method, apply to sample decompiled source, verify output.

**Task 1.2: Make `CodeViewer` interactive**
- Modify `CodeViewer.kt` to detect symbol boundaries in decompiled text.
- Add click-to-select and keyboard shortcut "N" for rename.
- Add right-click context menu with "Rename Symbol..." option.
- Show rename dialog (simple text input popup).
- Highlight renamed symbols in a distinct color.

**Task 1.3: Wire `RenameStore` into `InspectorViewModel`**
- Inject `RenameStore` via Koin.
- Combine `decompiledSource` with `renameMap` to produce display text.
- Expose rename action to UI.

**Task 1.4: Apply renames across all screens**
- Class Browser: show renamed class names.
- Trace view: show renamed method names in trace events.
- Hierarchy view: show renamed class names in tree.
- Strings view: show renamed class/method names in locations.

---

## Phase 2: AI-Assisted Reverse Engineering (Koog)

### Problem

Reverse engineering is slow and requires domain expertise. An AI agent with access to
Bytesight's analysis tools can accelerate common tasks: explaining obfuscated code,
suggesting meaningful names, identifying patterns, and guiding the user through
complex binaries.

### Design Decisions

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Framework | JetBrains Koog (v0.8.0) | Kotlin-native, tool-calling, multi-provider, JetBrains maintained |
| Default LLM provider | OpenRouter | One API key, access to Claude/GPT/Gemini/DeepSeek, user picks model |
| Module | New `ai` module | Isolates Koog dependencies, clean separation from UI |
| UI | Dedicated AI tab + contextual entry points | Chat for open-ended queries; right-click for targeted tasks |
| Persistence | Chat history session-only initially | Same approach as renames |

### Architecture

#### New module: `ai`

```
ai/
├── build.gradle.kts
└── src/main/kotlin/com/bugdigger/ai/
    ├── BytesightAgent.kt      # Main agent definition with tools
    ├── BytesightTools.kt      # Tool definitions wrapping Bytesight services
    ├── AgentConfig.kt         # LLM provider config (API key, model, etc.)
    └── prompts/
        └── SystemPrompts.kt   # System prompts for RE tasks
```

Dependencies:
```kotlin
// ai/build.gradle.kts
dependencies {
    implementation("ai.koog:koog-agents:0.8.0")
    implementation(project(":protocol"))
    implementation(project(":core"))
}
```

#### Agent Tools

The AI agent's power comes from having Bytesight's features as callable tools:

| Tool | Description | Maps To |
|------|-------------|---------|
| `list_classes` | List loaded classes with optional filter | `AgentClient.listClasses()` |
| `decompile_class` | Decompile a class and return source | `getClassBytecode()` → `Decompiler.decompile()` |
| `get_class_info` | Get methods, fields, supers for a class | `AgentClient.listClasses()` filtered |
| `search_strings` | Search extracted string constants | `ConstantExtractor` |
| `get_traces` | Get recent method trace events | `AgentClient.streamTraceEvents()` |
| `rename_symbol` | Rename an obfuscated symbol | `RenameStore.rename()` |
| `get_renames` | Get current rename map | `RenameStore.renameMap` |
| `get_hierarchy` | Get class hierarchy for a class | Hierarchy analysis |
| `get_heap_info` | Get heap histogram or object details | Heap snapshot APIs |

#### System Prompt

```kotlin
object SystemPrompts {
    val REVERSE_ENGINEERING = """
        You are a JVM reverse engineering assistant embedded in Bytesight.
        You help users understand obfuscated Java/Kotlin bytecode by:
        - Decompiling classes and explaining their purpose
        - Suggesting meaningful names for obfuscated symbols
        - Identifying design patterns, crypto algorithms, network protocols
        - Tracing data flow through method calls
        - Flagging suspicious or interesting code patterns

        You have access to tools that let you inspect the running JVM:
        decompile classes, list loaded classes, search strings, read traces,
        rename symbols, and inspect the heap.

        When suggesting renames, use the rename_symbol tool to apply them.
        Always explain your reasoning.
    """.trimIndent()
}
```

#### UI: AI Screen (`composeApp`)

```
composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/ai/
├── AIScreen.kt           # Chat interface composable
├── AIViewModel.kt        # Manages chat state, delegates to BytesightAgent
└── AIMessageBubble.kt    # Renders individual messages with code blocks
```

- New `Screen.AI` entry in navigation enum (sparkle/brain icon).
- Chat interface: message list (scrollable), text input at bottom, send button.
- Messages with markdown support (code blocks reuse `JavaSyntaxHighlighter`).
- "Thinking" indicator while agent processes.
- Tool call visibility: show which tools the agent called (collapsible per message).

#### Contextual Entry Points

Other screens get "Ask AI" buttons that pre-populate the AI chat:

| Screen | Action | Pre-populated Prompt |
|--------|--------|---------------------|
| Inspector | "AI: Explain this class" | "Explain what the class `{className}` does based on its decompiled source" |
| Inspector | "AI: Suggest renames" | "Analyze class `{className}` and suggest meaningful names for all obfuscated symbols" |
| Trace | "AI: Explain this trace" | "Explain this method call trace: {traceData}" |
| Class Browser | "AI: Analyze selection" | "Analyze these classes and describe their relationships: {classList}" |

Clicking these navigates to the AI tab with the prompt pre-filled and auto-sent.

#### Settings Integration

Add to `SettingsScreen`:
- **AI Provider**: dropdown (OpenRouter / OpenAI / Anthropic / Ollama)
- **API Key**: password field
- **Model**: dropdown populated based on provider
- **Temperature**: slider (0.0–1.0, default 0.3 for analytical tasks)

Config stored in `AgentConfig` data class, persisted via preferences.

### Implementation Tasks

**Task 2.1: Create `ai` module skeleton**
- Create `ai/build.gradle.kts` with Koog dependency.
- Add to `settings.gradle.kts`.
- Create `AgentConfig` data class.
- Verify: `.\gradlew.bat :ai:build`

**Task 2.2: Define Bytesight tools for the agent**
- Create `BytesightTools.kt` with all tool definitions.
- Each tool wraps an existing Bytesight service method.
- Unit test: mock services, verify tool calls produce expected output.

**Task 2.3: Create `BytesightAgent`**
- Wire tools + system prompt + LLM config.
- Create agent with Koog `AIAgent` builder.
- Test with mock executor to verify tool routing.

**Task 2.4: Create `AIViewModel` + `AIScreen`**
- `AIViewModel`: chat message list as `StateFlow`, `sendMessage()` function.
- `AIScreen`: chat UI with message bubbles, input field, tool call display.
- Register in Koin, add to navigation.

**Task 2.5: Add AI configuration to Settings**
- Provider selection, API key input, model selection, temperature.
- Store in `AgentConfig`, wire to `BytesightAgent`.

**Task 2.6: Add contextual entry points**
- "Ask AI" buttons on Inspector, Trace, Class Browser screens.
- Navigation to AI tab with pre-populated prompt.

**Task 2.7: Enhance agent with RE-specific capabilities**
- Batch rename suggestions (agent analyzes a class, suggests all renames, applies via tool).
- Pattern detection prompts (crypto, networking, serialization).
- Cross-reference analysis (agent decompiles multiple related classes to understand flow).

---

## Dependency Order

```
Phase 1 (Renaming)           Phase 2 (AI Agent)
┌─────────────────┐          ┌─────────────────┐
│ Task 1.1        │          │ Task 2.1        │
│ RenameStore     │──────┐   │ ai module       │
└────────┬────────┘      │   └────────┬────────┘
         │               │            │
┌────────┴────────┐      │   ┌────────┴────────┐
│ Task 1.2        │      │   │ Task 2.2        │
│ Interactive View│      └──▶│ Tools (needs    │
└────────┬────────┘          │  RenameStore)   │
         │                   └────────┬────────┘
┌────────┴────────┐          ┌────────┴────────┐
│ Task 1.3        │          │ Task 2.3        │
│ Inspector wire  │          │ Agent           │
└────────┬────────┘          └────────┬────────┘
         │                   ┌────────┴────────┐
┌────────┴────────┐          │ Task 2.4        │
│ Task 1.4        │          │ AI Screen       │
│ Cross-screen    │          └────────┬────────┘
└─────────────────┘          ┌────────┴────────┐
                             │ Task 2.5        │
                             │ Settings        │
                             └────────┬────────┘
                             ┌────────┴────────┐
                             │ Task 2.6        │
                             │ Entry points    │
                             └────────┬────────┘
                             ┌────────┴────────┐
                             │ Task 2.7        │
                             │ RE capabilities │
                             └─────────────────┘

Phase 2 depends on Phase 1 Task 1.1 (RenameStore must exist for the rename_symbol tool).
Tasks 2.1–2.3 can begin once Task 1.1 is done.
```

---

## Future Considerations

1. **ProGuard mapping.txt import/export** — Bulk rename via mapping file once core UX is solid.
2. **ASM rewriting for JAR export** — Use ASM `ClassRemapper` to produce cleaned JARs with
   renames baked into bytecode.
3. **Rename persistence** — JSON project file (`*.bytesight`) to save/load rename maps,
   comments, and AI chat history.
4. **Multi-agent workflows** — Koog supports graph-based agent workflows. An "auto-analyze"
   mode where the agent autonomously explores all classes and builds a rename map.
5. **Local models** — For sensitive RE work (malware analysis), Ollama support keeps
   everything offline.
6. **RAG on decompiled code** — Koog has RAG/embeddings support. Index all decompiled
   classes so the agent can search semantically ("find classes that do encryption").
