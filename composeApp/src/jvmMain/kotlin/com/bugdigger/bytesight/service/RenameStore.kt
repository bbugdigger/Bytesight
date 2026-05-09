package com.bugdigger.bytesight.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Session-scoped in-memory store for user-assigned symbol renames. Singleton so that
 * renames survive ViewModel recreation when the user navigates between screens.
 *
 * Keys use fully-qualified identifiers, all containing enough type context
 * to disambiguate same-named symbols within a class:
 * - Classes: `"com.example.ClassName"`
 * - Methods: `"com.example.ClassName#methodName(Ljava/lang/String;)V"` — descriptor inline.
 * - Fields:  `"com.example.ClassName#fieldName:Ljava/util/Map;"` — descriptor after `:`.
 *   This shape is required because the JVM allows multiple fields with the
 *   same name and different types in the same class; obfuscators exploit it.
 *
 * For backward compatibility, renames written with the old descriptor-less
 * field key (`"com.example.ClassName#fieldName"`) still apply via fallback
 * lookup in [RenameRemapper], but new code should use [fieldKey] to
 * construct precise keys.
 *
 * Renames are applied at the **bytecode layer** by [RenameAwareDecompiler]
 * before Vineflower runs, so the decompiled source comes out correctly
 * disambiguated even when multiple symbols share a simple name.
 */
class RenameStore {

    private val _renames = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Current rename map: original FQN → user-assigned name. */
    val renameMap: StateFlow<Map<String, String>> = _renames.asStateFlow()

    /** Register or update a rename for the given original fully-qualified name. */
    fun rename(originalFqn: String, newName: String) {
        require(newName.isNotBlank()) { "New name must not be blank" }
        _renames.update { it + (originalFqn to newName) }
    }

    /** Remove a previously registered rename. */
    fun removeRename(originalFqn: String) {
        _renames.update { it - originalFqn }
    }

    /** Clear all renames. */
    fun clearAll() {
        _renames.update { emptyMap() }
    }

    /**
     * Apply all renames to decompiled source text. Replaces the short name (simple name
     * portion of the FQN) with the user-assigned name using word-boundary matching.
     *
     * Renames are applied longest-short-name-first to avoid partial replacements.
     */
    fun applyToSource(source: String): String {
        val renames = _renames.value
        if (renames.isEmpty()) return source

        // Build replacement pairs: extract the short name from each FQN
        val replacements = renames.map { (fqn, newName) ->
            shortName(fqn) to newName
        }
            .filter { (old, new) -> old != new }
            .sortedByDescending { it.first.length } // longest first to avoid partial matches

        var result = source
        for ((oldName, newName) in replacements) {
            // Use word-boundary matching to avoid replacing substrings
            val pattern = Regex("""\b${Regex.escape(oldName)}\b""")
            result = pattern.replace(result, newName)
        }
        return result
    }

    /**
     * Returns a map of short-name → new-name for display purposes (e.g., highlighting
     * renamed symbols in the code viewer).
     */
    fun shortNameMap(): Map<String, String> {
        return _renames.value.map { (fqn, newName) -> shortName(fqn) to newName }
            .toMap()
    }

    /** Serialize the current rename map as JSON. */
    fun serialize(json: Json = DEFAULT_JSON): String =
        json.encodeToString(MAP_SERIALIZER, _renames.value)

    /** Replace the rename map with the contents of the given JSON. */
    fun restore(text: String, json: Json = DEFAULT_JSON) {
        val map: Map<String, String> = json.decodeFromString(MAP_SERIALIZER, text)
        _renames.value = map
    }

    companion object {
        /**
         * Extract the short (simple) name from a fully-qualified identifier.
         * - `"com.example.Foo"` → `"Foo"`
         * - `"com.example.Foo#bar(I)V"` → `"bar"`
         * - `"com.example.Foo#myField"` → `"myField"`
         */
        fun shortName(fqn: String): String {
            val afterHash = if ('#' in fqn) fqn.substringAfter('#') else fqn
            // Strip method descriptor if present
            val name = if ('(' in afterHash) afterHash.substringBefore('(') else afterHash
            // Take last dot-separated segment
            return name.substringAfterLast('.')
        }

        /**
         * Display short name for a class/method/field key. If the user has a
         * rename for [fqn] in [renames], returns it; otherwise returns the
         * natural [shortName]. FQN-keyed lookup, so two classes with the
         * same simple name (`a.X` vs `b.X`) don't collide.
         */
        fun displayShortName(fqn: String, renames: Map<String, String>): String =
            renames[fqn] ?: shortName(fqn)

        /**
         * Display fully-qualified class name. Package portion is preserved;
         * the simple name is replaced with the user's rename if present.
         * Pass class FQNs only — behavior with `#methodName` keys is
         * undefined.
         */
        fun displayClassFqn(classFqn: String, renames: Map<String, String>): String {
            val pkg = classFqn.substringBeforeLast('.', missingDelimiterValue = "")
            val simple = displayShortName(classFqn, renames)
            return if (pkg.isEmpty()) simple else "$pkg.$simple"
        }

        /**
         * Builds the precise rename key for a field. Includes the field's
         * JVM descriptor so two fields with the same name and different
         * types in the same class don't collide.
         */
        fun fieldKey(classFqn: String, fieldName: String, descriptor: String): String =
            "$classFqn#$fieldName:$descriptor"

        /**
         * Builds the precise rename key for a method. Mirrors how Vineflower /
         * the JVM identify methods; the descriptor disambiguates overloads.
         */
        fun methodKey(classFqn: String, methodName: String, descriptor: String): String =
            "$classFqn#$methodName$descriptor"

        private val DEFAULT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
        private val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
