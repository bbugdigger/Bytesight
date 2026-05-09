package com.bugdigger.bytesight.service

import org.objectweb.asm.commons.Remapper

/**
 * ASM [Remapper] that consults a [RenameStore]-style FQN-keyed rename map
 * to rewrite bytecode references. Used by [RenameAwareDecompiler] to apply
 * renames at the bytecode layer — disambiguates same-name symbols
 * automatically because each ASM callback gives us the full identifying
 * context (owner + name + descriptor for fields & methods, internal name
 * for types).
 *
 * Key format expected in [renames]:
 * - Class:  `"com.example.Foo"`
 * - Method: `"com.example.Foo#bar(Ljava/lang/String;)V"` (descriptor inline)
 * - Field:  `"com.example.Foo#field:Ljava/util/Map;"` (descriptor after `:`)
 *
 * For backward compatibility we also accept the old descriptor-less field
 * key format (`"com.example.Foo#field"`) — it still works when the field
 * name is unambiguous within its class. The test for this shape is
 * documented in [RenameStore.fieldKey].
 */
class RenameRemapper(private val renames: Map<String, String>) : Remapper() {

    /**
     * Renames a type internal name (e.g. `"o/j"`). Returns the same internal
     * name with the simple part replaced when the user has renamed that
     * class; otherwise returns the input unchanged.
     */
    override fun map(internalName: String): String {
        val fqn = internalName.replace('/', '.')
        val newSimple = renames[fqn] ?: return internalName
        val pkg = internalName.substringBeforeLast('/', missingDelimiterValue = "")
        return if (pkg.isEmpty()) newSimple else "$pkg/$newSimple"
    }

    override fun mapFieldName(owner: String, name: String, descriptor: String): String {
        val ownerFqn = owner.replace('/', '.')
        // Precise key first (descriptor included) — this is what disambiguates
        // two fields with the same name and different types.
        renames[RenameStore.fieldKey(ownerFqn, name, descriptor)]?.let { return it }
        // Fallback: old descriptor-less key for renames created before
        // the format was extended. Only matches when the old key exists.
        renames["$ownerFqn#$name"]?.let { return it }
        return name
    }

    override fun mapMethodName(owner: String, name: String, descriptor: String): String {
        val ownerFqn = owner.replace('/', '.')
        renames["$ownerFqn#$name$descriptor"]?.let { return it }
        return name
    }

    /** Synthetic / record-style invokedynamic names — same shape as method names. */
    override fun mapInvokeDynamicMethodName(name: String, descriptor: String): String {
        // We don't currently key invokedynamic renames; pass through.
        return name
    }
}
