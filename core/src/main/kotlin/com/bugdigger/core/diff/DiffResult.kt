package com.bugdigger.core.diff

/** Output of [ProjectDiffer.diff]. */
data class DiffResult(
    /** Pairs of (oldFingerprint, newFingerprint, confidence). Sorted by confidence desc. */
    val matched: List<MatchedPair>,
    /** New methods that didn't pair to anything in old. */
    val addedInNew: List<MethodFingerprint>,
    /** Old methods that didn't pair to anything in new. */
    val removedFromOld: List<MethodFingerprint>,
    /** Configured threshold below which pairs are dropped to unmatched. */
    val confidenceThreshold: Double,
)

data class MatchedPair(
    val old: MethodFingerprint,
    val new: MethodFingerprint,
    val confidence: Double,
    /** Per-feature breakdown so the UI can explain "why" with a tooltip. */
    val features: ConfidenceFeatures,
)

data class ConfidenceFeatures(
    val opcodeHistogramCosine: Double,
    val calleeJaccard: Double,
    val signatureScore: Double,
    val stringJaccard: Double,
)

/** Configurable weights. Defaults sum to 1.0 and can be overridden in Settings later. */
data class DiffWeights(
    val opcodes: Double = 0.40,
    val callees: Double = 0.30,
    val signature: Double = 0.15,
    val strings: Double = 0.15,
    val confidenceThreshold: Double = 0.40,
)
