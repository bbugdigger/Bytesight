# Multi-source — Step 3: Save/Load `.bts` — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist a reverse-engineering session to a custom `.bts` file (frozen snapshot — annotations + bytecode in one container) and round-trip it. Reopening a `.bts` reconstructs the active `ClassSource` plus `RenameStore` / `CommentStore` / breakpoints / settings — even if the original JVM process or JAR is no longer available.

**Architecture:** A `.bts` is a ZIP container with a small JSON manifest, the raw class bytes (slash-encoded paths so it doubles as a regular JAR layout) and a few JSON sidecars for annotations and settings. A new `BtsProjectClassSource` is a `JarClassSource`-like static source backed by the `.bts`. Save/load is orchestrated by `ProjectService` in `composeApp`. Trace events and heap snapshots remain in their own sidecar files (`.btstrace`, `.btsheap` in this step), separate from the project file — same precedent as `.btsrec`.

**Tech Stack:** Kotlin, kotlinx-serialization-json (new), `java.util.zip` for the container, ASM (already on `core`'s classpath). Compose Desktop's menu bar for File → New / Open / Save / Save As.

**Module changes:** `core` (new project format types + reader/writer), `composeApp` (orchestrator, source impl, UI menus, store serialization).

**Prerequisite:** Step 1 (ClassSource refactor) and Step 2 (JAR/APK static sources) must be complete. This plan reuses `JarReader`, `StaticHierarchyExtractor`, `StaticClassInfoMapper`, `ClassSource`, `Capability`, `ConnectionRegistry`.

---

## File Structure

**`.bts` zip layout:**
```
manifest.json                    Required. Project metadata (version, sourceKind, displayName, createdAt, bytesightVersion).
classes/<a>/<b>/<C>.class        Required (>=1). Raw bytecode, slash-encoded path so a JarReader can read it as-is.
renames.json                     Optional. RenameStore state.
comments.json                    Optional. CommentStore state.
breakpoints.json                 Optional. DebuggerState breakpoints (path-only, no live state).
settings.json                    Optional. Per-project decompiler/UI prefs. No API keys.
```

**New files (`core`):**
- `core/src/main/kotlin/com/bugdigger/core/project/ProjectManifest.kt` — `@Serializable` data classes
- `core/src/main/kotlin/com/bugdigger/core/project/BtsProjectFile.kt` — read/write the zip container
- `core/src/test/kotlin/com/bugdigger/core/project/BtsProjectFileTest.kt`

**New files (`composeApp`):**
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/BtsProjectClassSource.kt` — `ClassSource` backed by an open `.bts`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ProjectService.kt` — orchestrates save & load
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/menubar/MainMenuBar.kt` — menu composable (New / Open / Save / Save As)
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/BtsProjectClassSourceTest.kt`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/ProjectServiceTest.kt`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/RenameStoreSerializationTest.kt`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/CommentStoreSerializationTest.kt`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/debugger/DebuggerStateSerializationTest.kt`

**Modified files:**
- `gradle/libs.versions.toml` — add kotlinx-serialization plugin + library
- `core/build.gradle.kts` — apply serialization plugin, add dep
- `composeApp/build.gradle.kts` — apply serialization plugin, add dep
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/RenameStore.kt` — add `serialize()/restore()`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/CommentStore.kt` — add `serialize()/restore()`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/debugger/DebuggerState.kt` — add `serialize()/restore()`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt` — register `ProjectService`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/Main.kt` — install Compose menu bar (or `App.kt` if menu lives there)

**Test commands:**
- `.\gradlew.bat :core:test --tests "com.bugdigger.core.project.*"`
- `.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.service.*"` and `.source.Bts*`

---

## Task 1: Add kotlinx-serialization

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/build.gradle.kts`
- Modify: `composeApp/build.gradle.kts`

- [ ] **Step 1: Add version + plugin + library to `libs.versions.toml`**

In `[versions]`:
```toml
kotlinx-serialization = "1.7.3"
```

In `[libraries]`:
```toml
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
```

In `[plugins]`:
```toml
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Apply the plugin and add the dep in `core/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)   // NEW
}

dependencies {
    // ...
    implementation(libs.kotlinx.serialization.json)
    // ...
}
```

- [ ] **Step 3: Same for `composeApp/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.kotlinSerialization)   // NEW
}

// In jvmMain.dependencies:
jvmMain.dependencies {
    // existing...
    implementation(libs.kotlinx.serialization.json)
}
```

- [ ] **Step 4: Verify resolution**

```bash
.\gradlew.bat :core:build :composeApp:compileKotlinJvm
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add gradle/libs.versions.toml core/build.gradle.kts composeApp/build.gradle.kts
git commit -m "build: add kotlinx-serialization for project file persistence"
```

---

## Task 2: `ProjectManifest` data classes (core)

**Files:**
- Create: `core/src/main/kotlin/com/bugdigger/core/project/ProjectManifest.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.bugdigger.core.project

import kotlinx.serialization.Serializable

/**
 * Project file container schema. Bumped when the on-disk format changes
 * incompatibly. Old versions can be migrated, never silently overwritten.
 */
const val PROJECT_FORMAT_VERSION = 1

/**
 * Top-level metadata stored as `manifest.json` inside the .bts container.
 * Keep this small — it's read on every project open.
 */
@Serializable
data class ProjectManifest(
    val formatVersion: Int = PROJECT_FORMAT_VERSION,
    val displayName: String,
    /** Where the bytes originally came from. Cosmetic — used for the title bar. */
    val sourceKind: SourceKind,
    /** Path to the original JAR/APK or "live PID 1234". Cosmetic; not used to re-attach. */
    val originalPath: String?,
    val createdAt: Long,
    val bytesightVersion: String,
    /** Names of optional sidecar files (.btstrace, .btsheap) that shipped alongside. */
    val sidecars: List<String> = emptyList(),
)

@Serializable
enum class SourceKind {
    /** Snapshot of a once-live agent attach. */
    LIVE_SNAPSHOT,
    JAR,
    APK,
    /** A loaded .bts re-saved (so we don't lose origin info). */
    BTS,
}
```

- [ ] **Step 2: Verify compile**

```bash
.\gradlew.bat :core:compileKotlin
```

- [ ] **Step 3: Commit**

```bash
git add core/src/main/kotlin/com/bugdigger/core/project/ProjectManifest.kt
git commit -m "feat(core/project): add ProjectManifest schema"
```

---

## Task 3: `BtsProjectFile` reader / writer (TDD)

The container is a regular ZIP. Reader exposes the manifest, the class bytes, and the optional JSON sidecars. Writer takes the same and produces the file atomically (write to `.tmp`, then rename).

**Files:**
- Create: `core/src/test/kotlin/com/bugdigger/core/project/BtsProjectFileTest.kt`
- Create: `core/src/main/kotlin/com/bugdigger/core/project/BtsProjectFile.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.core.project

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BtsProjectFileTest {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private fun makeClass(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    @Test
    fun `round-trips manifest, classes, and json entries`(@TempDir dir: Path) {
        val out = dir.resolve("project.bts").toFile()
        val manifest = ProjectManifest(
            displayName = "test",
            sourceKind = SourceKind.JAR,
            originalPath = "/path/to/sample.jar",
            createdAt = 12345L,
            bytesightVersion = "0.1.0",
        )
        val classBytes = mapOf(
            "com.example.Foo" to makeClass("com/example/Foo"),
            "com.example.Bar" to makeClass("com/example/Bar"),
        )
        val jsonEntries = mapOf(
            "renames.json" to """{"com.example.a":"UserService"}""",
            "comments.json" to "[]",
        )

        BtsProjectFile.write(out, manifest, classBytes, jsonEntries, json)

        val read = BtsProjectFile.open(out)
        try {
            assertEquals(manifest, read.readManifest(json))
            assertEquals(setOf("com.example.Foo", "com.example.Bar"), read.listClassEntries())
            assertTrue(read.readClass("com.example.Foo")!!.contentEquals(classBytes["com.example.Foo"]!!))
            assertEquals(jsonEntries["renames.json"], read.readJsonEntry("renames.json"))
            assertEquals(jsonEntries["comments.json"], read.readJsonEntry("comments.json"))
            assertNull(read.readJsonEntry("missing.json"))
        } finally {
            read.close()
        }
    }

    @Test
    fun `write is atomic - failure does not corrupt existing file`(@TempDir dir: Path) {
        val out = dir.resolve("project.bts").toFile()
        // Pre-write a valid project
        BtsProjectFile.write(out,
            ProjectManifest("v1", SourceKind.JAR, null, 1L, "0.0"),
            mapOf("a.B" to makeClass("a/B")),
            emptyMap(), json)
        val originalSize = out.length()

        // Force failure during write by giving an unreadable class map (size mismatch)
        runCatching {
            BtsProjectFile.write(out,
                ProjectManifest("v2", SourceKind.JAR, null, 2L, "0.0"),
                emptyMap(),  // No classes — write should reject (require classes.isNotEmpty())
                emptyMap(), json)
        }

        // Original file untouched
        val reread = BtsProjectFile.open(out)
        try {
            assertEquals("v1", reread.readManifest(json).displayName)
            assertEquals(originalSize, out.length())
        } finally { reread.close() }
    }

    @Test
    fun `readManifest fails on missing manifest`(@TempDir dir: Path) {
        // Construct a "bad" zip with no manifest.json
        val out = dir.resolve("bad.bts").toFile()
        java.util.zip.ZipOutputStream(out.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("classes/a/B.class"))
            zip.write(makeClass("a/B"))
            zip.closeEntry()
        }

        val read = BtsProjectFile.open(out)
        try {
            val ex = runCatching { read.readManifest(json) }.exceptionOrNull()
            assertNotNull(ex)
            assertTrue(ex is IllegalStateException || ex is java.io.IOException)
        } finally { read.close() }
    }
}
```

- [ ] **Step 2: Run the test — confirm fail**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.project.BtsProjectFileTest"
```

Expected: COMPILATION FAILED.

- [ ] **Step 3: Implement `BtsProjectFile`**

Create `core/src/main/kotlin/com/bugdigger/core/project/BtsProjectFile.kt`:

```kotlin
package com.bugdigger.core.project

import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val MANIFEST_ENTRY = "manifest.json"
private const val CLASSES_PREFIX = "classes/"
private const val CLASS_SUFFIX = ".class"

/**
 * `.bts` project file = ZIP container.
 *
 * - [open] mounts an existing file for reading.
 * - [write] produces a new file atomically (writes to `.tmp` next door, then
 *   moves over the destination).
 *
 * Class entries are stored under `classes/<slash/path>/<Name>.class` so that
 * the resulting zip is also a valid JAR — which means [JarReader] from
 * [com.bugdigger.core.source] can read it directly when needed for diff
 * scenarios that load two `.bts` files.
 */
class BtsProjectFile private constructor(
    private val zip: ZipFile,
    private val sourceFile: File,
) : Closeable {

    fun readManifest(json: Json): ProjectManifest {
        val entry = zip.getEntry(MANIFEST_ENTRY)
            ?: throw IllegalStateException("Project file missing $MANIFEST_ENTRY")
        val text = zip.getInputStream(entry).bufferedReader().use { it.readText() }
        return json.decodeFromString(ProjectManifest.serializer(), text)
    }

    fun listClassEntries(): Set<String> = buildSet {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (e.isDirectory) continue
            if (!e.name.startsWith(CLASSES_PREFIX) || !e.name.endsWith(CLASS_SUFFIX)) continue
            val internal = e.name.removePrefix(CLASSES_PREFIX).removeSuffix(CLASS_SUFFIX)
            add(internal.replace('/', '.'))
        }
    }

    fun readClass(fqn: String): ByteArray? {
        val name = CLASSES_PREFIX + fqn.replace('.', '/') + CLASS_SUFFIX
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun readJsonEntry(name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).bufferedReader().use { it.readText() }
    }

    override fun close() = zip.close()

    val file: File get() = sourceFile

    companion object {
        fun open(file: File): BtsProjectFile {
            require(file.exists()) { "Project file not found: $file" }
            return BtsProjectFile(ZipFile(file), file)
        }

        /**
         * Writes a project file atomically. Throws if [classes] is empty —
         * a project with no class data is malformed.
         */
        fun write(
            destination: File,
            manifest: ProjectManifest,
            classes: Map<String, ByteArray>,
            jsonEntries: Map<String, String>,
            json: Json,
        ) {
            require(classes.isNotEmpty()) { "Cannot write a project with no classes" }
            val tmp = File(destination.parentFile, destination.name + ".tmp")
            tmp.outputStream().use { fos ->
                ZipOutputStream(fos).use { zip ->
                    // 1. Manifest
                    val manifestText = json.encodeToString(ProjectManifest.serializer(), manifest)
                    zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                    zip.write(manifestText.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    // 2. Classes
                    for ((fqn, bytes) in classes) {
                        val entryName = CLASSES_PREFIX + fqn.replace('.', '/') + CLASS_SUFFIX
                        zip.putNextEntry(ZipEntry(entryName))
                        zip.write(bytes)
                        zip.closeEntry()
                    }

                    // 3. Optional JSON entries
                    for ((name, content) in jsonEntries) {
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(content.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }
                }
            }
            // Atomic move
            Files.move(tmp.toPath(), destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
```

- [ ] **Step 4: Run the test**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.project.BtsProjectFileTest"
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/bugdigger/core/project/BtsProjectFile.kt core/src/test/kotlin/com/bugdigger/core/project/BtsProjectFileTest.kt
git commit -m "feat(core/project): add BtsProjectFile reader/writer (atomic)"
```

---

## Task 4: `BtsProjectClassSource` (TDD)

A `ClassSource` impl backed by an open `.bts`. It's essentially `JarClassSource` over a `BtsProjectFile`, since the class layout matches a JAR.

**Files:**
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/BtsProjectClassSourceTest.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/BtsProjectClassSource.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.project.BtsProjectFile
import com.bugdigger.core.project.ProjectManifest
import com.bugdigger.core.project.SourceKind
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BtsProjectClassSourceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `lists classes from a .bts file and returns bytecode`(@TempDir dir: Path) = runTest {
        val bts = dir.resolve("p.bts").toFile()
        val foo = makeClass("com/example/Foo")
        BtsProjectFile.write(bts,
            ProjectManifest("p", SourceKind.JAR, null, 0L, "0.0"),
            mapOf("com.example.Foo" to foo),
            emptyMap(), json)

        val source = BtsProjectClassSource.open(bts, StaticHierarchyExtractor(), json)
        try {
            assertEquals(Capability.STATIC_ONLY, source.capabilities)
            assertTrue(source.displayName.contains("p.bts") || source.displayName.contains("p"))
            val list = source.listClasses().getOrNull()!!
            assertEquals(setOf("com.example.Foo"), list.map { it.name }.toSet())
            assertTrue(source.getBytecode("com.example.Foo").getOrNull()!!.contentEquals(foo))
        } finally { source.close() }
    }

    private fun makeClass(internal: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }
}
```

- [ ] **Step 2: Run — confirm fail**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.BtsProjectClassSourceTest"
```

- [ ] **Step 3: Implement**

Create `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/BtsProjectClassSource.kt`:

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.project.BtsProjectFile
import com.bugdigger.core.project.ProjectManifest
import com.bugdigger.protocol.ClassInfo
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [ClassSource] backed by an open `.bts` project file. Capabilities are
 * always [Capability.STATIC_ONLY] — even if the project was originally
 * captured from a live agent, replaying the live RPCs against a snapshot
 * is out of scope.
 */
class BtsProjectClassSource private constructor(
    private val projectFile: BtsProjectFile,
    private val manifest: ProjectManifest,
    hierarchyExtractor: StaticHierarchyExtractor,
) : ClassSource {

    override val capabilities: Set<Capability> = Capability.STATIC_ONLY

    override val displayName: String = "${manifest.displayName} (${projectFile.file.name})"

    private val classNames: List<String> = projectFile.listClassEntries().sorted()

    private val classInfos: List<ClassInfo> by lazy {
        classNames.mapNotNull { fqn ->
            val bytes = projectFile.readClass(fqn) ?: return@mapNotNull null
            runCatching {
                val md = hierarchyExtractor.extract(bytes)
                StaticClassInfoMapper.toClassInfo(md, classLoaderName = "BtsProject(${projectFile.file.name})")
            }.getOrNull()
        }
    }

    /** The opened project file — used by ProjectService for round-trip Save behavior. */
    fun underlyingProjectFile(): BtsProjectFile = projectFile

    /** Manifest as read on open. */
    fun manifest(): ProjectManifest = manifest

    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> =
        Result.success(classInfos)

    override suspend fun getBytecode(className: String): Result<ByteArray> =
        projectFile.readClass(className)?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Class not found: $className"))

    override fun close() {
        projectFile.close()
    }

    companion object {
        fun open(file: File, hierarchyExtractor: StaticHierarchyExtractor, json: Json): BtsProjectClassSource {
            val projectFile = BtsProjectFile.open(file)
            val manifest = projectFile.readManifest(json)
            return BtsProjectClassSource(projectFile, manifest, hierarchyExtractor)
        }
    }
}
```

- [ ] **Step 4: Run the test**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.BtsProjectClassSourceTest"
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/BtsProjectClassSource.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/BtsProjectClassSourceTest.kt
git commit -m "feat(source): add BtsProjectClassSource"
```

---

## Task 5: Serialize `RenameStore` (TDD)

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/RenameStore.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/RenameStoreSerializationTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.bytesight.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RenameStoreSerializationTest {

    @Test
    fun `serialize then restore preserves all renames`() {
        val original = RenameStore().apply {
            rename("com.example.a", "UserService")
            rename("com.example.b#c()V", "getUser")
            rename("com.example.b#field", "userId")
        }

        val text = original.serialize()
        val restored = RenameStore().apply { restore(text) }

        assertEquals(original.renameMap.value, restored.renameMap.value)
    }

    @Test
    fun `restore on empty json yields empty store`() {
        val store = RenameStore().apply { rename("a", "B") }
        store.restore("{}")
        assertEquals(emptyMap<String, String>(), store.renameMap.value)
    }
}
```

- [ ] **Step 2: Add the methods**

In `RenameStore.kt`:

```kotlin
import kotlinx.serialization.json.Json

class RenameStore {
    // ...existing fields and methods unchanged...

    fun serialize(json: Json = DEFAULT_JSON): String =
        json.encodeToString(MapStringSerializer, _renames.value)

    fun restore(text: String, json: Json = DEFAULT_JSON) {
        val map: Map<String, String> = json.decodeFromString(MapStringSerializer, text)
        _renames.value = map
    }

    companion object {
        // existing shortName(...)
        private val DEFAULT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
        private val MapStringSerializer = kotlinx.serialization.builtins.MapSerializer(
            kotlinx.serialization.builtins.serializer<String>(),
            kotlinx.serialization.builtins.serializer<String>(),
        )
    }
}
```

- [ ] **Step 3: Run the test**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.service.RenameStoreSerializationTest"
```

Expected: 2 PASS.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/RenameStore.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/RenameStoreSerializationTest.kt
git commit -m "feat(rename): add RenameStore serialize/restore"
```

---

## Task 6: Serialize `CommentStore` (TDD)

`MethodKey` and `MethodComments` already exist; we just need to add (de)serialization. Use a flat list-of-records form for stability.

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/CommentStore.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/CommentStoreSerializationTest.kt`

- [ ] **Step 1: Test**

```kotlin
package com.bugdigger.bytesight.service

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class CommentStoreSerializationTest {

    @Test
    fun `round-trips block-level and instruction-level comments`() {
        val original = CommentStore().apply {
            setBlockComment(MethodKey("a.B", "foo", "(I)V"), "block_3", "tricky branch")
            setInstructionComment(MethodKey("a.B", "foo", "(I)V"), 42, "magic ldc")
            setInstructionComment(MethodKey("a.B", "bar", "()V"), 0, "entry")
        }

        val text = original.serialize()
        val restored = CommentStore().apply { restore(text) }

        for (key in original.state.value.keys) {
            assertEquals(original.commentsFor(key), restored.commentsFor(key))
        }
        assertEquals(original.state.value.size, restored.state.value.size)
    }
}
```

- [ ] **Step 2: Implement**

In `CommentStore.kt`:

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
private data class SerializedCommentEntry(
    val className: String,
    val methodName: String,
    val descriptor: String,
    val blockLevel: Map<String, String> = emptyMap(),
    val instructionLevel: Map<Int, String> = emptyMap(),
)

class CommentStore {
    // ...existing fields and methods...

    fun serialize(json: Json = DEFAULT_JSON): String {
        val entries = _state.value.map { (key, value) ->
            SerializedCommentEntry(
                className = key.className,
                methodName = key.methodName,
                descriptor = key.descriptor,
                blockLevel = value.blockLevel,
                instructionLevel = value.instructionLevel,
            )
        }
        return json.encodeToString(ListSerializer(SerializedCommentEntry.serializer()), entries)
    }

    fun restore(text: String, json: Json = DEFAULT_JSON) {
        val entries: List<SerializedCommentEntry> =
            json.decodeFromString(ListSerializer(SerializedCommentEntry.serializer()), text)
        _state.value = entries.associate { e ->
            MethodKey(e.className, e.methodName, e.descriptor) to
                MethodComments(blockLevel = e.blockLevel, instructionLevel = e.instructionLevel)
        }
    }

    companion object {
        private val DEFAULT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.service.CommentStoreSerializationTest"
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/CommentStore.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/CommentStoreSerializationTest.kt
git commit -m "feat(comments): add CommentStore serialize/restore"
```

---

## Task 7: Serialize `DebuggerState` breakpoints (TDD)

We persist only static fields (id, class/method/signature/line/mode/enabled/condition/skipCount). Runtime fields (`hitCount`, `conditionError`) are always reset on load.

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/debugger/DebuggerState.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/debugger/DebuggerStateSerializationTest.kt`

- [ ] **Step 1: Test**

```kotlin
package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.MethodBreakpointMode
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DebuggerStateSerializationTest {

    @Test
    fun `round-trips persisted breakpoint fields and resets runtime fields`() {
        val original = DebuggerState().apply {
            addBreakpoint(DebuggerState.UiBreakpoint(
                id = "bp-1",
                className = "a.B",
                methodName = "foo",
                methodSignature = "()V",
                displayLine = 42,
                mode = MethodBreakpointMode.METHOD_BP_ENTRY,
                enabled = true,
                condition = "x > 0",
                skipCount = 3,
                hitCount = 999,                // runtime — should NOT survive
                conditionError = "uh oh",      // runtime — should NOT survive
            ))
        }

        val text = original.serialize()
        val restored = DebuggerState().apply { restore(text) }
        val bp = restored.breakpoints.value.first()

        assertEquals("bp-1", bp.id)
        assertEquals("a.B", bp.className)
        assertEquals(42, bp.displayLine)
        assertEquals("x > 0", bp.condition)
        assertEquals(3, bp.skipCount)
        assertEquals(0, bp.hitCount)            // reset
        assertEquals(null, bp.conditionError)   // reset
    }
}
```

- [ ] **Step 2: Implement**

In `DebuggerState.kt`:

```kotlin
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class DebuggerState {
    // ... existing fields/methods ...

    @Serializable
    private data class SerializedBp(
        val id: String,
        val className: String,
        val methodName: String,
        val methodSignature: String,
        val displayLine: Int,
        val mode: String,           // store mode by name for forward-compat
        val enabled: Boolean,
        val condition: String,
        val skipCount: Int,
    )

    fun serialize(json: Json = DEFAULT_JSON): String {
        val list = _breakpoints.value.map { bp ->
            SerializedBp(
                id = bp.id, className = bp.className, methodName = bp.methodName,
                methodSignature = bp.methodSignature, displayLine = bp.displayLine,
                mode = bp.mode.name, enabled = bp.enabled,
                condition = bp.condition, skipCount = bp.skipCount,
            )
        }
        return json.encodeToString(ListSerializer(SerializedBp.serializer()), list)
    }

    fun restore(text: String, json: Json = DEFAULT_JSON) {
        val list: List<SerializedBp> = json.decodeFromString(ListSerializer(SerializedBp.serializer()), text)
        _breakpoints.value = list.map { s ->
            UiBreakpoint(
                id = s.id, className = s.className, methodName = s.methodName,
                methodSignature = s.methodSignature, displayLine = s.displayLine,
                mode = MethodBreakpointMode.valueOf(s.mode),
                enabled = s.enabled, condition = s.condition, skipCount = s.skipCount,
                hitCount = 0, conditionError = null,
            )
        }
    }

    companion object {
        private val DEFAULT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.debugger.DebuggerStateSerializationTest"
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/debugger/DebuggerState.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/debugger/DebuggerStateSerializationTest.kt
git commit -m "feat(debugger): add DebuggerState serialize/restore for breakpoints"
```

---

## Task 8: `ProjectService` — orchestrate save & load (TDD)

This is the glue. Save: snapshot every class from the active `ClassSource`, dump stores, write `.bts`. Load: open the `.bts`, restore stores, install a `BtsProjectClassSource` on the registry.

**Files:**
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/ProjectServiceTest.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ProjectService.kt`

- [ ] **Step 1: Test**

```kotlin
package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.source.Capability
import com.bugdigger.bytesight.source.FakeClassSource
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.protocol.ClassInfo
import com.bugdigger.protocol.MethodBreakpointMode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import kotlin.test.assertEquals

class ProjectServiceTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `save then load preserves classes, renames, comments, breakpoints`(@TempDir dir: Path) = runTest {
        // Given: a registry with a fake live source, populated stores
        val registry = ConnectionRegistry()
        val rename = RenameStore().apply { rename("com.example.a", "Renamed") }
        val comment = CommentStore().apply { setBlockComment(MethodKey("a.B", "foo", "()V"), "blk_1", "hi") }
        val debug = DebuggerState().apply {
            addBreakpoint(DebuggerState.UiBreakpoint(
                id = "x", className = "a.B", methodName = "foo",
                methodSignature = "()V", displayLine = 5,
                mode = MethodBreakpointMode.METHOD_BP_ENTRY, enabled = true,
            ))
        }
        val classes = mapOf(
            "com.example.Foo" to makeClass("com/example/Foo"),
            "a.B" to makeClass("a/B"),
        )
        val info = listOf(
            ClassInfo.newBuilder().setName("com.example.Foo").build(),
            ClassInfo.newBuilder().setName("a.B").build(),
        )
        registry.setSource(FakeClassSource(info, classes, capabilities = Capability.ALL), connectionKey = "live")

        val service = ProjectService(registry, rename, comment, debug, StaticHierarchyExtractor(), json,
            bytesightVersion = "test")

        val out = dir.resolve("p.bts").toFile()
        service.saveAs(out, displayName = "Test").getOrThrow()

        // Wipe stores to simulate fresh app start
        rename.clearAll()
        comment.state.value
        debug.setBreakpoints(emptyList())
        registry.setSource(null, null)

        // When: load
        service.load(out).getOrThrow()

        // Then
        assertEquals("Renamed", rename.renameMap.value["com.example.a"])
        assertEquals("hi", comment.commentsFor(MethodKey("a.B", "foo", "()V")).blockLevel["blk_1"])
        assertEquals(1, debug.breakpoints.value.size)
        assertEquals("x", debug.breakpoints.value.first().id)
        assertEquals(Capability.STATIC_ONLY, registry.classSource.value!!.capabilities)
        val classNames = registry.classSource.value!!.listClasses().getOrThrow().map { it.name }.toSet()
        assertEquals(setOf("com.example.Foo", "a.B"), classNames)
    }

    private fun makeClass(internal: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internal, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }
}
```

(`FakeClassSource` from Plan 1 Task 9 is reused; if missing, create as described there.)

- [ ] **Step 2: Implement**

Create `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ProjectService.kt`:

```kotlin
package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.source.BtsProjectClassSource
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.project.BtsProjectFile
import com.bugdigger.core.project.ProjectManifest
import com.bugdigger.core.project.SourceKind
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Save & load of `.bts` project files. All disk I/O is wrapped in [Result].
 *
 * Save flow: snapshot every class from the active [com.bugdigger.bytesight.source.ClassSource],
 * dump rename/comment/breakpoint JSON, write a [BtsProjectFile] atomically.
 *
 * Load flow: open the .bts, restore stores from JSON entries (best-effort —
 * missing entries leave that store empty), install a [BtsProjectClassSource]
 * on the registry. The previously-active source is closed.
 */
class ProjectService(
    private val connectionRegistry: ConnectionRegistry,
    private val renameStore: RenameStore,
    private val commentStore: CommentStore,
    private val debuggerState: DebuggerState,
    private val hierarchyExtractor: StaticHierarchyExtractor,
    private val json: Json,
    private val bytesightVersion: String,
) {

    private val logger = LoggerFactory.getLogger(ProjectService::class.java)

    /** Save the current session as a new `.bts` file. */
    suspend fun saveAs(destination: File, displayName: String): Result<Unit> = runCatching {
        val source = connectionRegistry.classSource.value
            ?: error("No active source to save")

        // Snapshot every class
        val list = source.listClasses(includeSystemClasses = false).getOrThrow()
        val classes = mutableMapOf<String, ByteArray>()
        for (info in list) {
            source.getBytecode(info.name)
                .onSuccess { classes[info.name] = it }
                .onFailure { logger.warn("Skipping ${info.name}: ${it.message}") }
        }
        require(classes.isNotEmpty()) { "Nothing to save — no class bytecode collected" }

        val manifest = ProjectManifest(
            displayName = displayName,
            sourceKind = inferSourceKind(source),
            originalPath = source.displayName,
            createdAt = System.currentTimeMillis(),
            bytesightVersion = bytesightVersion,
            sidecars = emptyList(),
        )

        val jsonEntries = mapOf(
            "renames.json" to renameStore.serialize(json),
            "comments.json" to commentStore.serialize(json),
            "breakpoints.json" to debuggerState.serialize(json),
        )

        BtsProjectFile.write(destination, manifest, classes, jsonEntries, json)
        logger.info("Saved project: $destination (${classes.size} classes)")
    }

    /** Load a `.bts` and install it as the active source. */
    suspend fun load(file: File): Result<Unit> = runCatching {
        val source = BtsProjectClassSource.open(file, hierarchyExtractor, json)

        // Restore stores. Each is best-effort: a missing/corrupt entry just resets that store.
        runCatching {
            source.underlyingProjectFile().readJsonEntry("renames.json")?.let { renameStore.restore(it, json) }
                ?: renameStore.clearAll()
        }.onFailure { logger.warn("Failed to restore renames: ${it.message}"); renameStore.clearAll() }

        runCatching {
            source.underlyingProjectFile().readJsonEntry("comments.json")?.let { commentStore.restore(it, json) }
                ?: commentStore.state.value
        }.onFailure { logger.warn("Failed to restore comments: ${it.message}") }

        runCatching {
            source.underlyingProjectFile().readJsonEntry("breakpoints.json")?.let { debuggerState.restore(it, json) }
                ?: debuggerState.setBreakpoints(emptyList())
        }.onFailure {
            logger.warn("Failed to restore breakpoints: ${it.message}")
            debuggerState.setBreakpoints(emptyList())
        }

        connectionRegistry.setSource(source, connectionKey = null)
        logger.info("Loaded project: $file")
    }

    private fun inferSourceKind(source: com.bugdigger.bytesight.source.ClassSource): SourceKind {
        val name = source::class.simpleName.orEmpty()
        return when {
            name.contains("Agent") -> SourceKind.LIVE_SNAPSHOT
            name.contains("Apk") -> SourceKind.APK
            name.contains("Bts") -> SourceKind.BTS
            else -> SourceKind.JAR
        }
    }
}
```

- [ ] **Step 3: Run + commit**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.service.ProjectServiceTest"
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/service/ProjectService.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/service/ProjectServiceTest.kt
git commit -m "feat(project): add ProjectService for .bts save/load"
```

---

## Task 9: Wire ProjectService into Koin

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt`

- [ ] **Step 1: Register**

```kotlin
import com.bugdigger.bytesight.service.ProjectService
import kotlinx.serialization.json.Json

// somewhere in appModule:
single { Json { prettyPrint = true; ignoreUnknownKeys = true } }
single {
    ProjectService(
        connectionRegistry = get(),
        renameStore = get(),
        commentStore = get(),
        debuggerState = get(),
        hierarchyExtractor = get(),
        json = get(),
        bytesightVersion = "0.1.0", // pull from BuildConfig if available
    )
}
```

- [ ] **Step 2: Verify**

```bash
.\gradlew.bat :composeApp:jvmTest
```

- [ ] **Step 3: Commit**

```bash
git commit -am "di: register Json and ProjectService"
```

---

## Task 10: File menu — New / Open / Save / Save As

Compose Desktop windows can carry a `MenuBar`. Add it to the application window in `Main.kt` (or wherever `application { Window(...) }` is constructed). Use AWT `FileDialog` for pickers (matches the JAR/APK pattern from Step 2).

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/Main.kt` (or the window-construction file — check the project)
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/menubar/MainMenuBar.kt`

- [ ] **Step 1: Create the menu bar**

```kotlin
package com.bugdigger.bytesight.ui.menubar

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import com.bugdigger.bytesight.service.ProjectService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

@Composable
fun FrameWindowScope.MainMenuBar(
    projectService: ProjectService,
    scope: CoroutineScope,
    currentFile: MutableState<File?>,
    onError: (String) -> Unit,
) {
    MenuBar {
        Menu("File", mnemonic = 'F') {
            Item("Open .bts…", mnemonic = 'O', onClick = {
                pickFile("Open Project", "*.bts", FileDialog.LOAD)?.let { f ->
                    scope.launch {
                        projectService.load(f).onSuccess { currentFile.value = f }
                            .onFailure { onError("Open failed: ${it.message}") }
                    }
                }
            })
            Item("Save", mnemonic = 'S', onClick = {
                val target = currentFile.value
                if (target != null) {
                    scope.launch {
                        projectService.saveAs(target, target.nameWithoutExtension)
                            .onFailure { onError("Save failed: ${it.message}") }
                    }
                } else {
                    pickFile("Save Project As", "project.bts", FileDialog.SAVE)?.let { f ->
                        val withExt = if (f.extension.equals("bts", ignoreCase = true)) f
                            else File(f.parentFile, "${f.name}.bts")
                        scope.launch {
                            projectService.saveAs(withExt, withExt.nameWithoutExtension)
                                .onSuccess { currentFile.value = withExt }
                                .onFailure { onError("Save failed: ${it.message}") }
                        }
                    }
                }
            })
            Item("Save As…", onClick = {
                pickFile("Save Project As", "project.bts", FileDialog.SAVE)?.let { f ->
                    val withExt = if (f.extension.equals("bts", ignoreCase = true)) f
                        else File(f.parentFile, "${f.name}.bts")
                    scope.launch {
                        projectService.saveAs(withExt, withExt.nameWithoutExtension)
                            .onSuccess { currentFile.value = withExt }
                            .onFailure { onError("Save failed: ${it.message}") }
                    }
                }
            })
        }
    }
}

private fun pickFile(title: String, defaultName: String, mode: Int): File? {
    val dlg = FileDialog(null as Frame?, title, mode)
    dlg.file = defaultName
    dlg.isVisible = true
    val name = dlg.file ?: return null
    val dir = dlg.directory ?: return null
    return File(dir, name)
}
```

- [ ] **Step 2: Install the menu in the application window**

Find `Main.kt` (or wherever `application { Window(...) }` is). Inside the `Window { ... }` lambda (FrameWindowScope), add:

```kotlin
val projectService: ProjectService = koinInject()
val scope = rememberCoroutineScope()
val currentFile = remember { mutableStateOf<File?>(null) }
var menuError by remember { mutableStateOf<String?>(null) }

MainMenuBar(projectService, scope, currentFile, onError = { menuError = it })

App() // existing

menuError?.let { msg ->
    // Optionally show a Toast/Snackbar; for v1 a simple log is fine.
    // The Attach screen's existing ErrorCard pattern can be reused later.
}
```

- [ ] **Step 3: Run the desktop app and verify File menu works**

```bash
.\gradlew.bat :composeApp:run
```

- Attach → make some renames + comments → File → Save As → `test.bts`
- Restart the app
- File → Open → `test.bts`
- Sidebar shows static-only caps (Trace/Heap/Debugger disabled)
- Classes / Hierarchy / Inspector show the saved data
- Renames + comments visible

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/menubar/ composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/Main.kt
git commit -m "feat(ui): File menu with New/Open/Save/Save As for .bts"
```

---

## Task 11: Sidecar formats — `.btstrace` and `.btsheap` (skeleton only)

Define the file formats so they exist before any UI to write/read them in this plan. UI-level Save/Load for trace and heap can come later — the formats stand alone.

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/debugger/TraceRecordingFile.kt`
- (Optional) Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/heap/HeapSnapshotFile.kt`

- [ ] **Step 1: `.btstrace`**

Mirrors `RecordingFile`'s exact pattern (length-prefixed protobuf):

```kotlin
package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.MethodTraceEvent
import java.nio.file.Files
import java.nio.file.Path

/**
 * `.btstrace` = length-prefixed stream of [MethodTraceEvent] via
 * `writeDelimitedTo` / `parseDelimitedFrom`. Same shape as `.btsrec`.
 */
object TraceRecordingFile {

    fun saveTo(path: Path, events: List<MethodTraceEvent>) {
        Files.newOutputStream(path).use { out ->
            for (e in events) e.writeDelimitedTo(out)
        }
    }

    fun loadFrom(path: Path): List<MethodTraceEvent> {
        val out = mutableListOf<MethodTraceEvent>()
        Files.newInputStream(path).use { input ->
            while (true) {
                val e = MethodTraceEvent.parseDelimitedFrom(input) ?: break
                out.add(e)
            }
        }
        return out
    }
}
```

- [ ] **Step 2: Add a small test for `TraceRecordingFile`**

`composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/debugger/TraceRecordingFileTest.kt`:

```kotlin
package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.MethodTraceEvent
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals

class TraceRecordingFileTest {
    @Test
    fun `round trips events`(@TempDir dir: Path) {
        val file = dir.resolve("rec.btstrace")
        val events = listOf(
            MethodTraceEvent.newBuilder().setClassName("a.B").setMethodName("foo").build(),
            MethodTraceEvent.newBuilder().setClassName("a.B").setMethodName("bar").build(),
        )
        TraceRecordingFile.saveTo(file, events)
        val read = TraceRecordingFile.loadFrom(file)
        assertEquals(events.size, read.size)
        assertEquals("a.B", read[0].className)
    }
}
```

(Heap snapshot file can wait — it has no obvious framework structure yet. Skip Task 11 Step 3 until we wire trace/heap UI buttons.)

- [ ] **Step 3: Run + commit**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.debugger.TraceRecordingFileTest"
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/debugger/TraceRecordingFile.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/debugger/TraceRecordingFileTest.kt
git commit -m "feat(debugger): add .btstrace sidecar format (mirror .btsrec)"
```

---

## Task 12: End-to-end smoke + integration

- [ ] **Step 1: Run all tests**

```bash
.\gradlew.bat :core:test :composeApp:jvmTest
```

- [ ] **Step 2: Manual round-trip walk-through**

Build & run:

```bash
.\gradlew.bat :sample:jar :agent:agentJar
.\gradlew.bat :composeApp:run
```

In another shell: `java -jar sample/build/libs/sample-*.jar`

In Bytesight:
1. Attach to the live sample.
2. Browse a few classes; rename one (e.g. via Inspector or wherever rename is wired); add a method comment.
3. Set a breakpoint via the Inspector gutter.
4. File → Save As → `~/sample-session.bts`. Verify file size is sensible (a few hundred KB to single-digit MB for a small sample).
5. Disconnect from the live sample (or kill the process).
6. File → Open → `~/sample-session.bts`.
7. Verify:
   - Sidebar gates Trace/Heap/Debugger off (it's now a static-mode session).
   - Classes tab populated.
   - Inspector shows the saved rename and the saved comment.
   - Debugger tab is disabled but the breakpoint persists in the underlying state (verify via DebuggerState by reattaching to a new live JVM and checking the list).

- [ ] **Step 3: Tag**

```bash
git tag step-3-save-load-complete
```

---

## Verification

| Invariant | Check |
|---|---|
| `.bts` is a self-contained snapshot (no external refs needed) | Manual: copy `test.bts` to a different folder, open it, verify nothing broken |
| Round-trip preserves bytecode byte-equality | `BtsProjectFileTest::round-trips` |
| Stores survive save/load | `ProjectServiceTest` |
| API keys never leak into project files | grep: open any `.bts` in a hex viewer / `7z l`, confirm no key strings; no apiKey field is in the manifest by design |
| Trace/Heap data not in project file | Manual inspection; sidecars only |

## What's intentionally not done

- Re-attaching to a previously-live process based on a saved descriptor (we chose frozen snapshot during brainstorming)
- Heap snapshot sidecar UI (`.btsheap` file format added separately when needed)
- Project format migration tooling (only one format version exists; first migration we need will land alongside `formatVersion = 2`)
- Sharing/collab features (the project file IS the sharing artifact — anything beyond that is out of scope)
