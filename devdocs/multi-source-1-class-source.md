# Multi-source — Step 1: ClassSource Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `ClassSource` interface that hides where class bytes come from. Migrate the four ViewModels that read class data (ClassBrowser, Hierarchy, Inspector, Strings) so they read through `ClassSource` instead of calling `AgentClient` directly. Behavior must be identical to before this refactor — no user-visible change.

**Architecture:** A new `ClassSource` interface lives in `composeApp/.../source/` (it returns `protocol.ClassInfo`, so it cannot live in `core` without coupling `core → protocol`). The sole initial implementation is `AgentClassSource`, a thin wrapper over the existing `AgentClient`. `ConnectionRegistry` is widened to hold a `StateFlow<ClassSource?>` alongside the existing `connectionKey`. The Sidebar's enabled-tab logic switches from a boolean `isConnected` to capability checks against the active source.

**Tech Stack:** Kotlin, kotlinx.coroutines, Koin DI, JUnit 5 + MockK + kotlinx-coroutines-test. No new external dependencies.

**Module changes:** `composeApp` only (apart from a tiny `Capability` enum that lives in `composeApp` too). `core`, `protocol`, and `agent` are untouched.

---

## File Structure

**New files (composeApp):**
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/Capability.kt` — small enum
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/ClassSource.kt` — interface + companion helpers
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/AgentClassSource.kt` — wraps `AgentClient`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/AgentClassSourceTest.kt` — unit test for the wrapper

**Modified files (composeApp):**
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ConnectionRegistry.kt` — add `classSource` flow
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt` — wiring
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachViewModel.kt` — construct `AgentClassSource` on connect
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt` — read `ClassSource` from registry, pass capability set to `Sidebar`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Sidebar.kt` — gate by capabilities
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Navigation.kt` — `NavigationState` carries capability set
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/browser/ClassBrowserViewModel.kt`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyViewModel.kt`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/strings/StringsViewModel.kt`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/inspector/InspectorViewModel.kt`
- Existing tests for the four migrated VMs — update constructors

**Test build commands** (from `AGENTS.md`):
- `./gradlew :composeApp:jvmTest` — run all composeApp tests
- `./gradlew :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.AgentClassSourceTest"` — single test class
- On Windows: `.\gradlew.bat ...`

---

## Important reminders for the implementer

- This is a **behavior-preserving refactor**. Run the existing test suite frequently and the desktop app at the end. If any test that was green before now fails for a non-trivial reason, stop and investigate.
- **Don't migrate runtime-only VMs** in this plan: TraceViewModel, HeapViewModel, DebuggerViewModel, AIViewModel, AttachViewModel keep using `AgentClient` directly. They use RPCs (hooks, snapshots, breakpoints) that are not part of `ClassSource`. Future plans (Step 2+) will not need to change them either.
- **`ClassInfo` is the proto type** generated from `protocol/src/main/proto/bytesight.proto`. It lives in `com.bugdigger.protocol`. We keep using it as the data carrier between `ClassSource` and ViewModels — no new DTO is introduced in this plan.
- **Commit after every task.** This refactor touches many files; small commits are essential to bisect any regression.

---

## Task 1: Add `Capability` enum

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/Capability.kt`

- [ ] **Step 1: Create the file with the enum**

```kotlin
package com.bugdigger.bytesight.source

/**
 * What a [ClassSource] can do beyond static class inspection.
 *
 * STATIC_ANALYSIS is implicit (every source supports it). The other entries
 * are runtime-only — they require a live agent attached to a JVM.
 *
 * Used by the Sidebar to gate Trace / Heap / Debugger tabs and by the
 * routing in [com.bugdigger.bytesight.App] to skip rendering screens whose
 * required capabilities are absent.
 */
enum class Capability {
    /** Listing classes, fetching bytecode, decompiling, hierarchy, strings. Always present. */
    STATIC_ANALYSIS,

    /** Method tracing via the agent's hook RPCs. */
    LIVE_TRACE,

    /** Heap snapshot capture and exploration. */
    LIVE_HEAP,

    /** Breakpoints, stepping, debugger event subscription. */
    LIVE_DEBUG,
    ;

    companion object {
        /** All four capabilities — the set declared by an attached live agent today. */
        val ALL: Set<Capability> = entries.toSet()

        /** Just static analysis — declared by future JAR/APK/.bts sources. */
        val STATIC_ONLY: Set<Capability> = setOf(STATIC_ANALYSIS)
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :composeApp:compileKotlinJvm`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/Capability.kt
git commit -m "feat(source): add Capability enum for ClassSource gating"
```

---

## Task 2: Add `ClassSource` interface

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/ClassSource.kt`

- [ ] **Step 1: Create the interface**

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.protocol.ClassInfo

/**
 * Abstraction over "where class bytes come from". Hides the difference
 * between a live attached agent, a JAR/APK on disk (added in Step 2), and
 * a previously-saved `.bts` project file (Step 3).
 *
 * ViewModels that only need class metadata + bytecode read through this
 * interface so they don't have to care about the source. Runtime tabs
 * (Trace, Heap, Debugger) keep talking to AgentClient directly because
 * their RPCs aren't part of this contract.
 */
interface ClassSource {
    /** What this source supports beyond static analysis. */
    val capabilities: Set<Capability>

    /** Short label for the title bar / status bar (e.g. "PID 1234", "sample.jar"). */
    val displayName: String

    /**
     * Returns the list of classes this source knows about. Mirrors
     * [com.bugdigger.bytesight.service.AgentClient.listClasses].
     *
     * @param includeSystemClasses include `java.*`/`javax.*`/`sun.*`. Static
     *   sources may ignore this and return everything in the JAR/APK.
     */
    suspend fun listClasses(includeSystemClasses: Boolean = false): Result<List<ClassInfo>>

    /**
     * Returns raw bytecode for the given fully-qualified class name, or a
     * failure if the class is unknown to this source.
     */
    suspend fun getBytecode(className: String): Result<ByteArray>

    /** Release any held resources (close JarFile handles, etc.). Default: no-op. */
    fun close() {}
}
```

- [ ] **Step 2: Verify it compiles**

Run: `.\gradlew.bat :composeApp:compileKotlinJvm`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/ClassSource.kt
git commit -m "feat(source): add ClassSource interface"
```

---

## Task 3: Implement `AgentClassSource` (TDD)

**Files:**
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/AgentClassSourceTest.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/AgentClassSource.kt`

- [ ] **Step 1: Write the failing test first**

Create `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/AgentClassSourceTest.kt`:

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.protocol.BytecodeResponse
import com.bugdigger.protocol.ClassInfo
import com.google.protobuf.ByteString
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentClassSourceTest {

    private lateinit var agentClient: AgentClient
    private lateinit var source: AgentClassSource

    @BeforeEach
    fun setup() {
        agentClient = mockk()
        source = AgentClassSource(agentClient = agentClient, connectionKey = "localhost:50051")
    }

    @Nested
    @DisplayName("Capabilities and display name")
    inner class CapsAndName {
        @Test
        fun `declares all live capabilities`() {
            assertEquals(Capability.ALL, source.capabilities)
        }

        @Test
        fun `display name includes connection key`() {
            assertTrue(source.displayName.contains("localhost:50051"))
        }
    }

    @Nested
    @DisplayName("listClasses")
    inner class ListClasses {
        @Test
        fun `delegates to agentClient with includeSystemClasses=false by default`() = runTest {
            val klass = ClassInfo.newBuilder().setName("a.B").build()
            coEvery {
                agentClient.listClasses("localhost:50051", "", false)
            } returns Result.success(listOf(klass))

            val result = source.listClasses()

            assertTrue(result.isSuccess)
            assertEquals(listOf(klass), result.getOrNull())
        }

        @Test
        fun `passes includeSystemClasses=true through`() = runTest {
            coEvery {
                agentClient.listClasses("localhost:50051", "", true)
            } returns Result.success(emptyList())

            val result = source.listClasses(includeSystemClasses = true)

            assertTrue(result.isSuccess)
        }

        @Test
        fun `propagates failure from agentClient`() = runTest {
            coEvery {
                agentClient.listClasses(any(), any(), any())
            } returns Result.failure(IllegalStateException("not connected"))

            val result = source.listClasses()

            assertTrue(result.isFailure)
        }
    }

    @Nested
    @DisplayName("getBytecode")
    inner class GetBytecode {
        @Test
        fun `returns the bytes from the response`() = runTest {
            val bytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
            val response = BytecodeResponse.newBuilder()
                .setClassName("a.B")
                .setBytecode(ByteString.copyFrom(bytes))
                .setFound(true)
                .build()
            coEvery {
                agentClient.getClassBytecode("localhost:50051", "a.B")
            } returns Result.success(response)

            val result = source.getBytecode("a.B")

            assertTrue(result.isSuccess)
            assertEquals(bytes.toList(), result.getOrNull()!!.toList())
        }

        @Test
        fun `returns failure when found=false`() = runTest {
            val response = BytecodeResponse.newBuilder()
                .setFound(false)
                .setError("missing")
                .build()
            coEvery {
                agentClient.getClassBytecode(any(), any())
            } returns Result.success(response)

            val result = source.getBytecode("a.Missing")

            assertTrue(result.isFailure)
        }

        @Test
        fun `propagates rpc failure`() = runTest {
            coEvery {
                agentClient.getClassBytecode(any(), any())
            } returns Result.failure(IllegalStateException("not connected"))

            val result = source.getBytecode("a.B")

            assertTrue(result.isFailure)
        }
    }
}
```

- [ ] **Step 2: Run the test to confirm it fails (because AgentClassSource does not exist yet)**

Run: `.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.AgentClassSourceTest"`

Expected: COMPILATION FAILED — `AgentClassSource` is unresolved.

- [ ] **Step 3: Create the implementation**

Create `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/AgentClassSource.kt`:

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.protocol.ClassInfo

/**
 * [ClassSource] backed by a live agent connection. Thin wrapper over
 * [AgentClient]; no caching here so behavior matches the pre-refactor flow
 * where every browse/select round-trips to the agent.
 *
 * The constructor takes the [connectionKey] returned by [AgentClient.connect];
 * [close] does not disconnect the underlying gRPC channel because the
 * connection is owned by [AgentClient] and may outlive this source if the
 * user simply switches sources.
 */
class AgentClassSource(
    private val agentClient: AgentClient,
    val connectionKey: String,
) : ClassSource {

    override val capabilities: Set<Capability> = Capability.ALL

    override val displayName: String = "JVM @ $connectionKey"

    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> {
        return agentClient.listClasses(
            connectionKey = connectionKey,
            packageFilter = "",
            includeSystemClasses = includeSystemClasses,
        )
    }

    override suspend fun getBytecode(className: String): Result<ByteArray> {
        val rpc = agentClient.getClassBytecode(connectionKey, className)
        return rpc.fold(
            onSuccess = { response ->
                if (response.found) {
                    Result.success(response.bytecode.toByteArray())
                } else {
                    Result.failure(NoSuchElementException(
                        if (response.error.isNotEmpty()) response.error
                        else "Class not found: $className"
                    ))
                }
            },
            onFailure = { Result.failure(it) },
        )
    }
}
```

- [ ] **Step 4: Run the test to confirm it passes**

Run: `.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.AgentClassSourceTest"`

Expected: 8 tests pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/AgentClassSource.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/AgentClassSourceTest.kt
git commit -m "feat(source): add AgentClassSource backed by AgentClient"
```

---

## Task 4: Widen `ConnectionRegistry` to hold a `ClassSource`

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ConnectionRegistry.kt`

The strategy: keep `connectionKey` exactly as-is (existing consumers like `BytesightAgentServicesImpl`, runtime VMs, integration test rely on it), and add a parallel `classSource` flow. They are kept in sync by `AttachViewModel` (Task 5).

- [ ] **Step 1: Replace the file content**

Open `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ConnectionRegistry.kt` and replace the body with:

```kotlin
package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.source.ClassSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped holder for the current source of class data, the live
 * connection key (when one exists), and the last captured heap snapshot.
 *
 * Two parallel pieces of state:
 *
 * - [classSource] — what migrated VMs (ClassBrowser, Hierarchy, Inspector,
 *   Strings) read class metadata + bytecode from. May be backed by a live
 *   agent or, in later steps, by a JAR/APK/.bts file.
 * - [connectionKey] — set only when [classSource] is an
 *   [com.bugdigger.bytesight.source.AgentClassSource]. Runtime VMs (Trace,
 *   Heap, Debugger) and the AI-services impl read this for direct AgentClient
 *   calls. Null when the active source is static.
 */
class ConnectionRegistry {

    private val _classSource = MutableStateFlow<ClassSource?>(null)
    val classSource: StateFlow<ClassSource?> = _classSource.asStateFlow()

    private val _connectionKey = MutableStateFlow<String?>(null)
    val connectionKey: StateFlow<String?> = _connectionKey.asStateFlow()

    private val _snapshotId = MutableStateFlow<Long?>(null)
    val snapshotId: StateFlow<Long?> = _snapshotId.asStateFlow()

    /** Install a new active source. Closes the previous source if any. */
    fun setSource(source: ClassSource?, connectionKey: String? = null) {
        _classSource.value?.close()
        _classSource.value = source
        _connectionKey.value = connectionKey
        if (source == null) _snapshotId.value = null
    }

    /**
     * Convenience setter kept for callers that only know about the connection
     * key (e.g. legacy paths). Wraps [setSource] with a null source — runtime
     * VMs that read [connectionKey] still work, but [classSource] consumers
     * see no source. New code should call [setSource] with a [ClassSource].
     */
    @Deprecated("Use setSource() — passing only a key leaves classSource null", ReplaceWith("setSource(source, key)"))
    fun setConnection(key: String?) {
        if (key == null) {
            setSource(null, null)
        } else {
            // Don't drop classSource if one was already installed for this key.
            _connectionKey.value = key
        }
    }

    fun setSnapshot(id: Long?) {
        _snapshotId.value = id
    }
}
```

- [ ] **Step 2: Compile and run all composeApp tests**

Run: `.\gradlew.bat :composeApp:jvmTest`

Expected: BUILD SUCCESSFUL — existing tests still pass. The deprecated `setConnection(...)` keeps existing call sites compiling (they'll be migrated in Task 5).

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ConnectionRegistry.kt
git commit -m "refactor(registry): widen ConnectionRegistry to hold ClassSource"
```

---

## Task 5: `AttachViewModel` builds an `AgentClassSource` on connect

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachViewModel.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt`

The cleanest change: `AttachViewModel` builds the source itself and pushes it to the registry as part of `attachToSelected`. `App.kt` reads `connectionRegistry.classSource` for routing decisions instead of computing them from `connectionKey`.

- [ ] **Step 1: Inject `ConnectionRegistry` into `AttachViewModel` and build the source on connect**

Edit `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachViewModel.kt`. Update the constructor and the `attachToSelected` body:

```kotlin
// imports — add:
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.source.AgentClassSource

class AttachViewModel(
    private val attachService: AttachService,
    private val agentClient: AgentClient,
    private val connectionRegistry: ConnectionRegistry,
) : ViewModel() {

    // ... unchanged uiState, init, refreshProcesses, selectProcess, setAgentPort ...

    fun attachToSelected() {
        val state = _uiState.value
        val process = state.selectedProcess ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isAttaching = true, error = null) }

            attachService.attachAgent(process.pid, state.agentPort)
                .onSuccess { port ->
                    agentClient.connect(port = port)
                        .onSuccess { key ->
                            // Build the live source and install it on the registry
                            // so VMs that read classSource pick it up immediately.
                            val source = AgentClassSource(agentClient, key)
                            connectionRegistry.setSource(source, key)

                            _uiState.update {
                                it.copy(isAttaching = false, connectionKey = key)
                            }
                        }
                        .onFailure { e ->
                            _uiState.update {
                                it.copy(
                                    isAttaching = false,
                                    error = "Connected agent but failed to establish gRPC connection: ${e.message}",
                                )
                            }
                        }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isAttaching = false, error = "Failed to attach: ${e.message}")
                    }
                }
        }
    }

    fun disconnect() {
        _uiState.value.connectionKey?.let { key ->
            agentClient.disconnect(key)
            connectionRegistry.setSource(null, null)
            _uiState.update { it.copy(connectionKey = null) }
        }
    }

    // ... unchanged clearError, onCleared ...
}
```

- [ ] **Step 2: Update `AppModule.kt` so `AttachViewModel` resolves `ConnectionRegistry`**

`AttachViewModel` is registered with `factoryOf(::AttachViewModel)` (line ~70 of `AppModule.kt`). Koin will pick up the new constructor parameter automatically because `ConnectionRegistry` is already a singleton there. **No edit needed if you used `factoryOf` / `singleOf` — these reflect on the constructor.** Just verify by running tests.

- [ ] **Step 3: Update `App.kt` to no longer call `connectionRegistry.setConnection(key)` from the connect callback**

In `App.kt`, the `onConnected` callback currently does `connectionRegistry.setConnection(connectionKey)`. This is now redundant — `AttachViewModel` already installed the source. Remove that line:

```kotlin
// In App.kt, inside the App() composable, MainContent's onConnected callback:
onConnected = { connectionKey ->
    // ConnectionRegistry is now updated by AttachViewModel.attachToSelected()
    // before this callback fires. We just update navigation state.
    navState = navState.copy(
        isConnected = true,
        connectionKey = connectionKey,
        currentScreen = Screen.CLASS_BROWSER,
    )
},
onDisconnected = {
    // Disconnect path goes through AttachViewModel which clears the registry.
    navState = navState.copy(
        isConnected = false,
        connectionKey = null,
        currentScreen = Screen.ATTACH,
    )
},
```

- [ ] **Step 4: Compile and run all tests**

Run: `.\gradlew.bat :composeApp:jvmTest`

Expected: BUILD SUCCESSFUL. Existing tests for `AttachViewModel` (none currently in the repo for AttachViewModel — verify with `Get-ChildItem composeApp\src\jvmTest -Recurse -Filter AttachViewModel*` or in IDE) and other VMs still pass.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachViewModel.kt composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt
git commit -m "refactor(attach): wire AgentClassSource into ConnectionRegistry on attach"
```

---

## Task 6: Migrate `ClassBrowserViewModel` to read from `ClassSource`

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/browser/ClassBrowserViewModel.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/browser/ClassBrowserViewModelTest.kt` (or whichever test file uses the old constructor; if no test exists, skip the test edit)
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt` — no longer pass `connectionKey` directly; the VM gets it from registry

The pattern: VM constructor takes `ConnectionRegistry` instead of `AgentClient`. It observes `classSource` and reacts to changes. The `connectionKey` parameter on the screen composable goes away.

- [ ] **Step 1: Update `ClassBrowserViewModel` to depend on `ConnectionRegistry`**

Replace the constructor and the data-fetching internals:

```kotlin
package com.bugdigger.bytesight.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.bytesight.source.ClassSource
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// ClassBrowserUiState — unchanged

class ClassBrowserViewModel(
    private val connectionRegistry: ConnectionRegistry,
    private val decompiler: Decompiler,
    private val renameStore: RenameStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassBrowserUiState())
    val uiState: StateFlow<ClassBrowserUiState> = _uiState.asStateFlow()

    private var activeSource: ClassSource? = null

    init {
        // Re-apply renames whenever the rename map changes
        viewModelScope.launch {
            renameStore.renameMap.collect { _ ->
                _uiState.update { state ->
                    state.copy(
                        displayDecompiled = state.decompiled?.let { renameStore.applyToSource(it) },
                    )
                }
            }
        }
        // React to source changes — replaces the old setConnectionKey call site.
        viewModelScope.launch {
            connectionRegistry.classSource.collect { source ->
                onSourceChanged(source)
            }
        }
    }

    private fun onSourceChanged(source: ClassSource?) {
        if (activeSource === source) return
        activeSource = source
        // Drop the previous source's class list and any drilled-into class.
        // Keep user preferences (searchQuery, includeSystemClasses) so the
        // browser opens with the user's last-used filter against the new
        // source — usually what they want when they reattach to retry.
        val prev = _uiState.value
        _uiState.value = ClassBrowserUiState(
            searchQuery = prev.searchQuery,
            includeSystemClasses = prev.includeSystemClasses,
        )
        if (source != null) refreshClasses()
    }

    fun refreshClasses() {
        val source = activeSource ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            source.listClasses(_uiState.value.includeSystemClasses)
                .onSuccess { classes ->
                    _uiState.update {
                        it.copy(
                            classes = classes,
                            filteredClasses = filterClasses(classes, it.searchQuery),
                            isLoading = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to load classes: ${e.message}")
                    }
                }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(searchQuery = query, filteredClasses = filterClasses(it.classes, query))
        }
    }

    fun setIncludeSystemClasses(include: Boolean) {
        _uiState.update { it.copy(includeSystemClasses = include) }
        refreshClasses()
    }

    fun selectClass(classInfo: ClassInfo?) {
        _uiState.update {
            it.copy(
                selectedClass = classInfo,
                bytecode = null,
                decompiled = null,
                decompilationWarnings = emptyList(),
            )
        }
        if (classInfo != null) fetchBytecode(classInfo.name)
    }

    private fun fetchBytecode(className: String) {
        val source = activeSource ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBytecode = true) }
            source.getBytecode(className)
                .onSuccess { bytes ->
                    _uiState.update { it.copy(bytecode = bytes, decompiled = "// Decompiling...") }
                    decompileBytecode(className, bytes)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoadingBytecode = false, error = "Failed to fetch bytecode: ${e.message}")
                    }
                }
        }
    }

    // decompileBytecode and the rest — unchanged from the original file.
    // Just keep existing behavior; only the data-fetch path changed.
    // ... (clearError, filterClasses) ...
}
```

(Keep the `decompileBytecode`, `clearError`, and `filterClasses` methods exactly as they are in the original file.)

**Note:** `setConnectionKey(key: String)` is removed. The screen composable no longer needs to pass `connectionKey` because the VM reads from the registry directly.

- [ ] **Step 2: Update `ClassBrowserScreen.kt` to drop the `connectionKey` parameter**

Open `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/browser/ClassBrowserScreen.kt`. Remove the `connectionKey: String` parameter from the `ClassBrowserScreen` composable signature and any code that calls `viewModel.setConnectionKey(connectionKey)` inside it. The VM observes the registry; it's no longer the screen's responsibility.

- [ ] **Step 3: Update the `ClassBrowserScreen` invocation in `App.kt`**

Inside `MainContent`, change:

```kotlin
Screen.CLASS_BROWSER -> {
    val connectionKey = navState.connectionKey
    if (connectionKey != null) {
        val viewModel: ClassBrowserViewModel = koinInject()
        ClassBrowserScreen(
            viewModel = viewModel,
            connectionKey = connectionKey,
            onAskAI = onAskAI,
            modifier = modifier,
        )
    }
}
```

to:

```kotlin
Screen.CLASS_BROWSER -> {
    val viewModel: ClassBrowserViewModel = koinInject()
    ClassBrowserScreen(
        viewModel = viewModel,
        onAskAI = onAskAI,
        modifier = modifier,
    )
}
```

(The `if (connectionKey != null)` guard is now in the routing — see Task 11 for capability-based gating.)

- [ ] **Step 4: Update existing tests for `ClassBrowserViewModel`**

If `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/browser/ClassBrowserViewModelTest.kt` exists and constructs the VM with `AgentClient`, change it to use `ConnectionRegistry`:

```kotlin
@BeforeEach
fun setup() {
    connectionRegistry = ConnectionRegistry()
    decompiler = mockk(relaxed = true)
    viewModel = ClassBrowserViewModel(connectionRegistry, decompiler, RenameStore())
}
```

If no test file exists for ClassBrowser today, skip this step.

- [ ] **Step 5: Update `AppModule.kt` Koin wiring**

In `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt`, the `singleOf(::ClassBrowserViewModel)` line is unchanged — Koin reflects on the new constructor signature automatically (`ConnectionRegistry` is already registered as a singleton).

- [ ] **Step 6: Run `:composeApp:jvmTest` and the desktop app smoke test**

```bash
.\gradlew.bat :composeApp:jvmTest
.\gradlew.bat :composeApp:run
```

In the running app: open Attach → pick a JVM → Classes tab should populate exactly as before. Click a class → bytecode + decompiled source appear. Identical behavior to pre-refactor.

- [ ] **Step 7: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/browser/ composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/browser/
git commit -m "refactor(browser): read class data from ClassSource via ConnectionRegistry"
```

---

## Task 7: Migrate `HierarchyViewModel`

Mirror the pattern from Task 6.

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyViewModel.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyScreen.kt` — drop `connectionKey` param
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt` — drop the `connectionKey` argument when invoking `HierarchyScreen`
- Modify: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/hierarchy/HierarchyViewModelTest.kt` — fix constructor

- [ ] **Step 1: Replace the VM body**

The diff vs. the existing file is small — swap `AgentClient` for `ConnectionRegistry`, swap the `setConnectionKey` method for an init-time `classSource.collect { ... }` block, and swap `agentClient.listClasses(...)` for `source.listClasses(...)`. Keep `loadHierarchy` as the single entry point that reads from the active source. Use Task 6's structure as the template — same skeleton, different state class.

```kotlin
class HierarchyViewModel(
    private val connectionRegistry: ConnectionRegistry,
    private val renameStore: RenameStore,
) : ViewModel() {
    // _uiState, uiState, allClasses, init { renameStore.collect{} } unchanged

    private var activeSource: ClassSource? = null

    init {
        viewModelScope.launch {
            renameStore.renameMap.collect { _ ->
                _uiState.update { it.copy(renames = renameStore.shortNameMap()) }
            }
        }
        viewModelScope.launch {
            connectionRegistry.classSource.collect { source ->
                onSourceChanged(source)
            }
        }
    }

    private fun onSourceChanged(source: ClassSource?) {
        if (activeSource === source) return
        activeSource = source
        allClasses = emptyList()
        val prev = _uiState.value
        _uiState.value = HierarchyUiState(
            showInterfaces = prev.showInterfaces,
            showClasses = prev.showClasses,
            searchQuery = prev.searchQuery,
            renames = prev.renames,
        )
        if (source != null) loadHierarchy()
    }

    fun loadHierarchy() {
        val source = activeSource ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            source.listClasses(includeSystemClasses = false)
                .onSuccess { classes ->
                    allClasses = classes
                    val roots = HierarchyBuilder.buildHierarchy(classes)
                    val filtered = applyVisibilityFilter(roots, _uiState.value)
                    _uiState.update {
                        it.copy(
                            allRoots = roots,
                            filteredRoots = HierarchyBuilder.filterTree(filtered, it.searchQuery),
                            isLoading = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to load classes: ${e.message}")
                    }
                }
        }
    }

    // selectClass, setSearchQuery, setShowInterfaces, setShowClasses, toggleExpanded,
    // clearError, applyVisibilityFilter — unchanged.
}
```

- [ ] **Step 2: Drop `connectionKey` parameter from `HierarchyScreen` and the App.kt invocation**

Pattern is identical to Task 6 Step 2-3.

- [ ] **Step 3: Fix `HierarchyViewModelTest.kt`**

Replace `AgentClient` mock construction with a real `ConnectionRegistry()` and a fake `ClassSource` set on it via `registry.setSource(fakeSource, "test")`. Example:

```kotlin
private class FakeClassSource(private val classes: List<ClassInfo>) : ClassSource {
    override val capabilities = Capability.ALL
    override val displayName = "fake"
    override suspend fun listClasses(includeSystemClasses: Boolean) = Result.success(classes)
    override suspend fun getBytecode(className: String) = Result.failure<ByteArray>(NotImplementedError())
}

@BeforeEach
fun setup() {
    registry = ConnectionRegistry()
    viewModel = HierarchyViewModel(registry, RenameStore())
}

@Test
fun `loadHierarchy populates roots from source`() = runTest {
    val sample = listOf(ClassInfo.newBuilder().setName("a.B").build())
    registry.setSource(FakeClassSource(sample), "test")
    // give the collect block a tick to react
    runCurrent()
    assertTrue(viewModel.uiState.value.allRoots.isNotEmpty())
}
```

- [ ] **Step 4: Run hierarchy tests**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.hierarchy.*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/hierarchy/ composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/hierarchy/
git commit -m "refactor(hierarchy): read from ClassSource via ConnectionRegistry"
```

---

## Task 8: Migrate `StringsViewModel`

Same pattern as Task 6 / 7. The VM iterates `source.listClasses()` then for each calls `source.getBytecode(name)` and feeds bytes to `ConstantExtractor`.

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/strings/StringsViewModel.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/strings/StringsScreen.kt` — drop `connectionKey` param
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/strings/StringsViewModelTest.kt`

- [ ] **Step 1: Replace constructor and `setConnectionKey` with init-time observation**

```kotlin
class StringsViewModel(
    private val connectionRegistry: ConnectionRegistry,
    private val renameStore: RenameStore,
) : ViewModel() {
    // ... uiState unchanged ...
    private val extractor = ConstantExtractor()
    private var activeSource: ClassSource? = null

    init {
        viewModelScope.launch {
            renameStore.renameMap.collect { _ ->
                _uiState.update { it.copy(renames = renameStore.shortNameMap()) }
            }
        }
        viewModelScope.launch {
            connectionRegistry.classSource.collect { source ->
                if (activeSource !== source) {
                    activeSource = source
                    val prev = _uiState.value
                    _uiState.value = StringsUiState(
                        typeFilter = prev.typeFilter,
                        patternFilter = prev.patternFilter,
                        renames = prev.renames,
                    )
                }
            }
        }
    }

    fun extractAll() {
        val source = activeSource ?: return
        viewModelScope.launch {
            _uiState.update {
                it.copy(isExtracting = true, progress = 0f, error = null,
                    constants = emptyList(), filteredConstants = emptyList(), processedClasses = 0)
            }
            source.listClasses(includeSystemClasses = false)
                .onSuccess { classes ->
                    _uiState.update { it.copy(totalClasses = classes.size) }
                    val all = mutableListOf<ExtractedConstant>()
                    var processed = 0
                    for (info in classes) {
                        source.getBytecode(info.name)
                            .onSuccess { bytes ->
                                if (bytes.isNotEmpty()) {
                                    runCatching { all.addAll(extractor.extract(bytes)) }
                                }
                            }
                        processed++
                        if (processed % 10 == 0 || processed == classes.size) {
                            _uiState.update {
                                it.copy(processedClasses = processed,
                                    progress = processed.toFloat() / classes.size,
                                    constants = all.toList(),
                                    filteredConstants = applyFilters(all, it.searchQuery, it.typeFilter, it.patternFilter))
                            }
                        }
                    }
                    _uiState.update {
                        it.copy(isExtracting = false, progress = 1f,
                            constants = all.toList(),
                            filteredConstants = applyFilters(all, it.searchQuery, it.typeFilter, it.patternFilter))
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(isExtracting = false, error = "Failed to list classes: ${e.message}") }
                }
        }
    }

    // setSearchQuery, toggleTypeFilter, togglePatternFilter, clearError, applyFilters — unchanged
}
```

- [ ] **Step 2: Drop `connectionKey` from `StringsScreen` + the App.kt invocation**

- [ ] **Step 3: Fix `StringsViewModelTest.kt`**

Replace `mockk<AgentClient>()` constructor arg with `ConnectionRegistry()`. Initial-state and filter tests are unaffected. The "Extract Without Connection" test still passes because `activeSource` is null until a source is installed on the registry.

- [ ] **Step 4: Run strings tests**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.strings.*"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/strings/ composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/strings/
git commit -m "refactor(strings): read from ClassSource via ConnectionRegistry"
```

---

## Task 9: Migrate `InspectorViewModel`

Same pattern. Inspector uses `listClasses` for the dropdown and `getBytecode` for the disassembly + decompile + CFG.

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/inspector/InspectorViewModel.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/inspector/InspectorScreen.kt` — drop `connectionKey` param
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt`
- Modify: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/inspector/InspectorViewModelTest.kt`

- [ ] **Step 1: Open `InspectorViewModel.kt` and replace its data-fetch path**

Constructor: replace `agentClient: AgentClient` with `connectionRegistry: ConnectionRegistry`. Keep `commentStore`, `renameStore`, `decompiler`, `debuggerState` as-is. Replace any `agentClient.listClasses(connectionKey, ...)` with `activeSource?.listClasses(...)`. Replace `agentClient.getClassBytecode(...)` with `activeSource?.getBytecode(...)`. The cached-bytecode optimization (`cachedBytecode`) still applies — keep it.

Add the same `init { connectionRegistry.classSource.collect { ... } }` block + `private var activeSource: ClassSource? = null` field.

Remove `setConnectionKey(key: String)` — same as the other VMs.

(The signature change must be reflected in the screen composable. Inspector reads `pendingClassName/pendingMethodName/pendingMethodSignature` from navigation state — those parameters are unchanged.)

- [ ] **Step 2: Drop `connectionKey` from `InspectorScreen` + App.kt**

In App.kt:

```kotlin
Screen.INSPECTOR -> {
    val viewModel: InspectorViewModel = koinInject()
    InspectorScreen(
        viewModel = viewModel,
        pendingClassName = navState.pendingInspectorClass,
        pendingMethodName = navState.pendingInspectorMethod,
        pendingMethodSignature = navState.pendingInspectorMethodSignature,
        onPendingClassConsumed = onClearPendingInspectorClass,
        onAskAI = onAskAI,
        modifier = modifier,
    )
}
```

- [ ] **Step 3: Fix `InspectorViewModelTest.kt`**

Existing tests construct the VM via `mockk<AgentClient>()`. Replace with `ConnectionRegistry()` + a `FakeClassSource` (the same helper class introduced in Task 7 — copy it into a small shared test util at `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/FakeClassSource.kt`).

- [ ] **Step 4: Extract `FakeClassSource` test helper**

Create `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/FakeClassSource.kt`:

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.protocol.ClassInfo

/** Test fake. Configure with a class list and a per-class bytecode map. */
class FakeClassSource(
    private val classes: List<ClassInfo> = emptyList(),
    private val bytecode: Map<String, ByteArray> = emptyMap(),
    override val capabilities: Set<Capability> = Capability.ALL,
    override val displayName: String = "fake",
    private val listFailure: Throwable? = null,
    private val bytecodeFailure: Throwable? = null,
) : ClassSource {
    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> =
        listFailure?.let { Result.failure(it) } ?: Result.success(classes)

    override suspend fun getBytecode(className: String): Result<ByteArray> =
        bytecodeFailure?.let { Result.failure(it) }
            ?: bytecode[className]?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Class not found: $className"))
}
```

Use this fake in `HierarchyViewModelTest.kt`, `StringsViewModelTest.kt`, and `InspectorViewModelTest.kt` instead of inline anonymous impls.

- [ ] **Step 5: Run inspector tests**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.ui.inspector.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/inspector/ composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/ui/inspector/ composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/FakeClassSource.kt
git commit -m "refactor(inspector): read from ClassSource via ConnectionRegistry"
```

---

## Task 10: Carry capabilities through `NavigationState`

The Sidebar today gates by a boolean `isConnected`. We need it to gate by the **set of capabilities** of the active source so that future static-only sources (Step 2) can disable Trace/Heap/Debugger automatically.

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Navigation.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/Sidebar.kt`
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt`

- [ ] **Step 1: Add `capabilities` to `NavigationState`**

In `Navigation.kt`:

```kotlin
import com.bugdigger.bytesight.source.Capability

data class NavigationState(
    val currentScreen: Screen = Screen.ATTACH,
    val isConnected: Boolean = false,
    val connectionKey: String? = null,
    /** Capabilities of the active source. Empty when not connected. */
    val capabilities: Set<Capability> = emptySet(),
    val pendingInspectorClass: String? = null,
    val pendingInspectorMethod: String? = null,
    val pendingInspectorMethodSignature: String? = null,
    val pendingAIPrompt: String? = null,
)
```

- [ ] **Step 2: Update `Sidebar` to take a `capabilities` set instead of `isConnected`**

```kotlin
@Composable
fun Sidebar(
    currentScreen: Screen,
    capabilities: Set<Capability>,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Spacer(Modifier.height(12.dp))

        ConnectionIndicator(isConnected = capabilities.isNotEmpty())

        Spacer(Modifier.height(24.dp))

        Screen.entries.forEach { screen ->
            val enabled = isScreenEnabled(screen, capabilities)
            NavigationRailItem(
                selected = currentScreen == screen,
                onClick = { if (enabled) onNavigate(screen) },
                enabled = enabled,
                icon = { Text(text = screen.icon, style = MaterialTheme.typography.titleMedium) },
                label = { Text(screen.title) },
                alwaysShowLabel = true,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

private fun isScreenEnabled(screen: Screen, caps: Set<Capability>): Boolean = when (screen) {
    Screen.ATTACH, Screen.AI, Screen.SETTINGS -> true
    Screen.CLASS_BROWSER, Screen.HIERARCHY,
    Screen.INSPECTOR, Screen.STRINGS -> Capability.STATIC_ANALYSIS in caps
    Screen.TRACE -> Capability.LIVE_TRACE in caps
    Screen.HEAP -> Capability.LIVE_HEAP in caps
    Screen.DEBUGGER -> Capability.LIVE_DEBUG in caps
}
```

(`ConnectionIndicator` private composable stays as-is; just pass `capabilities.isNotEmpty()`.)

- [ ] **Step 3: Update `App.kt` to push capabilities into NavigationState**

In `App()`, observe `connectionRegistry.classSource` and update `navState.capabilities`:

```kotlin
@Composable
fun App() {
    BytesightTheme {
        var navState by remember { mutableStateOf(NavigationState()) }
        val connectionRegistry: ConnectionRegistry = koinInject()
        val activeSource by connectionRegistry.classSource.collectAsState()

        // Sync capabilities into navState whenever the source changes.
        LaunchedEffect(activeSource) {
            navState = navState.copy(
                capabilities = activeSource?.capabilities ?: emptySet(),
                isConnected = activeSource != null,
            )
        }

        Row(...) {
            Sidebar(
                currentScreen = navState.currentScreen,
                capabilities = navState.capabilities,
                onNavigate = { screen -> navState = navState.copy(currentScreen = screen) },
            )
            MainContent(navState = navState, ...)
        }
    }
}
```

(Add imports for `androidx.compose.runtime.LaunchedEffect`, `androidx.compose.runtime.collectAsState`, `androidx.compose.runtime.getValue`, `com.bugdigger.bytesight.source.Capability`.)

The `onConnected` and `onDisconnected` callbacks no longer need to set `isConnected` — the `LaunchedEffect` does it. Simplify them to only update `currentScreen` and `connectionKey`.

- [ ] **Step 4: Compile and run all tests**

```bash
.\gradlew.bat :composeApp:jvmTest
```

Expected: PASS.

- [ ] **Step 5: Smoke test the desktop app**

```bash
.\gradlew.bat :composeApp:run
```

- Open Attach → pick a JVM → Sidebar should now show Classes / Hierarchy / Inspector / Strings / Trace / Heap / Debugger / AI all enabled (live agent declares all capabilities).
- Click Disconnect → only Attach / AI / Settings remain enabled.

- [ ] **Step 6: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/navigation/ composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt
git commit -m "refactor(nav): gate sidebar by Capability set, not isConnected"
```

---

## Task 11: Final smoke test + integration test sweep

**Files:** none modified.

- [ ] **Step 1: Run the full composeApp test suite**

```bash
.\gradlew.bat :composeApp:jvmTest
```

Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 2: Run core tests**

```bash
.\gradlew.bat :core:test
```

Expected: BUILD SUCCESSFUL — no changes to `core` in this refactor.

- [ ] **Step 3: Build the full project**

```bash
.\gradlew.bat build
```

Expected: BUILD SUCCESSFUL — exercises agent JAR build, sample JAR build, all modules compile.

- [ ] **Step 4: Manual end-to-end smoke test**

Build everything then run the app:

```bash
.\gradlew.bat :sample:jar :agent:agentJar
.\gradlew.bat :composeApp:run
```

In another shell, start the sample target:

```bash
java -jar sample/build/libs/sample-*.jar
```

In the running Bytesight app:
1. Attach tab → see the sample JVM in the list → Attach.
2. Classes tab populates. Click a class → bytecode + decompiled appears.
3. Hierarchy tab populates. Tree opens, ancestors shown on click.
4. Inspector tab opens. Method dropdown lists methods. Disassembly + decompiled + CFG render.
5. Strings tab → Extract All → constants populate.
6. Trace tab → add a hook → trigger sample method → events stream.
7. Heap tab → Capture Snapshot → histogram renders.
8. Debugger tab → set a breakpoint → trigger → frame visible.
9. Disconnect → all live tabs disable; only Attach / AI / Settings remain.

**If anything in 1-9 differs from pre-refactor behavior, the refactor failed.** Investigate before continuing.

- [ ] **Step 5: Update devdocs/plan.md to note this milestone is done**

Append a short line under whichever phase tracker is current. (Optional but useful as a paper trail.)

- [ ] **Step 6: Final commit + tag**

```bash
git add -A
git commit -m "chore: Step 1 ClassSource refactor complete"
git tag step-1-classsource-complete
```

---

## Verification (end-to-end)

Re-runs the full check list above. The key invariants:

| Invariant | How to check |
|---|---|
| All existing tests still pass | `./gradlew :composeApp:jvmTest :core:test :agent:test` |
| User-visible behavior unchanged | Manual smoke (Task 11 step 4) |
| `ClassSource` is the only path for class-data reads in the 4 migrated VMs | grep: `Get-ChildItem composeApp/src/jvmMain -Recurse -Include *ViewModel.kt | Select-String "agentClient.listClasses\|agentClient.getClassBytecode"` should return matches **only** in `AgentClassSource.kt`. |
| Capability gating works | Disconnect → Trace/Heap/Debugger become disabled. (Will be tested again automatically once Step 2 lands a static source.) |

## What's intentionally not done in this plan

- No JAR/APK loading (Step 2)
- No save/load (Step 3)
- No diff (Step 4)
- TraceViewModel, HeapViewModel, DebuggerViewModel still talk to `AgentClient` directly using `connectionKey` from `ConnectionRegistry`. That's correct: their RPCs are not part of `ClassSource` and pulling them through it would be premature.
- The deprecated `ConnectionRegistry.setConnection(key)` shim stays for one release; it'll be removed once we're sure nothing else calls it. Search for callers with: `Get-ChildItem composeApp/src/jvmMain -Recurse | Select-String "setConnection\("`. If only the shim itself shows up after Step 2, delete it.
