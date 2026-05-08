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
            val entries = jar.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
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
