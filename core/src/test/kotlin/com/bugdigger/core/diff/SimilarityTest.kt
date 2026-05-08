package com.bugdigger.core.diff

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SimilarityTest {

    @Test
    fun `cosine returns 1 for identical vectors`() {
        val v = intArrayOf(1, 2, 3, 0, 5)
        assertEquals(1.0, Similarity.cosine(v, v), 1e-9)
    }

    @Test
    fun `cosine returns 0 for orthogonal vectors`() {
        assertEquals(0.0, Similarity.cosine(intArrayOf(1, 0, 0), intArrayOf(0, 1, 0)), 1e-9)
    }

    @Test
    fun `cosine returns 0 for either zero vector`() {
        assertEquals(0.0, Similarity.cosine(intArrayOf(0, 0, 0), intArrayOf(1, 1, 1)), 1e-9)
        assertEquals(0.0, Similarity.cosine(intArrayOf(1, 1, 1), intArrayOf(0, 0, 0)), 1e-9)
    }

    @Test
    fun `jaccard returns 1 for identical sets`() {
        val s = setOf("a", "b", "c")
        assertEquals(1.0, Similarity.jaccard(s, s), 1e-9)
    }

    @Test
    fun `jaccard returns 0 for disjoint sets`() {
        assertEquals(0.0, Similarity.jaccard(setOf("a"), setOf("b")), 1e-9)
    }

    @Test
    fun `jaccard returns 1 for two empty sets`() {
        assertEquals(1.0, Similarity.jaccard(emptySet<String>(), emptySet()), 1e-9)
    }

    @Test
    fun `jaccard correctly computes intersection over union`() {
        // |intersection| = 2 (y, z); |union| = 4 (w, x, y, z) -> 0.5
        val a = setOf("x", "y", "z")
        val b = setOf("y", "z", "w")
        assertEquals(0.5, Similarity.jaccard(a, b), 1e-9)
    }
}
