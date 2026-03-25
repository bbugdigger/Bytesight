# Bytesight Feature Roadmap — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Extend Bytesight from a class browser / method tracer into a full JVM
reverse-engineering workbench with 7 new capabilities, phased from easiest to hardest.

**Architecture:** Each feature adds a new screen (or panel) following the existing MVVM +
Koin pattern. New analysis modules live in `core` (pure Kotlin, no UI dependency). Heavy
bytecode work uses ASM directly; ByteBuddy stays in `agent` for runtime instrumentation
only. Graph rendering uses Compose Canvas.

**Tech Stack:** Kotlin (Compose Multiplatform), Java (agent), ASM 9.x (bytecode analysis),
Koin (DI), gRPC/Protobuf (agent communication), Compose Canvas (graph drawing).

---

## Phase Overview

| Phase | Feature                      | Effort   | New Module? | Depends On |
|-------|------------------------------|----------|-------------|------------|
| 1     | String / Constant Extraction | Easy     | No          | —          |
| 1     | Class Hierarchy Explorer     | Easy     | No          | —          |
| 2     | Bytecode-Level Inspector     | Medium   | No          | —          |
| 2     | JAR Deobfuscation            | Medium   | Yes (`deobfuscator`) | — |
| 3     | Bytecode Diff View           | Medium   | No          | Phase 2 (deobfuscation provides before/after) |
| 3     | Method Call Graph            | Med-Hard | No          | Phase 2 (bytecode parsing patterns) |
| 4     | Control Flow Graph Viewer    | Hard     | No          | Phase 3 (graph rendering patterns) |

---

## Phase 1 — Low-Hanging Fruit

These use data already available via the existing `GetClassBytecode` and `GetLoadedClasses`
RPCs. No agent changes required.

---

### Feature 1: String / Constant Extraction

**What:** Scan bytecode of loaded classes for hardcoded strings, numeric constants, class
references, URLs, file paths, and other embedded literals. Display them in a searchable,
filterable UI panel.

**Why:** One of the most common RE tasks. Immediately useful for finding API endpoints,
encryption keys, debug messages, hidden features.

**Architecture:**
- New Kotlin class `ConstantExtractor` in `core` module — parses bytecode using ASM's
  `ClassReader` + `ClassVisitor` to collect `LDC`, `LDC_W`, `LDC2_W` instructions and
  constant pool entries.
- New `StringsScreen` + `StringsViewModel` in `composeApp`.
- Reuses existing `agentClient.getClassBytecode()` to fetch raw bytes per class.

#### Data Model

```kotlin
// core/src/main/kotlin/com/bugdigger/core/analysis/ConstantExtractor.kt

data class ExtractedConstant(
    val value: Any,              // String, Int, Long, Float, Double, Type
    val type: ConstantType,      // STRING, INTEGER, LONG, FLOAT, DOUBLE, CLASS_REF, METHOD_HANDLE
    val className: String,       // Class where found
    val methodName: String?,     // Method where found (null if class-level)
    val location: String,        // Human-readable location "MyClass.doStuff()"
)

enum class ConstantType { STRING, INTEGER, LONG, FLOAT, DOUBLE, CLASS_REF, METHOD_HANDLE }

// Patterns for highlighting interesting strings
enum class StringPattern(val regex: Regex) {
    URL(Regex("https?://.*")),
    FILE_PATH(Regex("[/\\\\].*\\.[a-zA-Z]{2,4}")),
    IP_ADDRESS(Regex("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")),
    CRYPTO_KEY(Regex("[A-Fa-f0-9]{32,}")),
    // ... more patterns
}
```

#### Implementation Tasks

**Task 1.1: Create `ConstantExtractor` in `core` module**

Files:
- Create: `core/src/main/kotlin/com/bugdigger/core/analysis/ConstantExtractor.kt`
- Create: `core/src/test/kotlin/com/bugdigger/core/analysis/ConstantExtractorTest.kt`

Steps:
1. Add ASM dependency to `core/build.gradle.kts`: `implementation("org.ow2.asm:asm:9.7.1")`
2. Write test: feed known bytecode (compile a small class with strings/numbers), assert
   extracted constants match expected values.
3. Implement `ConstantExtractor` using `ClassReader` + custom `ClassVisitor` / `MethodVisitor`
   that overrides `visitLdcInsn()` and collects constants.
4. Run: `.\gradlew.bat :core:test --tests "*.ConstantExtractorTest"`

**Task 1.2: Create `StringsScreen` + `StringsViewModel`**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/strings/StringsViewModel.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/strings/StringsScreen.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Navigation.kt`
  — add `STRINGS` to `Screen` enum
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt`
  — register `StringsViewModel`

UI layout:
- Top bar: "Extract" button (scans all loaded classes), progress indicator, search bar.
- Filter chips: by type (Strings / Numbers / URLs / Class refs), by pattern match.
- Main table: columns for Value, Type, Pattern (URL/IP/etc.), Class, Method.
- Click row → navigate to ClassBrowser with that class selected.
- "Scan Selection" button to scan only filtered/selected classes.

State:
```kotlin
data class StringsUiState(
    val constants: List<ExtractedConstant> = emptyList(),
    val filteredConstants: List<ExtractedConstant> = emptyList(),
    val searchQuery: String = "",
    val typeFilter: Set<ConstantType> = ConstantType.entries.toSet(),
    val patternFilter: Set<StringPattern>? = null, // null = all
    val isExtracting: Boolean = false,
    val progress: Float = 0f,  // 0..1
    val error: String? = null,
)
```

**Task 1.3: Write ViewModel tests**

File: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/strings/StringsViewModelTest.kt`

Test: initial state, search filtering, type filtering, error handling, progress updates.
Run: `.\gradlew.bat :composeApp:jvmTest --tests "*.StringsViewModelTest"`

---

### Feature 2: Class Hierarchy Explorer

**What:** Interactive tree/graph view showing inheritance relationships (extends/implements)
across all loaded classes. Click a class to see its full hierarchy chain.

**Why:** Essential for understanding OOP structure of an application, finding implementations
of interfaces, spotting proxy/wrapper patterns.

**Architecture:**
- `ClassInfo` already contains `superclass: string` and `interfaces: repeated string` in the
  proto — no agent changes needed.
- New `HierarchyScreen` + `HierarchyViewModel` in `composeApp`.
- Build a tree data structure from the flat class list on the client side.
- Render as a collapsible tree (like a file explorer) + optional graph view.

#### Data Model

```kotlin
data class HierarchyNode(
    val classInfo: ClassInfo?,     // null if class not loaded (e.g. java.lang.Object)
    val className: String,
    val children: List<HierarchyNode>,   // subclasses / implementors
    val isInterface: Boolean,
)

data class HierarchyUiState(
    val roots: List<HierarchyNode> = emptyList(),  // Top-level nodes (no parent loaded)
    val selectedClass: String? = null,
    val selectedAncestors: List<String> = emptyList(),  // Upward chain to Object
    val searchQuery: String = "",
    val showInterfaces: Boolean = true,
    val showClasses: Boolean = true,
    val isLoading: Boolean = false,
    val error: String? = null,
)
```

#### Implementation Tasks

**Task 2.1: Build hierarchy tree from ClassInfo list**

This is a pure function — given `List<ClassInfo>`, return `List<HierarchyNode>`.

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyBuilder.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyBuilderTest.kt`

Algorithm:
1. Index all classes by full name into a map.
2. For each class, register it as a child of its `superclass` and each of its `interfaces`.
3. Roots = classes whose superclass is not in the loaded class set (typically `java.lang.Object`
   subclasses).
4. Build tree recursively.

Test: create mock `ClassInfo` objects with known hierarchy, assert tree structure is correct.
Run: `.\gradlew.bat :composeApp:jvmTest --tests "*.HierarchyBuilderTest"`

**Task 2.2: Create `HierarchyScreen` + `HierarchyViewModel`**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyViewModel.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyScreen.kt`
- Modify: `Navigation.kt` — add `HIERARCHY` to `Screen` enum
- Modify: `Sidebar.kt` — add sidebar entry (gated by `isConnected`)
- Modify: `AppModule.kt` — register `HierarchyViewModel`

UI layout:
- Left panel: collapsible tree view using `LazyColumn` with indentation. Icons: 📦 class,
  🔷 interface, 📋 enum. Expand/collapse arrows.
- Right panel: selected class detail — full ancestor chain (upward to Object), list of
  direct subclasses, fields/methods summary.
- Search bar: filter the tree to show only branches containing the search term.
- Toggle: "Show interfaces" / "Show abstract classes" checkboxes.

**Task 2.3: Write ViewModel tests**

File: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyViewModelTest.kt`

Run: `.\gradlew.bat :composeApp:jvmTest --tests "*.HierarchyViewModelTest"`

---

## Phase 2 — Bytecode Analysis

These features add deeper bytecode analysis capabilities and a new module.

---

### Feature 3: Bytecode-Level Inspector

**What:** A raw bytecode disassembly view showing JVM instructions alongside the decompiled
Java source. Think of `javap -c` output but interactive — click an instruction to highlight
the corresponding decompiled line, and vice versa.

**Why:** Decompiled source can be misleading (decompiler guesses, lost information). The raw
bytecode is the ground truth. Needed for understanding obfuscated code, compiler quirks,
and verifying decompiler output.

**Architecture:**
- New `BytecodeDisassembler` in `core` module — uses ASM to produce a structured list of
  instructions per method (not just a text dump).
- New `InspectorScreen` with split-pane layout: bytecode on left, decompiled source on right.
- Reuses existing `CodeViewer` for the decompiled side.

#### Data Model

```kotlin
data class DisassembledClass(
    val className: String,
    val majorVersion: Int,
    val minorVersion: Int,
    val accessFlags: Int,
    val constantPool: List<ConstantPoolEntry>,
    val methods: List<DisassembledMethod>,
    val fields: List<FieldSummary>,
)

data class DisassembledMethod(
    val name: String,
    val descriptor: String,
    val accessFlags: Int,
    val instructions: List<Instruction>,
    val maxStack: Int,
    val maxLocals: Int,
    val tryCatchBlocks: List<TryCatchBlock>,
    val localVariables: List<LocalVariable>,
)

data class Instruction(
    val offset: Int,          // bytecode offset
    val opcode: Int,          // JVM opcode
    val mnemonic: String,     // "INVOKEVIRTUAL", "ALOAD", etc.
    val operands: String,     // Human-readable operand string
    val lineNumber: Int?,     // Source line if available (from LineNumberTable)
)
```

#### Implementation Tasks

**Task 3.1: Create `BytecodeDisassembler` in `core`**

Files:
- Create: `core/src/main/kotlin/com/bugdigger/core/analysis/BytecodeDisassembler.kt`
- Create: `core/src/test/kotlin/com/bugdigger/core/analysis/BytecodeDisassemblerTest.kt`

Uses ASM `ClassReader` with a custom `MethodVisitor` that records each instruction.
ASM dependency should already be added in Phase 1. If not, add it here.

Test: compile a known Java class, disassemble it, assert instruction sequences.
Run: `.\gradlew.bat :core:test --tests "*.BytecodeDisassemblerTest"`

**Task 3.2: Create `InspectorScreen` + `InspectorViewModel`**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/inspector/InspectorViewModel.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/inspector/InspectorScreen.kt`
- Modify: `Navigation.kt`, `Sidebar.kt`, `AppModule.kt`

UI layout:
- Top bar: class selector dropdown (from loaded classes) + method selector dropdown.
- Split pane: left = bytecode instructions (`LazyColumn` of `Instruction` rows with offset,
  opcode, operands), right = decompiled source in `CodeViewer`.
- Instruction detail panel at bottom: when an instruction is selected, show operand details,
  stack effect (+N / -N), relevant constant pool entry.
- Color coding: control flow (red), method calls (blue), field access (green), load/store
  (gray).

---

### Feature 4: JAR Deobfuscation

**What:** Load a ProGuard mapping file and apply it to rename obfuscated classes, methods,
and fields — either on loaded classes (live) or on a JAR file (offline). Produces
deobfuscated bytecode that can then be decompiled.

**Why:** Core RE capability. We already generate obfuscated JARs in the `sample` module
with `mapping.txt` — built-in test case.

**Architecture:**
- New Gradle module: `deobfuscator` (pure Kotlin + ASM, no UI dependency).
- `MappingParser` — reads ProGuard `.txt` format into a structured mapping model.
- `ClassDeobfuscator` — uses ASM `ClassRemapper` + custom `Remapper` to rewrite all
  references.
- Integration in `composeApp`: new "Deobfuscate" action in ClassBrowser that applies
  mappings before decompilation.

**Note on ASM vs ByteBuddy:** We use ASM directly here, not ByteBuddy. ByteBuddy is for
runtime instrumentation (method interception, advice). ASM's `ClassRemapper` is purpose-built
for bulk renaming across type descriptors, signatures, annotations, etc. Both javgent and
Reconstruct use this approach.

#### Data Model

```kotlin
// deobfuscator module

data class ClassMapping(
    val obfuscatedName: String,    // e.g. "a.b.c"
    val originalName: String,      // e.g. "com.example.UserService"
    val methods: List<MethodMapping>,
    val fields: List<FieldMapping>,
)

data class MethodMapping(
    val obfuscatedName: String,
    val originalName: String,
    val descriptor: String,       // JVM descriptor for overload resolution
)

data class FieldMapping(
    val obfuscatedName: String,
    val originalName: String,
    val type: String,
)

data class MappingFile(
    val classMappings: Map<String, ClassMapping>,  // keyed by obfuscated name
)
```

#### Implementation Tasks

**Task 4.1: Create `deobfuscator` module**

Files:
- Create: `deobfuscator/build.gradle.kts`
- Modify: `settings.gradle.kts` — add `:deobfuscator` module

Dependencies: ASM (`org.ow2.asm:asm:9.7.1`, `org.ow2.asm:asm-commons:9.7.1`), SLF4J,
JUnit 5. No Kotlin Multiplatform — plain `kotlin-jvm` plugin. Target JVM 17.

**Task 4.2: Implement `ProGuardMappingParser`**

Files:
- Create: `deobfuscator/src/main/kotlin/com/bugdigger/deobfuscator/mapping/ProGuardMappingParser.kt`
- Create: `deobfuscator/src/test/kotlin/com/bugdigger/deobfuscator/mapping/ProGuardMappingParserTest.kt`

ProGuard format:
```
com.example.Original -> a.b.c:
    java.lang.String realField -> d
    void realMethod(java.lang.String) -> e
```

Test with the actual `sample/build/obfuscated/mapping.txt` if available, plus synthetic
test mappings.
Run: `.\gradlew.bat :deobfuscator:test --tests "*.ProGuardMappingParserTest"`

**Task 4.3: Implement `ClassDeobfuscator`**

Files:
- Create: `deobfuscator/src/main/kotlin/com/bugdigger/deobfuscator/transform/ClassDeobfuscator.kt`
- Create: `deobfuscator/src/test/kotlin/com/bugdigger/deobfuscator/transform/ClassDeobfuscatorTest.kt`

Implementation: Create a subclass of ASM's `Remapper` that looks up obfuscated → original
names from the `MappingFile`. Use `ClassReader` + `ClassWriter` + `ClassRemapper` to produce
renamed bytecode.

Two-pass approach (from javgent):
1. First pass: scan all classes to resolve hierarchy (superclass/interface chains).
2. Second pass: apply `ClassRemapper` with hierarchy-aware method resolution.

Test: obfuscate a class with known mapping, deobfuscate, verify names restored.
Run: `.\gradlew.bat :deobfuscator:test --tests "*.ClassDeobfuscatorTest"`

**Task 4.4: Integrate into `composeApp`**

Files:
- Modify: `composeApp/build.gradle.kts` — add `implementation(project(":deobfuscator"))`
- Modify: `ClassBrowserViewModel.kt` — add "Apply Mappings" action, store mapping state
- Modify: `ClassBrowserScreen.kt` — add "Load Mapping File" button + file picker dialog

Flow: User loads `.txt` mapping file → mappings stored in ViewModel → when selecting a class,
bytecode is deobfuscated before decompilation → decompiled source shows original names.

---

## Phase 3 — Comparison & Graph Analysis

These features build on Phase 2's bytecode parsing and introduce graph rendering.

---

### Feature 5: Bytecode Diff View

**What:** Side-by-side comparison of two versions of a class's decompiled source — for
example, original vs deobfuscated, or two snapshots taken at different times.

**Why:** Deobfuscation, instrumentation, and class redefinition all modify bytecode.
Seeing exactly what changed is essential.

**Architecture:**
- Simple text diff algorithm (Myers diff or patience diff) — can use an existing JVM
  library like `java-diff-utils`.
- New `DiffScreen` with dual `CodeViewer` panels and diff gutter markers (green = added,
  red = removed, yellow = changed).
- Source data comes from decompiling the same class with/without mappings applied.

#### Implementation Tasks

**Task 5.1: Add diff library dependency**

Add `io.github.java-diff-utils:java-diff-utils:4.12` to `composeApp` dependencies.

**Task 5.2: Create `DiffViewModel` + `DiffScreen`**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/DiffViewModel.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/diff/DiffScreen.kt`
- Modify: `Navigation.kt`, `Sidebar.kt`, `AppModule.kt`

State:
```kotlin
data class DiffUiState(
    val leftSource: String = "",
    val rightSource: String = "",
    val leftLabel: String = "Original",
    val rightLabel: String = "Modified",
    val diffLines: List<DiffLine> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

data class DiffLine(
    val leftLineNum: Int?,
    val rightLineNum: Int?,
    val leftText: String,
    val rightText: String,
    val type: DiffType,  // EQUAL, INSERT, DELETE, CHANGE
)
```

UI: Two `CodeViewer` panels with synchronized scrolling. Diff gutter between them
showing +/−/~ markers. "Compare" button to select two sources (e.g., "Obfuscated" vs
"Deobfuscated" of the same class).

---

### Feature 6: Method Call Graph

**What:** Static analysis of bytecode to build a caller → callee graph. Visualize which
methods call which other methods, rendered as an interactive directed graph.

**Why:** Essential for understanding program flow, finding entry points, tracing data paths,
and identifying dead code. Complements the existing dynamic method tracing.

**Architecture:**
- New `CallGraphAnalyzer` in `core` — scans bytecode for `INVOKE*` instructions (INVOKEVIRTUAL,
  INVOKESTATIC, INVOKEINTERFACE, INVOKESPECIAL, INVOKEDYNAMIC) and records edges.
- Graph data model: `CallGraph` with nodes (methods) and edges (calls).
- **Compose Canvas renderer** — this is the first feature that needs graph visualization.
  Build a reusable `GraphView` composable with force-directed or layered layout.

#### Data Model

```kotlin
data class CallGraph(
    val nodes: Set<MethodNode>,
    val edges: Set<CallEdge>,
)

data class MethodNode(
    val className: String,
    val methodName: String,
    val descriptor: String,
    val id: String = "$className.$methodName$descriptor",
)

data class CallEdge(
    val caller: MethodNode,
    val callee: MethodNode,
    val invokeType: InvokeType,  // VIRTUAL, STATIC, INTERFACE, SPECIAL, DYNAMIC
    val count: Int = 1,          // How many call sites
)
```

#### Implementation Tasks

**Task 6.1: Create `CallGraphAnalyzer` in `core`**

Files:
- Create: `core/src/main/kotlin/com/bugdigger/core/analysis/CallGraphAnalyzer.kt`
- Create: `core/src/test/kotlin/com/bugdigger/core/analysis/CallGraphAnalyzerTest.kt`

Uses ASM `MethodVisitor.visitMethodInsn()` and `visitInvokeDynamicInsn()` to record edges.

**Task 6.2: Build reusable `GraphView` composable**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/components/GraphView.kt`

This is a **reusable Compose Canvas component** that will also be used by the CFG viewer
in Phase 4. Features:
- Takes a generic graph (nodes with positions + edges) as input.
- Canvas-based rendering: rectangles for nodes, lines/arrows for edges.
- Pan and zoom (mouse drag + scroll wheel).
- Click-to-select nodes.
- Layout algorithm: start with a simple force-directed layout (Fruchterman-Reingold) or
  a top-down layered layout (Sugiyama).

**Task 6.3: Create `CallGraphScreen` + `CallGraphViewModel`**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/callgraph/CallGraphViewModel.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/callgraph/CallGraphScreen.kt`
- Modify: `Navigation.kt`, `Sidebar.kt`, `AppModule.kt`

UI:
- Class/method selector: choose a starting method or "scan all."
- Depth slider: how many levels of callers/callees to show (1–5).
- The `GraphView` composable renders the call graph.
- Click a node → show method details panel (signature, callers, callees).
- Filter: hide library calls, show only application classes.

---

## Phase 4 — Advanced Visualization

---

### Feature 7: Control Flow Graph Viewer

**What:** Visualize the control flow graph (CFG) of a method's bytecode as basic blocks
connected by edges. Each block shows a sequence of instructions; edges represent
branches, jumps, and fall-throughs.

**Why:** The most powerful view for understanding method logic — especially obfuscated
code where the decompiler struggles. CFGs are the standard representation in RE tools
(Ghidra, IDA Pro, Binary Ninja).

**Architecture:**
- New `CfgBuilder` in `core` — takes bytecode, identifies basic block leaders (targets of
  jumps, instructions after branches, exception handler starts), splits instruction stream
  into blocks, builds edge set.
- Reuses `GraphView` from Phase 3 (Feature 6), with a specialized `CfgLayout` that uses
  a **Sugiyama layered layout** (top-to-bottom, since CFGs have a natural direction).
- Optional: dominator tree overlay, loop highlighting.

#### Data Model

```kotlin
data class BasicBlock(
    val id: Int,
    val startOffset: Int,
    val endOffset: Int,
    val instructions: List<Instruction>,  // reuses from Feature 3
    val isEntryBlock: Boolean,
    val isExitBlock: Boolean,
    val isCatchBlock: Boolean,
)

data class CfgEdge(
    val from: BasicBlock,
    val to: BasicBlock,
    val type: CfgEdgeType,  // FALL_THROUGH, JUMP, BRANCH_TRUE, BRANCH_FALSE, EXCEPTION
)

data class ControlFlowGraph(
    val methodName: String,
    val blocks: List<BasicBlock>,
    val edges: List<CfgEdge>,
    val entryBlock: BasicBlock,
)
```

#### Implementation Tasks

**Task 7.1: Create `CfgBuilder` in `core`**

Files:
- Create: `core/src/main/kotlin/com/bugdigger/core/analysis/CfgBuilder.kt`
- Create: `core/src/test/kotlin/com/bugdigger/core/analysis/CfgBuilderTest.kt`

Algorithm:
1. First pass: identify leaders — offset 0, targets of all GOTO/IF_*/SWITCH/JSR, instruction
   after any branch/goto, exception handler start offsets.
2. Second pass: split instruction stream at leaders into `BasicBlock` objects.
3. Third pass: for each block's last instruction, determine successors (fall-through, jump
   target, both branches for conditionals, all cases for switch, exception handlers).

Test with methods containing: linear code, if/else, loops, try-catch, switch.
Run: `.\gradlew.bat :core:test --tests "*.CfgBuilderTest"`

**Task 7.2: Implement Sugiyama layout for CFG**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/components/SugiyamaLayout.kt`

The Sugiyama algorithm for layered graph drawing:
1. Assign layers (reverse topological sort / longest path).
2. Minimize edge crossings within layers (barycenter heuristic).
3. Assign x-coordinates within layers (spacing).
4. Route edges (orthogonal or straight lines).

This extends the `GraphView` from Feature 6 with a layout strategy specifically suited
to directed acyclic-ish graphs (CFGs can have back-edges for loops).

**Task 7.3: Create `CfgScreen` + `CfgViewModel`**

Files:
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/cfg/CfgViewModel.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/cfg/CfgScreen.kt`
- Modify: `Navigation.kt`, `Sidebar.kt`, `AppModule.kt`

UI:
- Method selector (class dropdown → method dropdown).
- `GraphView` with Sugiyama layout rendering basic blocks as rectangles containing
  instruction text, connected by arrows.
- Color code edges: green = fall-through, blue = jump/goto, orange = branch-true,
  red = branch-false, purple = exception.
- Color code blocks: gray = normal, yellow = loop header (has back-edge), red = catch block.
- Click block → highlight corresponding source lines in side panel.
- Side panel: decompiled source in `CodeViewer` for context.

---

## Cross-Cutting Concerns

### Navigation Updates

Each new screen requires:
1. Add enum entry to `Screen` in `Navigation.kt`
2. Add `NavigationRailItem` in `Sidebar.kt` (connection-gated)
3. Add `when` branch in the main navigation composable
4. Register ViewModel in `AppModule.kt`

Final sidebar order (9 screens):
`ATTACH` → `CLASS_BROWSER` → `HIERARCHY` → `INSPECTOR` → `STRINGS` → `CALL_GRAPH` → `CFG` → `TRACE` → `DIFF` → `SETTINGS`

### ASM Dependency

ASM is needed in `core` (Features 1, 3, 6, 7) and `deobfuscator` (Feature 4).
Add once per module:
```kotlin
implementation("org.ow2.asm:asm:9.7.1")
implementation("org.ow2.asm:asm-commons:9.7.1")  // for ClassRemapper in deobfuscator
implementation("org.ow2.asm:asm-util:9.7.1")      // optional, for Textifier debug output
```

### Reusable Components

- `GraphView` (built in Feature 6) is reused by Feature 7.
- `CodeViewer` (existing) is reused by Features 3, 5, 7.
- `ConstantExtractor` (Feature 1) and `BytecodeDisassembler` (Feature 3) share ASM
  visitor patterns — consider a shared base `BytecodeAnalyzer` if patterns converge.

### Testing Strategy

- **Unit tests** for all `core` and `deobfuscator` analysis classes (pure logic, no UI).
- **ViewModel tests** for all new ViewModels (MockK + `runBlocking`).
- **Integration test**: extend `AgentIntegrationTest` to test deobfuscation round-trip
  with the `sample` module's obfuscated JAR + mapping file.

---

## Timeline Estimate

| Phase | Features | Estimated Effort |
|-------|----------|-----------------|
| 1 | String Extraction + Class Hierarchy | 3–4 days |
| 2 | Bytecode Inspector + JAR Deobfuscation | 5–7 days |
| 3 | Bytecode Diff + Method Call Graph | 5–7 days |
| 4 | Control Flow Graph Viewer | 5–7 days |
| **Total** | **7 features** | **~18–25 days** |
