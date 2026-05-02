# Multi-source — Step 2: Static JAR + APK — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two new `ClassSource` implementations — one for `.jar` files and one for `.apk` files — so the user can pick "Open JAR…" / "Open APK…" on the Attach screen and run static analysis (Classes / Hierarchy / Inspector / Strings / AI) without any agent. Tabs that need a live JVM (Trace / Heap / Debugger) automatically disable themselves because static sources declare only the `STATIC_ANALYSIS` capability (gating from Step 1).

**Architecture:** Two layers:
- **`core`** gets pure analysis primitives: a JAR reader (`JarReader`), a static metadata extractor (`StaticHierarchyExtractor` — replaces what the agent does today using `Class.forName`/reflection), and a DEX-to-JVM-bytecode converter (`DexToJvmConverter`). These have **no dependency on `protocol` or any UI**.
- **`composeApp`** gets the `ClassSource` glue: `JarClassSource` (composes `JarReader` + `StaticHierarchyExtractor` and produces `protocol.ClassInfo`) and `ApkClassSource` (`DexToJvmConverter` + `JarClassSource`). The Attach screen gets two file-picker buttons.

**Tech Stack:** Kotlin, ASM 9.7.1 (already on `core`'s classpath), and one new dependency for DEX→JVM conversion: `com.googlecode.d2j:d2j-jar2dex` family — specifically `com.googlecode.d2j:d2j-core` plus `com.googlecode.d2j:dex-tools` (jar2dex + dex-translator). Alternatives evaluated: `jadx-core` (also active, but its public API is less stable than dex-tools'). Pick is `dex-tools`; if integration trouble arises during Task 6, swap behind the `DexToJvmConverter` interface — only that one file changes.

**Module changes:** `core` (new analysis), `composeApp` (sources + UI).

---

## File Structure

**New files (`core`):**
- `core/src/main/kotlin/com/bugdigger/core/source/JarReader.kt` — enumerate `.class` entries from a `JarFile`
- `core/src/main/kotlin/com/bugdigger/core/source/DexToJvmConverter.kt` — DEX → JVM `.class` bytes via dex-tools
- `core/src/main/kotlin/com/bugdigger/core/analysis/StaticHierarchyExtractor.kt` — ASM-based metadata extractor (no reflection)
- `core/src/test/kotlin/com/bugdigger/core/source/JarReaderTest.kt`
- `core/src/test/kotlin/com/bugdigger/core/source/DexToJvmConverterTest.kt`
- `core/src/test/kotlin/com/bugdigger/core/analysis/StaticHierarchyExtractorTest.kt`

**New files (`composeApp`):**
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/JarClassSource.kt`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/ApkClassSource.kt`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/StaticClassInfoMapper.kt` — converts core's `StaticClassMetadata` → `protocol.ClassInfo`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/JarClassSourceTest.kt`
- `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/ApkClassSourceTest.kt` (gated by APK fixture availability)

**Modified files:**
- `gradle/libs.versions.toml` — add `dex-tools` versions + libraries
- `core/build.gradle.kts` — add dex-tools dependency
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachViewModel.kt` — add `openJar(path)` / `openApk(path)`
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachScreen.kt` — add "Open JAR…" / "Open APK…" buttons + file pickers
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt` — register `StaticHierarchyExtractor`, `JarReader`, `DexToJvmConverter` as singletons
- `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/App.kt` — `AttachScreen.onConnected` callback fires for any source, not just live agent

**Reused from Step 1:**
- `composeApp/.../source/ClassSource.kt`
- `composeApp/.../source/Capability.kt`
- `composeApp/.../source/AgentClassSource.kt` (for parallel reference)
- `composeApp/.../service/ConnectionRegistry.kt` — `setSource(source, key=null)` now meaningfully accepts `key=null` for static sources
- `composeApp/.../ui/navigation/Sidebar.kt` — capability gating already handles greying out runtime tabs

**Test commands:**
- `.\gradlew.bat :core:test` — for the core analysis primitives
- `.\gradlew.bat :composeApp:jvmTest` — for the new ClassSource impls and Attach VM changes
- `.\gradlew.bat :composeApp:run` — manual smoke

---

## Task 1: Add `dex-tools` dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `core/build.gradle.kts`

- [ ] **Step 1: Add version + library entries to `libs.versions.toml`**

In the `[versions]` block, add:

```toml
# DEX → JVM bytecode conversion (Android APK static analysis)
dexTools = "2.4"
```

In the `[libraries]` block, add:

```toml
# DEX → JVM
dex-tools = { module = "com.googlecode.d2j:dex-tools", version.ref = "dexTools" }
d2j-core = { module = "com.googlecode.d2j:d2j-core", version.ref = "dexTools" }
```

(If exact coordinate names differ on Maven Central at implementation time, search `https://search.maven.org/search?q=g:com.googlecode.d2j` and adjust. The Maven Central artifact for the active fork is published under `com.googlecode.d2j` group; alternatively look at `com.android.tools.smali:smali-dexlib2` + a community translator. The interface `DexToJvmConverter` isolates the choice.)

- [ ] **Step 2: Add the dependency to `core/build.gradle.kts`**

In the `dependencies { ... }` block, add:

```kotlin
// Android DEX → JVM bytecode conversion
implementation(libs.dex.tools)
implementation(libs.d2j.core)
```

- [ ] **Step 3: Run a clean build to verify resolution**

```bash
.\gradlew.bat :core:build
```

Expected: BUILD SUCCESSFUL with no unresolved-dependency errors.

If resolution fails, the version or coordinates need updating (see Step 1 note). Pick whichever published artifact resolves and rerun. Don't proceed past this gate without a working dependency.

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml core/build.gradle.kts
git commit -m "build(core): add dex-tools for APK static analysis"
```

---

## Task 2: `JarReader` — enumerate `.class` entries from a JAR (TDD)

**Files:**
- Create: `core/src/test/kotlin/com/bugdigger/core/source/JarReaderTest.kt`
- Create: `core/src/main/kotlin/com/bugdigger/core/source/JarReader.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.core.source

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JarReaderTest {

    @Test
    fun `enumerates class entries with FQN keys`(@TempDir dir: Path) {
        val jar = dir.resolve("test.jar")
        val foo = makeClassBytes("com/example/Foo")
        val bar = makeClassBytes("com/example/Bar")
        writeJar(jar, mapOf("com/example/Foo.class" to foo, "com/example/Bar.class" to bar))

        val reader = JarReader()
        val entries = reader.read(jar.toFile())

        assertEquals(setOf("com.example.Foo", "com.example.Bar"), entries.keys)
        assertTrue(entries["com.example.Foo"]!!.contentEquals(foo))
    }

    @Test
    fun `skips non-class entries`(@TempDir dir: Path) {
        val jar = dir.resolve("test.jar")
        writeJar(jar, mapOf(
            "com/example/Foo.class" to makeClassBytes("com/example/Foo"),
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray(),
            "resources/data.txt" to "hello".toByteArray(),
        ))

        val entries = JarReader().read(jar.toFile())

        assertEquals(setOf("com.example.Foo"), entries.keys)
    }

    @Test
    fun `skips module-info and inner-class entries by default`(@TempDir dir: Path) {
        val jar = dir.resolve("test.jar")
        writeJar(jar, mapOf(
            "com/example/Foo.class" to makeClassBytes("com/example/Foo"),
            "com/example/Foo\$Inner.class" to makeClassBytes("com/example/Foo\$Inner"),
            "module-info.class" to makeClassBytes("module-info"),
        ))

        val entries = JarReader().read(jar.toFile())

        // Inner classes ARE included (they're real classes); module-info is skipped.
        assertTrue("com.example.Foo" in entries.keys)
        assertTrue("com.example.Foo\$Inner" in entries.keys)
        assertTrue("module-info" !in entries.keys)
    }

    private fun makeClassBytes(internalName: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun writeJar(path: Path, entries: Map<String, ByteArray>) {
        path.outputStream().use { os ->
            JarOutputStream(os).use { jar ->
                for ((name, bytes) in entries) {
                    jar.putNextEntry(JarEntry(name))
                    jar.write(bytes)
                    jar.closeEntry()
                }
            }
        }
    }
}
```

- [ ] **Step 2: Run the test — confirm it fails because `JarReader` doesn't exist yet**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.source.JarReaderTest"
```

Expected: COMPILATION FAILED.

- [ ] **Step 3: Implement `JarReader`**

Create `core/src/main/kotlin/com/bugdigger/core/source/JarReader.kt`:

```kotlin
package com.bugdigger.core.source

import java.io.File
import java.util.jar.JarFile

/**
 * Reads `.class` entries from a JAR file into memory.
 *
 * Returned map is keyed by dot-separated FQN (e.g. `"com.example.Foo"`).
 * Non-class entries (resources, manifest, signatures) and `module-info`
 * are filtered out.
 *
 * Inner classes are included as separate entries with `$` retained in the
 * key (e.g. `"com.example.Foo$Inner"`) — that's how their bytecode
 * references them on disk and how the rest of Bytesight expects them.
 */
class JarReader {

    fun read(jarFile: File): Map<String, ByteArray> {
        require(jarFile.exists()) { "JAR not found: $jarFile" }
        val out = mutableMapOf<String, ByteArray>()
        JarFile(jarFile).use { jar ->
            for (entry in jar.entries()) {
                if (entry.isDirectory) continue
                val name = entry.name
                if (!name.endsWith(".class")) continue
                if (name == "module-info.class") continue

                val fqn = name
                    .removeSuffix(".class")
                    .replace('/', '.')
                jar.getInputStream(entry).use { input ->
                    out[fqn] = input.readBytes()
                }
            }
        }
        return out
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.source.JarReaderTest"
```

Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/bugdigger/core/source/JarReader.kt core/src/test/kotlin/com/bugdigger/core/source/JarReaderTest.kt
git commit -m "feat(core): add JarReader for static .class enumeration"
```

---

## Task 3: `StaticHierarchyExtractor` — ASM-based metadata extraction (TDD)

The agent today extracts class metadata via reflection (`clazz.getSuperclass()`, etc. in `BytesightAgentService.java`). For static-mode we can't reflect — there's no live `Class<?>`. Use ASM to read the same fields directly from bytecode.

**Files:**
- Create: `core/src/test/kotlin/com/bugdigger/core/analysis/StaticHierarchyExtractorTest.kt`
- Create: `core/src/main/kotlin/com/bugdigger/core/analysis/StaticHierarchyExtractor.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.core.analysis

import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticHierarchyExtractorTest {

    private val extractor = StaticHierarchyExtractor()

    @Test
    fun `extracts FQN, simpleName, and packageName`() {
        val bytes = makeClass("com/example/Foo", "java/lang/Object", interfaces = arrayOf())
        val md = extractor.extract(bytes)
        assertEquals("com.example.Foo", md.name)
        assertEquals("Foo", md.simpleName)
        assertEquals("com.example", md.packageName)
    }

    @Test
    fun `extracts superclass and interfaces`() {
        val bytes = makeClass(
            "com/example/MyList",
            superName = "java/util/AbstractList",
            interfaces = arrayOf("java/util/List", "java/io/Serializable"),
        )
        val md = extractor.extract(bytes)
        assertEquals("java.util.AbstractList", md.superName)
        assertEquals(listOf("java.util.List", "java.io.Serializable"), md.interfaces)
    }

    @Test
    fun `detects interface and enum flags`() {
        val iface = makeClass("a/B", "java/lang/Object", arrayOf(), accessFlags = Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT)
        val enumClass = makeClass("a/E", "java/lang/Enum", arrayOf(), accessFlags = Opcodes.ACC_ENUM)
        assertTrue(extractor.extract(iface).isInterface)
        assertFalse(extractor.extract(iface).isEnum)
        assertTrue(extractor.extract(enumClass).isEnum)
    }

    @Test
    fun `extracts methods with signature, return type, parameter types`() {
        val bytes = makeClassWithMethod(
            internalName = "a/B",
            methodName = "foo",
            descriptor = "(Ljava/lang/String;I)Ljava/util/List;",
        )
        val md = extractor.extract(bytes)
        val foo = md.methods.first { it.name == "foo" }
        assertEquals("(Ljava/lang/String;I)Ljava/util/List;", foo.descriptor)
        assertEquals("java.util.List", foo.returnType)
        assertEquals(listOf("java.lang.String", "int"), foo.parameterTypes)
    }

    @Test
    fun `extracts fields with name and type`() {
        val bytes = makeClassWithField("a/B", "name", "Ljava/lang/String;")
        val md = extractor.extract(bytes)
        val f = md.fields.first { it.name == "name" }
        assertEquals("java.lang.String", f.type)
    }

    private fun makeClass(
        internalName: String,
        superName: String = "java/lang/Object",
        interfaces: Array<String> = emptyArray(),
        accessFlags: Int = Opcodes.ACC_PUBLIC,
    ): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, accessFlags, internalName, null, superName, interfaces)
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun makeClassWithMethod(internalName: String, methodName: String, descriptor: String): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_MAXS or ClassWriter.COMPUTE_FRAMES)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        val mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, descriptor, null, null)
        mv.visitCode()
        mv.visitInsn(Opcodes.ACONST_NULL)
        mv.visitInsn(Opcodes.ARETURN)
        mv.visitMaxs(0, 0)
        mv.visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun makeClassWithField(internalName: String, fieldName: String, descriptor: String): ByteArray {
        val cw = ClassWriter(0)
        cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
        cw.visitField(Opcodes.ACC_PRIVATE, fieldName, descriptor, null, null).visitEnd()
        cw.visitEnd()
        return cw.toByteArray()
    }
}
```

- [ ] **Step 2: Run the test — confirm it fails**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.analysis.StaticHierarchyExtractorTest"
```

Expected: COMPILATION FAILED.

- [ ] **Step 3: Implement `StaticHierarchyExtractor`**

Create `core/src/main/kotlin/com/bugdigger/core/analysis/StaticHierarchyExtractor.kt`:

```kotlin
package com.bugdigger.core.analysis

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

/**
 * Mirrors what `BytesightAgentService.buildClassInfo` produces, but works on
 * raw `.class` bytes instead of a live `Class<?>` so it can power static-only
 * sources (JAR / APK / saved project file).
 *
 * Returns module-local data classes (no proto dependency in `core`). The
 * mapping to `protocol.ClassInfo` lives in `composeApp` (StaticClassInfoMapper).
 */
class StaticHierarchyExtractor {

    fun extract(bytecode: ByteArray): StaticClassMetadata {
        val reader = ClassReader(bytecode)

        var name = ""
        var superName: String? = null
        var interfaces: List<String> = emptyList()
        var modifiers = 0
        val methods = mutableListOf<StaticMethodMetadata>()
        val fields = mutableListOf<StaticFieldMetadata>()

        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            override fun visit(
                version: Int,
                access: Int,
                clsName: String,
                signature: String?,
                superClsName: String?,
                interfaceNames: Array<out String>?,
            ) {
                name = clsName.replace('/', '.')
                modifiers = access
                superName = superClsName
                    ?.takeIf { it != "java/lang/Object" || clsName == "java/lang/Object" }
                    ?.replace('/', '.')
                interfaces = interfaceNames?.map { it.replace('/', '.') } ?: emptyList()
            }

            override fun visitMethod(
                access: Int,
                methodName: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                methods.add(StaticMethodMetadata(
                    name = methodName,
                    descriptor = descriptor,
                    returnType = Type.getReturnType(descriptor).className,
                    parameterTypes = Type.getArgumentTypes(descriptor).map { it.className },
                    modifiers = access,
                    isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0,
                    isBridge = (access and Opcodes.ACC_BRIDGE) != 0,
                ))
                return null
            }

            override fun visitField(
                access: Int,
                fieldName: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                fields.add(StaticFieldMetadata(
                    name = fieldName,
                    descriptor = descriptor,
                    type = Type.getType(descriptor).className,
                    modifiers = access,
                    isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0,
                ))
                return null
            }
        }, ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)

        // Even superClass=Object is kept by the chain above for j.l.Object itself,
        // but most "real" classes we want to surface the actual super. The filter
        // earlier preserves null only for Object's own descriptor.
        if (superName == null && name != "java.lang.Object") {
            superName = "java.lang.Object"
        }

        val packageName = name.substringBeforeLast('.', missingDelimiterValue = "")
        val simpleName = name.substringAfterLast('.')

        return StaticClassMetadata(
            name = name,
            packageName = packageName,
            simpleName = simpleName,
            superName = superName,
            interfaces = interfaces,
            modifiers = modifiers,
            isInterface = (modifiers and Opcodes.ACC_INTERFACE) != 0,
            isEnum = (modifiers and Opcodes.ACC_ENUM) != 0,
            isAnnotation = (modifiers and Opcodes.ACC_ANNOTATION) != 0,
            isSynthetic = (modifiers and Opcodes.ACC_SYNTHETIC) != 0,
            methods = methods,
            fields = fields,
        )
    }
}

data class StaticClassMetadata(
    val name: String,
    val packageName: String,
    val simpleName: String,
    val superName: String?,
    val interfaces: List<String>,
    val modifiers: Int,
    val isInterface: Boolean,
    val isEnum: Boolean,
    val isAnnotation: Boolean,
    val isSynthetic: Boolean,
    val methods: List<StaticMethodMetadata>,
    val fields: List<StaticFieldMetadata>,
)

data class StaticMethodMetadata(
    val name: String,
    val descriptor: String,
    val returnType: String,
    val parameterTypes: List<String>,
    val modifiers: Int,
    val isSynthetic: Boolean,
    val isBridge: Boolean,
)

data class StaticFieldMetadata(
    val name: String,
    val descriptor: String,
    val type: String,
    val modifiers: Int,
    val isSynthetic: Boolean,
)
```

- [ ] **Step 4: Run the test to verify**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.analysis.StaticHierarchyExtractorTest"
```

Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/src/main/kotlin/com/bugdigger/core/analysis/StaticHierarchyExtractor.kt core/src/test/kotlin/com/bugdigger/core/analysis/StaticHierarchyExtractorTest.kt
git commit -m "feat(core): add StaticHierarchyExtractor (ASM, no reflection)"
```

---

## Task 4: `StaticClassInfoMapper` — adapt core metadata → `protocol.ClassInfo`

This adapter lives in `composeApp` because `core` doesn't depend on `protocol`.

**Files:**
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/StaticClassInfoMapper.kt`
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/StaticClassInfoMapperTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticClassMetadata
import com.bugdigger.core.analysis.StaticFieldMetadata
import com.bugdigger.core.analysis.StaticMethodMetadata
import org.junit.jupiter.api.Test
import org.objectweb.asm.Opcodes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticClassInfoMapperTest {

    @Test
    fun `maps top-level fields onto ClassInfo`() {
        val md = StaticClassMetadata(
            name = "com.example.Foo",
            packageName = "com.example",
            simpleName = "Foo",
            superName = "java.lang.Object",
            interfaces = listOf("java.io.Serializable"),
            modifiers = Opcodes.ACC_PUBLIC,
            isInterface = false,
            isEnum = false,
            isAnnotation = false,
            isSynthetic = false,
            methods = emptyList(),
            fields = emptyList(),
        )
        val info = StaticClassInfoMapper.toClassInfo(md, classLoaderName = "JarFile")
        assertEquals("com.example.Foo", info.name)
        assertEquals("com.example", info.packageName)
        assertEquals("Foo", info.simpleName)
        assertEquals("java.lang.Object", info.superclass)
        assertEquals(listOf("java.io.Serializable"), info.interfacesList)
        assertEquals("JarFile", info.classLoader)
        assertFalse(info.isInterface)
        assertFalse(info.isEnum)
    }

    @Test
    fun `maps methods to MethodInfo`() {
        val md = StaticClassMetadata(
            name = "a.B",
            packageName = "a",
            simpleName = "B",
            superName = "java.lang.Object",
            interfaces = emptyList(),
            modifiers = Opcodes.ACC_PUBLIC,
            isInterface = false, isEnum = false, isAnnotation = false, isSynthetic = false,
            methods = listOf(StaticMethodMetadata(
                name = "foo",
                descriptor = "(I)V",
                returnType = "void",
                parameterTypes = listOf("int"),
                modifiers = Opcodes.ACC_PUBLIC,
                isSynthetic = false,
                isBridge = false,
            )),
            fields = emptyList(),
        )
        val info = StaticClassInfoMapper.toClassInfo(md, "JarFile")
        val foo = info.methodsList.first()
        assertEquals("foo", foo.name)
        assertEquals("(I)V", foo.signature)
        assertEquals("void", foo.returnType)
        assertEquals(listOf("int"), foo.parameterTypesList)
    }

    @Test
    fun `maps fields to FieldInfo`() {
        val md = StaticClassMetadata(
            name = "a.B",
            packageName = "a", simpleName = "B",
            superName = "java.lang.Object",
            interfaces = emptyList(),
            modifiers = Opcodes.ACC_PUBLIC,
            isInterface = false, isEnum = false, isAnnotation = false, isSynthetic = false,
            methods = emptyList(),
            fields = listOf(StaticFieldMetadata(
                name = "count",
                descriptor = "I",
                type = "int",
                modifiers = Opcodes.ACC_PRIVATE,
                isSynthetic = false,
            )),
        )
        val info = StaticClassInfoMapper.toClassInfo(md, "JarFile")
        val f = info.fieldsList.first()
        assertEquals("count", f.name)
        assertEquals("int", f.type)
    }
}
```

- [ ] **Step 2: Implement the mapper**

Create `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/StaticClassInfoMapper.kt`:

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticClassMetadata
import com.bugdigger.core.analysis.StaticFieldMetadata
import com.bugdigger.core.analysis.StaticMethodMetadata
import com.bugdigger.protocol.ClassInfo
import com.bugdigger.protocol.FieldInfo
import com.bugdigger.protocol.MethodInfo

/** Adapter from core's pure-Kotlin static metadata types to the gRPC ClassInfo proto. */
object StaticClassInfoMapper {

    fun toClassInfo(md: StaticClassMetadata, classLoaderName: String): ClassInfo {
        val builder = ClassInfo.newBuilder()
            .setName(md.name)
            .setPackageName(md.packageName)
            .setSimpleName(md.simpleName)
            .setSuperclass(md.superName ?: "")
            .setModifiers(md.modifiers)
            .setIsInterface(md.isInterface)
            .setIsEnum(md.isEnum)
            .setIsAnnotation(md.isAnnotation)
            .setIsSynthetic(md.isSynthetic)
            .setLoadedAt(0L)
            .setClassLoader(classLoaderName)

        builder.addAllInterfaces(md.interfaces)
        md.methods.forEach { builder.addMethods(toMethodInfo(it)) }
        md.fields.forEach { builder.addFields(toFieldInfo(it)) }
        return builder.build()
    }

    private fun toMethodInfo(m: StaticMethodMetadata): MethodInfo =
        MethodInfo.newBuilder()
            .setName(m.name)
            .setSignature(m.descriptor)
            .setReturnType(m.returnType)
            .addAllParameterTypes(m.parameterTypes)
            .setModifiers(m.modifiers)
            .setIsSynthetic(m.isSynthetic)
            .setIsBridge(m.isBridge)
            .build()

    private fun toFieldInfo(f: StaticFieldMetadata): FieldInfo =
        FieldInfo.newBuilder()
            .setName(f.name)
            .setType(f.type)
            .setModifiers(f.modifiers)
            .setIsSynthetic(f.isSynthetic)
            .build()
}
```

- [ ] **Step 3: Run the test**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.StaticClassInfoMapperTest"
```

Expected: 3 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/StaticClassInfoMapper.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/StaticClassInfoMapperTest.kt
git commit -m "feat(source): adapter from StaticClassMetadata to protocol.ClassInfo"
```

---

## Task 5: `JarClassSource` (TDD)

Combines `JarReader` + `StaticHierarchyExtractor` + `StaticClassInfoMapper` behind the `ClassSource` interface.

**Files:**
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/JarClassSourceTest.kt`
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/JarClassSource.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.JarReader
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.outputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JarClassSourceTest {

    @Test
    fun `declares STATIC_ONLY capability`(@TempDir dir: Path) {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        assertEquals(Capability.STATIC_ONLY, source.capabilities)
        source.close()
    }

    @Test
    fun `displayName uses jar file name`(@TempDir dir: Path) {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        assertTrue(source.displayName.contains(jar.fileName.toString()))
        source.close()
    }

    @Test
    fun `listClasses returns ClassInfo for every class entry`(@TempDir dir: Path) = runTest {
        val jar = makeTinyJar(dir)  // contains a.B and a.C
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        val result = source.listClasses()
        assertTrue(result.isSuccess)
        val names = result.getOrNull()!!.map { it.name }.toSet()
        assertEquals(setOf("a.B", "a.C"), names)
        source.close()
    }

    @Test
    fun `getBytecode returns the raw class bytes`(@TempDir dir: Path) = runTest {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        val bytes = source.getBytecode("a.B").getOrNull()!!
        // First 4 bytes of any .class are the magic number.
        assertEquals(0xCAFEBABE.toInt(), java.nio.ByteBuffer.wrap(bytes).int)
        source.close()
    }

    @Test
    fun `getBytecode returns failure for unknown class`(@TempDir dir: Path) = runTest {
        val jar = makeTinyJar(dir)
        val source = JarClassSource(jar.toFile(), JarReader(), StaticHierarchyExtractor())
        val result = source.getBytecode("a.Missing")
        assertTrue(result.isFailure)
        source.close()
    }

    private fun makeTinyJar(dir: Path): Path {
        val jar = dir.resolve("tiny.jar")
        jar.outputStream().use { os ->
            JarOutputStream(os).use { out ->
                listOf("a/B", "a/C").forEach { internalName ->
                    val cw = ClassWriter(0)
                    cw.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null)
                    cw.visitEnd()
                    out.putNextEntry(JarEntry("$internalName.class"))
                    out.write(cw.toByteArray())
                    out.closeEntry()
                }
            }
        }
        return jar
    }
}
```

- [ ] **Step 2: Run the test — confirm it fails**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.JarClassSourceTest"
```

Expected: COMPILATION FAILED.

- [ ] **Step 3: Implement `JarClassSource`**

Create `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/JarClassSource.kt`:

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.JarReader
import com.bugdigger.protocol.ClassInfo
import java.io.File

/**
 * [ClassSource] backed by a `.jar` file on disk. Reads all class entries on
 * construction and caches them in memory; subsequent calls are pure lookups.
 *
 * Memory cost: roughly equal to the JAR's compressed size (a typical
 * application JAR is a few MB; an Android APK's translated classes can be
 * 30–60 MB). If we hit larger artifacts we can lazy-load instead, but
 * eager-load matches what the agent does (it caches every loaded class
 * anyway).
 */
class JarClassSource(
    private val jarFile: File,
    private val jarReader: JarReader,
    private val hierarchyExtractor: StaticHierarchyExtractor,
    classLoaderLabel: String = "JarFile(${jarFile.name})",
) : ClassSource {

    override val capabilities: Set<Capability> = Capability.STATIC_ONLY

    override val displayName: String = jarFile.name

    private val bytecodeMap: Map<String, ByteArray> = jarReader.read(jarFile)

    private val classInfos: List<ClassInfo> by lazy {
        bytecodeMap.entries.mapNotNull { (fqn, bytes) ->
            runCatching {
                val md = hierarchyExtractor.extract(bytes)
                StaticClassInfoMapper.toClassInfo(md, classLoaderLabel)
            }.getOrNull()
        }
    }

    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> {
        // includeSystemClasses is always-true for static sources: the JAR is what it is.
        return Result.success(classInfos)
    }

    override suspend fun getBytecode(className: String): Result<ByteArray> =
        bytecodeMap[className]?.let { Result.success(it) }
            ?: Result.failure(NoSuchElementException("Class not found: $className"))

    // No resources to close — the JarFile was opened, drained, and closed in JarReader.
}
```

- [ ] **Step 4: Run the test**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.JarClassSourceTest"
```

Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/JarClassSource.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/JarClassSourceTest.kt
git commit -m "feat(source): add JarClassSource for .jar static analysis"
```

---

## Task 6: `DexToJvmConverter` — APK / DEX → JVM bytecode (TDD)

Hide dex-tools behind an interface so we can swap libs.

**Files:**
- Create: `core/src/test/kotlin/com/bugdigger/core/source/DexToJvmConverterTest.kt`
- Create: `core/src/main/kotlin/com/bugdigger/core/source/DexToJvmConverter.kt`

- [ ] **Step 1: Place a tiny APK fixture**

Add a tiny APK fixture for tests at `core/src/test/resources/fixtures/tiny.apk`. Build it once with the Android SDK (`d8` + `aapt`) or grab one from any open-source Android sample app, then check it in. It should contain at most a couple of Java classes so the test runs in milliseconds. If you can't produce an APK, note that and **skip Tasks 6 and 7 for now** — Task 5 (JAR support) is enough to ship the static-source feature; APK is additive.

If you have the APK fixture, commit it on its own:

```bash
git add core/src/test/resources/fixtures/tiny.apk
git commit -m "test(core): add tiny APK fixture for DexToJvmConverter tests"
```

- [ ] **Step 2: Write the failing test**

```kotlin
package com.bugdigger.core.source

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.test.assertTrue

class DexToJvmConverterTest {

    @Test
    fun `converts apk into a jar containing class entries`(@TempDir tmp: Path) {
        val apk = copyResourceToTemp("/fixtures/tiny.apk", tmp.resolve("tiny.apk"))
        val outputJar = tmp.resolve("converted.jar")

        DexToJvmConverter().convert(apk.toFile(), outputJar.toFile())

        assertTrue(Files.exists(outputJar))
        JarFile(outputJar.toFile()).use { jar ->
            val classEntries = jar.entries().asSequence().filter { it.name.endsWith(".class") }.toList()
            assertTrue(classEntries.isNotEmpty(), "expected at least one .class entry in converted jar")
        }
    }

    private fun copyResourceToTemp(resource: String, dest: Path): Path {
        javaClass.getResourceAsStream(resource).use { input ->
            requireNotNull(input) { "fixture missing: $resource" }
            Files.copy(input, dest)
        }
        return dest
    }
}
```

- [ ] **Step 3: Run the test — confirm it fails**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.source.DexToJvmConverterTest"
```

Expected: COMPILATION FAILED.

- [ ] **Step 4: Implement `DexToJvmConverter`**

Create `core/src/main/kotlin/com/bugdigger/core/source/DexToJvmConverter.kt`:

```kotlin
package com.bugdigger.core.source

import com.googlecode.d2j.dex.Dex2jar
import com.googlecode.d2j.reader.MultiDexFileReader
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path

/**
 * Converts an APK or DEX file into a JAR of JVM `.class` entries using
 * d2j (dex-tools). Output is consumed by [JarReader] like any other JAR.
 *
 * If we ever need to swap the underlying lib (jadx-core, smali+translator,
 * etc.), this is the only file that changes — `JarClassSource` and
 * `ApkClassSource` consume the resulting JAR via the same pipeline.
 */
class DexToJvmConverter {

    private val logger = LoggerFactory.getLogger(DexToJvmConverter::class.java)

    /**
     * Converts the input APK/DEX into a JAR written to [outputJar]. Existing
     * file at [outputJar] is overwritten. Returns the [outputJar] for chaining.
     */
    fun convert(input: File, outputJar: File): File {
        require(input.exists()) { "Input not found: $input" }
        outputJar.parentFile?.mkdirs()
        outputJar.delete()

        logger.info("Converting {} -> {} via d2j", input, outputJar)

        // d2j entrypoint — exact symbol path may need tweaking for the
        // resolved version, see the artifact's javadoc. The pattern is:
        // 1. Read all DEX files in the APK (MultiDexFileReader handles multi-dex).
        // 2. Hand to Dex2jar, configure exception handling, then writeJar.
        val reader = MultiDexFileReader.open(input.toPath())
        Dex2jar.from(reader)
            .skipDebug(false)
            .topoLogicalSort()
            .noCode(false)
            .to(outputJar.toPath())

        require(outputJar.exists() && outputJar.length() > 0) {
            "d2j produced no output for $input"
        }
        return outputJar
    }

    fun convert(input: Path, outputJar: Path): Path = convert(input.toFile(), outputJar.toFile()).toPath()
}
```

(API names like `MultiDexFileReader.open`, `Dex2jar.from`, `.topoLogicalSort()` are how dex-tools 2.4 exposes these. If the resolved artifact uses different names — older releases use a `Dex2Jar` builder under `com.googlecode.d2j.tools`, newer ones move to `com.android.tools.r8.dex` — adjust during implementation. The ONLY file that should need to change is this one; tests verify the **outcome** not the symbol names.)

- [ ] **Step 5: Run the test**

```bash
.\gradlew.bat :core:test --tests "com.bugdigger.core.source.DexToJvmConverterTest"
```

Expected: 1 test PASS.

- [ ] **Step 6: Commit**

```bash
git add core/src/main/kotlin/com/bugdigger/core/source/DexToJvmConverter.kt core/src/test/kotlin/com/bugdigger/core/source/DexToJvmConverterTest.kt
git commit -m "feat(core): add DexToJvmConverter (APK -> JAR via d2j)"
```

---

## Task 7: `ApkClassSource` (TDD)

A thin wrapper: convert APK → temp JAR using `DexToJvmConverter`, then delegate everything to `JarClassSource`.

**Files:**
- Create: `composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/ApkClassSourceTest.kt` (only if APK fixture exists; otherwise skip)
- Create: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/ApkClassSource.kt`

- [ ] **Step 1: Write the failing test (skip if no APK fixture)**

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.DexToJvmConverter
import com.bugdigger.core.source.JarReader
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

class ApkClassSourceTest {

    @Test
    fun `lists classes extracted from APK`(@TempDir tmp: Path) = runTest {
        val apk = copyResource("/fixtures/tiny.apk", tmp.resolve("tiny.apk"))
        val source = ApkClassSource(
            apkFile = apk.toFile(),
            converter = DexToJvmConverter(),
            jarReader = JarReader(),
            hierarchyExtractor = StaticHierarchyExtractor(),
            workDir = tmp.toFile(),
        )
        try {
            val classes = source.listClasses().getOrNull()!!
            assertTrue(classes.isNotEmpty(), "expected at least one class extracted from tiny.apk")
        } finally {
            source.close()
        }
    }

    @Test
    fun `displayName uses apk filename`(@TempDir tmp: Path) {
        val apk = copyResource("/fixtures/tiny.apk", tmp.resolve("tiny.apk"))
        val source = ApkClassSource(
            apkFile = apk.toFile(),
            converter = DexToJvmConverter(),
            jarReader = JarReader(),
            hierarchyExtractor = StaticHierarchyExtractor(),
            workDir = tmp.toFile(),
        )
        try {
            assertTrue(source.displayName.contains("tiny.apk"))
        } finally {
            source.close()
        }
    }

    private fun copyResource(resource: String, dest: Path): Path {
        javaClass.getResourceAsStream(resource).use { input ->
            requireNotNull(input) { "fixture missing: $resource" }
            Files.copy(input, dest)
        }
        return dest
    }
}
```

(Copy the `tiny.apk` fixture from Task 6 into `composeApp/src/jvmTest/resources/fixtures/tiny.apk` so the test can find it on the classpath.)

- [ ] **Step 2: Implement `ApkClassSource`**

Create `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/ApkClassSource.kt`:

```kotlin
package com.bugdigger.bytesight.source

import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.DexToJvmConverter
import com.bugdigger.core.source.JarReader
import com.bugdigger.protocol.ClassInfo
import java.io.File

/**
 * [ClassSource] backed by an Android APK. On construction, converts the APK's
 * DEX classes to JVM bytecode (writing a temp JAR alongside the APK) and then
 * delegates to a [JarClassSource] for everything else.
 */
class ApkClassSource(
    private val apkFile: File,
    converter: DexToJvmConverter,
    jarReader: JarReader,
    hierarchyExtractor: StaticHierarchyExtractor,
    workDir: File = apkFile.parentFile,
) : ClassSource {

    private val convertedJar: File = workDir.resolve("${apkFile.nameWithoutExtension}.converted.jar")
    private val delegate: JarClassSource

    init {
        converter.convert(apkFile, convertedJar)
        delegate = JarClassSource(convertedJar, jarReader, hierarchyExtractor,
            classLoaderLabel = "APK(${apkFile.name})")
    }

    override val capabilities: Set<Capability> = Capability.STATIC_ONLY

    override val displayName: String = "${apkFile.name} (APK)"

    override suspend fun listClasses(includeSystemClasses: Boolean): Result<List<ClassInfo>> =
        delegate.listClasses(includeSystemClasses)

    override suspend fun getBytecode(className: String): Result<ByteArray> =
        delegate.getBytecode(className)

    override fun close() {
        delegate.close()
        // Best-effort cleanup of the temp converted JAR.
        runCatching { convertedJar.delete() }
    }
}
```

- [ ] **Step 3: Run the test (if fixture present)**

```bash
.\gradlew.bat :composeApp:jvmTest --tests "com.bugdigger.bytesight.source.ApkClassSourceTest"
```

Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/source/ApkClassSource.kt composeApp/src/jvmTest/kotlin/com/bugdigger/bytesight/source/ApkClassSourceTest.kt composeApp/src/jvmTest/resources/fixtures/tiny.apk
git commit -m "feat(source): add ApkClassSource (DEX -> JVM via converter)"
```

---

## Task 8: Wire singletons into Koin

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt`

- [ ] **Step 1: Register the new singletons**

In `appModule`, near the existing `single { DecompilerOptions() }` block, add:

```kotlin
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.DexToJvmConverter
import com.bugdigger.core.source.JarReader

// Static-source primitives (used by JarClassSource / ApkClassSource)
single { JarReader() }
single { StaticHierarchyExtractor() }
single { DexToJvmConverter() }
```

- [ ] **Step 2: Verify the app compiles + tests pass**

```bash
.\gradlew.bat :composeApp:jvmTest
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/di/AppModule.kt
git commit -m "di: register JarReader, StaticHierarchyExtractor, DexToJvmConverter"
```

---

## Task 9: `AttachViewModel` gets `openJar(path)` and `openApk(path)`

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachViewModel.kt`

- [ ] **Step 1: Inject the new dependencies and add the methods**

```kotlin
class AttachViewModel(
    private val attachService: AttachService,
    private val agentClient: AgentClient,
    private val connectionRegistry: ConnectionRegistry,
    private val jarReader: JarReader,
    private val hierarchyExtractor: StaticHierarchyExtractor,
    private val dexConverter: DexToJvmConverter,
) : ViewModel() {

    // ... unchanged ...

    /** Opens a JAR file and installs it as the active static source. */
    fun openJar(path: String) {
        val file = File(path)
        viewModelScope.launch {
            _uiState.update { it.copy(isAttaching = true, error = null) }
            runCatching {
                JarClassSource(file, jarReader, hierarchyExtractor)
            }.onSuccess { source ->
                connectionRegistry.setSource(source, connectionKey = null)
                _uiState.update {
                    it.copy(
                        isAttaching = false,
                        // Re-use connectionKey field as the "source key" so
                        // App.kt's onConnected route fires. Use a synthetic
                        // identifier the live agent path won't collide with.
                        connectionKey = "jar://${file.absolutePath}",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isAttaching = false, error = "Failed to open JAR: ${e.message}")
                }
            }
        }
    }

    /** Opens an APK and installs an [ApkClassSource]. */
    fun openApk(path: String) {
        val file = File(path)
        viewModelScope.launch {
            _uiState.update { it.copy(isAttaching = true, error = null) }
            runCatching {
                ApkClassSource(file, dexConverter, jarReader, hierarchyExtractor)
            }.onSuccess { source ->
                connectionRegistry.setSource(source, connectionKey = null)
                _uiState.update {
                    it.copy(
                        isAttaching = false,
                        connectionKey = "apk://${file.absolutePath}",
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isAttaching = false, error = "Failed to open APK: ${e.message}")
                }
            }
        }
    }
}
```

(Add necessary imports: `com.bugdigger.bytesight.source.*`, `com.bugdigger.core.analysis.StaticHierarchyExtractor`, `com.bugdigger.core.source.*`, `java.io.File`.)

- [ ] **Step 2: Verify it compiles**

```bash
.\gradlew.bat :composeApp:compileKotlinJvm
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachViewModel.kt
git commit -m "feat(attach): openJar() and openApk() install static sources"
```

---

## Task 10: `AttachScreen` UI — file picker buttons

**Files:**
- Modify: `composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachScreen.kt`

- [ ] **Step 1: Add a file-picker section above the process list**

At the top of the `AttachScreen` `Column` body, just below `AttachHeader` (and before `Row` containing the process list), add a "Static analysis" section with two buttons:

```kotlin
import androidx.compose.material3.OutlinedButton
import java.awt.FileDialog
import java.awt.Frame

@Composable
private fun StaticOpenSection(
    onJarPicked: (String) -> Unit,
    onApkPicked: (String) -> Unit,
    enabled: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Static analysis (no agent)",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Open a JAR or APK to browse classes, hierarchy, strings, and decompiled source. Trace, Heap, and Debugger tabs are unavailable in this mode.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = enabled, onClick = { pickFile("Open JAR", "*.jar")?.let(onJarPicked) }) {
                    Text("Open JAR…")
                }
                OutlinedButton(enabled = enabled, onClick = { pickFile("Open APK", "*.apk")?.let(onApkPicked) }) {
                    Text("Open APK…")
                }
            }
        }
    }
}

private fun pickFile(title: String, mask: String): String? {
    val dlg = FileDialog(null as Frame?, title, FileDialog.LOAD)
    dlg.file = mask
    dlg.isVisible = true
    val name = dlg.file ?: return null
    val dir = dlg.directory ?: return null
    return java.io.File(dir, name).absolutePath
}
```

Then call it from `AttachScreen`:

```kotlin
@Composable
fun AttachScreen(...) {
    val uiState by viewModel.uiState.collectAsState()
    uiState.connectionKey?.let { key -> onConnected(key) }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        AttachHeader(...)
        Spacer(Modifier.height(16.dp))

        // NEW: static-mode entry points
        StaticOpenSection(
            onJarPicked = viewModel::openJar,
            onApkPicked = viewModel::openApk,
            enabled = !uiState.isAttaching && uiState.connectionKey == null,
        )
        Spacer(Modifier.height(16.dp))

        uiState.error?.let { ... existing ErrorCard ... }

        Row(...) { ProcessList(...); AttachmentPanel(...) }
    }
}
```

- [ ] **Step 2: Compile and run the desktop app**

```bash
.\gradlew.bat :composeApp:run
```

Verify the buttons appear above the process list. Click "Open JAR…", pick `sample/build/libs/sample-*.jar` (or any JAR), confirm:
- The Sidebar lights up Classes / Hierarchy / Inspector / Strings / AI.
- Trace / Heap / Debugger remain disabled.
- The Classes tab populates with classes from the JAR.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/jvmMain/kotlin/com/bugdigger/bytesight/ui/attach/AttachScreen.kt
git commit -m "feat(attach): add Open JAR / Open APK file pickers"
```

---

## Task 11: End-to-end smoke + integration

**Files:** none.

- [ ] **Step 1: Build everything**

```bash
.\gradlew.bat :sample:jar :agent:agentJar build
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run the desktop app and walk through both modes**

```bash
.\gradlew.bat :composeApp:run
```

**Static-JAR mode:**
- Open JAR → pick `sample/build/libs/sample-*.jar`
- Classes tab: populated, click any class, bytecode + decompiled appears
- Hierarchy tab: tree renders
- Inspector tab: pick a method, disassembly + CFG render
- Strings tab: Extract All → constants populate
- AI tab: works (it's source-agnostic — operates on selected class context)
- Trace / Heap / Debugger sidebar items: disabled

**Live-JVM mode (regression check):**
- Click Disconnect (or restart)
- Start sample as a live process: `java -jar sample/build/libs/sample-*.jar`
- Attach to it via the existing flow
- All tabs enabled, including Trace / Heap / Debugger
- Verify everything works exactly as before Step 1

**Static-APK mode (if d2j integrated):**
- Open APK → pick any small APK (Android sample app's release build)
- Same checks as Static-JAR

- [ ] **Step 3: Verify no test regressions**

```bash
.\gradlew.bat :composeApp:jvmTest :core:test
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Final commit + tag**

```bash
git tag step-2-static-jar-apk-complete
```

---

## Verification (end-to-end)

| Invariant | Check |
|---|---|
| JAR static mode produces a working Classes/Hierarchy/Inspector/Strings | Manual smoke (Task 11) |
| Trace/Heap/Debugger auto-disable in static mode | Manual smoke (Sidebar item state) |
| Live-JVM mode unchanged | Manual smoke + `:composeApp:jvmTest` |
| APK conversion produces a non-empty class set | `DexToJvmConverterTest` + manual APK pick |
| No reflection in `core` static-analysis path | grep: `Get-ChildItem core/src/main/kotlin -Recurse | Select-String "Class\.forName\|getDeclaredMethods"` should return zero matches |

## What's intentionally not done in this plan

- No save/load (Step 3)
- No diff (Step 4)
- No multi-DEX conversion edge cases beyond what dex-tools handles by default
- No support for fat APKs with native code or resources — we only care about classes
- No ProGuard mapping import (that's the `deobfuscator` module from `devdocs/plan.md` Phase 2; orthogonal to this plan)
- No streaming of the JAR contents — full eager-load is the simplest correct first cut
