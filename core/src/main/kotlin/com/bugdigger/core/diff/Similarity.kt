package com.bugdigger.core.diff

import kotlin.math.sqrt

/** Numeric similarity helpers used by [ProjectDiffer]. */
object Similarity {

    /**
     * Cosine similarity between two equal-length integer vectors. Returns 0
     * if either vector is zero-magnitude.
     */
    fun cosine(a: IntArray, b: IntArray): Double {
        require(a.size == b.size) { "vector lengths differ: ${a.size} vs ${b.size}" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val ai = a[i].toDouble()
            val bi = b[i].toDouble()
            dot += ai * bi
            normA += ai * ai
            normB += bi * bi
        }
        if (normA == 0.0 || normB == 0.0) return 0.0
        return dot / (sqrt(normA) * sqrt(normB))
    }

    /**
     * Jaccard similarity between two sets. Two empty sets return 1.0
     * (defined as identical "absence").
     */
    fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersection = a.intersect(b).size.toDouble()
        val union = (a.size + b.size).toDouble() - intersection
        if (union == 0.0) return 0.0
        return intersection / union
    }
}
