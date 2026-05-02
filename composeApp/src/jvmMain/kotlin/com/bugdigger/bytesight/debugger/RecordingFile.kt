package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.DebuggerEvent
import java.nio.file.Files
import java.nio.file.Path

/**
 * `.btsrec` file format = length-prefixed stream of [DebuggerEvent] messages
 * via protobuf's [com.google.protobuf.MessageLite.writeDelimitedTo] /
 * [DebuggerEvent.parseDelimitedFrom]. No header, no version, no magic.
 *
 * The proto's `oneof kind` and reserved field numbers (see
 * `bytesight.proto`) provide forward compatibility — additional event types
 * loaded by an older client surface as `KindCase.KIND_NOT_SET`, which the UI
 * already tolerates.
 */
object RecordingFile {

    fun saveTo(path: Path, events: List<DebuggerEvent>) {
        Files.newOutputStream(path).use { out ->
            for (e in events) e.writeDelimitedTo(out)
        }
    }

    fun loadFrom(path: Path): List<DebuggerEvent> {
        val result = mutableListOf<DebuggerEvent>()
        Files.newInputStream(path).use { input ->
            while (true) {
                val e = DebuggerEvent.parseDelimitedFrom(input) ?: break
                result.add(e)
            }
        }
        return result
    }
}
