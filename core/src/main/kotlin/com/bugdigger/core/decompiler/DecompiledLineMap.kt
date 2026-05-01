package com.bugdigger.core.decompiler

/**
 * Maps decompiled-source line numbers to original bytecode line numbers (the
 * ones in the class's `LineNumberTable`). Built from Vineflower's `IResultSaver`
 * `mapping: IntArray?` parameter.
 *
 * Vineflower's convention is a flat array of pairs:
 * `[decompiledLine_1, originalLine_1, decompiledLine_2, originalLine_2, ...]`.
 * Both numbers are 1-based and refer to lines in the produced source / the
 * original `.java` source the class was compiled from. Not every decompiled
 * line has a mapping (e.g. `}` lines, whitespace, synthetic constructs).
 *
 * Use [originalLineFor] to look up a decompiled line; returns null if the line
 * isn't covered. Use [findNearbyOriginalLine] to fall back to the nearest
 * mapped decompiled line above the click — handy for setting a breakpoint
 * when the user clicked a closing brace or a comment line.
 */
class DecompiledLineMap(private val pairs: IntArray) {

    /** True if Vineflower produced no mappings (empty pairs). */
    val isEmpty: Boolean get() = pairs.isEmpty()

    /** Number of (decompiled, original) pairs. */
    val size: Int get() = pairs.size / 2

    /** Direct lookup. Returns null if [decompiledLine] has no mapping. */
    fun originalLineFor(decompiledLine: Int): Int? {
        var i = 0
        while (i < pairs.size) {
            if (pairs[i] == decompiledLine) return pairs[i + 1]
            i += 2
        }
        return null
    }

    /**
     * Returns the original line for the largest decompiled line <= [decompiledLine].
     * Useful when the user clicked a line without a direct mapping (`}`, blank line,
     * comment) — we want to "round down" to the previous statement that was mapped.
     * Returns null when no decompiled line at or below the query has a mapping.
     */
    fun findNearbyOriginalLine(decompiledLine: Int): Int? {
        var bestDecompiled = -1
        var bestOriginal: Int? = null
        var i = 0
        while (i < pairs.size) {
            val d = pairs[i]
            if (d in (bestDecompiled + 1)..decompiledLine) {
                bestDecompiled = d
                bestOriginal = pairs[i + 1]
            }
            i += 2
        }
        return bestOriginal
    }

    /**
     * Inverse lookup: returns every decompiled line whose mapped original line
     * equals [originalLine]. Empty list if no decompiled line maps there.
     * Used by the Inspector decompiled-tab gutter to show bp dots on the
     * decompiled lines that correspond to currently-active bytecode bps.
     */
    fun decompiledLinesFor(originalLine: Int): List<Int> {
        val out = mutableListOf<Int>()
        var i = 0
        while (i < pairs.size) {
            if (pairs[i + 1] == originalLine) out.add(pairs[i])
            i += 2
        }
        return out
    }

    /** All distinct original lines that appear in the map, sorted ascending. */
    fun allOriginalLines(): List<Int> {
        val out = sortedSetOf<Int>()
        var i = 1
        while (i < pairs.size) {
            out.add(pairs[i])
            i += 2
        }
        return out.toList()
    }

    /** Internal — exposed for testing the raw pair layout. */
    internal fun rawPairs(): IntArray = pairs.copyOf()
}
