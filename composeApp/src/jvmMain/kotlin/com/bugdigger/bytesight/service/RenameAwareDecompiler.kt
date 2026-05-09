package com.bugdigger.bytesight.service

import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.core.decompiler.Decompiler
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.commons.ClassRemapper

/**
 * Decompiler wrapper that applies the user's symbol renames at the
 * **bytecode layer** before delegating to Vineflower. Each class's bytes
 * are run through ASM's [ClassRemapper] with a [RenameRemapper] callback;
 * Vineflower then sees pre-renamed bytecode and emits source that already
 * has the new names — no fragile text substitution involved.
 *
 * This is the right layer for the rename feature because:
 * 1. ASM gives us full disambiguating context per reference (owner + name +
 *    descriptor for fields & methods, internal name for types), so two
 *    fields named `a` of different types stay distinct.
 * 2. Renames cascade naturally — renaming class `o.l` to `Logger` updates
 *    every type reference in every other class without us having to find
 *    and rewrite them manually in source text.
 *
 * Plug into Koin: `single<Decompiler> { RenameAwareDecompiler(VineflowerDecompiler(get()), get()) }`.
 */
class RenameAwareDecompiler(
    private val delegate: Decompiler,
    private val renameStore: RenameStore,
) : Decompiler {

    override suspend fun decompile(className: String, bytecode: ByteArray): DecompilationResult {
        val renames = renameStore.renameMap.value
        if (renames.isEmpty()) return delegate.decompile(className, bytecode)

        val remapper = RenameRemapper(renames)
        val remappedBytes = applyRemap(bytecode, remapper)
        // mapType returns a possibly-renamed internal name ("o/Product"). We
        // pass that through to the delegate as the className; Vineflower
        // reads the bytes and produces source for whatever class it finds.
        val remappedClassName = remapper.map(className.replace('.', '/')).replace('/', '.')

        return delegate.decompile(remappedClassName, remappedBytes)
    }

    override suspend fun decompileAll(
        classes: Map<String, ByteArray>,
    ): Map<String, DecompilationResult> {
        val renames = renameStore.renameMap.value
        if (renames.isEmpty()) return delegate.decompileAll(classes)

        val remapper = RenameRemapper(renames)
        // Build a parallel map of remapped bytes keyed by the (possibly
        // renamed) class name. Track original→renamed so callers that
        // looked up by original FQN still get a result.
        val originalToRenamed = mutableMapOf<String, String>()
        val remappedClasses = HashMap<String, ByteArray>(classes.size)
        for ((origName, bytes) in classes) {
            val remappedBytes = applyRemap(bytes, remapper)
            val renamedName = remapper.map(origName.replace('.', '/')).replace('/', '.')
            remappedClasses[renamedName] = remappedBytes
            originalToRenamed[origName] = renamedName
        }

        val results = delegate.decompileAll(remappedClasses)

        // Re-key by original FQN so callers don't have to know about renaming.
        return originalToRenamed.mapValues { (_, renamedName) ->
            results[renamedName] ?: DecompilationResult.Failure(
                "No decompilation output produced after rename for: $renamedName"
            )
        }
    }

    private fun applyRemap(bytecode: ByteArray, remapper: RenameRemapper): ByteArray {
        val reader = ClassReader(bytecode)
        // ClassWriter(0): preserve frames as-is. ClassRemapper passes existing
        // frame entries through Remapper.mapType, so frame validity is
        // maintained without needing a class hierarchy lookup (which we
        // wouldn't have anyway — we only see one class at a time).
        val writer = ClassWriter(0)
        reader.accept(ClassRemapper(writer, remapper), 0)
        return writer.toByteArray()
    }
}
