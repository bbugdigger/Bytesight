package com.bugdigger.bytesight.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-scoped in-memory store for user-assigned symbol renames. Singleton so that
 * renames survive ViewModel recreation when the user navigates between screens.
 *
 * Keys use fully-qualified identifiers:
 * - Classes: `"com.example.ClassName"`
 * - Methods: `"com.example.ClassName#methodName(Ljava/lang/String;)V"`
 * - Fields: `"com.example.ClassName#fieldName"`
 *
 * The [applyToSource] method performs simple text replacement of short names (the part
 * after the last `.` or `#`) in decompiled source code.
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
    }
}
