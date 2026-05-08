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
 * Layout:
 * ```
 *   manifest.json                    Required. ProjectManifest as JSON.
 *   classes/<a>/<b>/<C>.class        Required (>=1). Raw bytecode, slash-encoded
 *                                    so the resulting zip is also a valid JAR
 *                                    (a JarReader can read it directly when
 *                                    BytecodeDiff loads two .bts files).
 *   <name>.json                      Optional sidecar JSON entries
 *                                    (renames.json, comments.json, ...).
 * ```
 *
 * - [open] mounts an existing file for reading. Caller must [close].
 * - [write] produces a new file atomically (writes to `.tmp` next door, then
 *   moves over the destination).
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
            destination.parentFile?.mkdirs()
            val tmp = File(destination.parentFile ?: File("."), destination.name + ".tmp")
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
            // Atomic move over destination
            Files.move(
                tmp.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }
}
