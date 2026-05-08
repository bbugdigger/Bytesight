package com.bugdigger.core.diff

/**
 * Multi-feature heuristic differ for two projects represented as
 * `className → bytecode` maps.
 *
 * Algorithm (v1, simple):
 * 1. Pre-bucket fingerprints by parameter arity to keep pairing space tractable.
 * 2. For each old/new pair within the same arity bucket, compute four cheap
 *    features and a weighted confidence score.
 * 3. Greedy match: take the highest-confidence pair, lock both methods, repeat.
 *    Anything below [DiffWeights.confidenceThreshold] is treated as unmatched.
 *
 * Future v2 with CFG-aware structural matching is left as an extension point;
 * the public API is stable on top of [MethodFingerprint] + [DiffResult].
 */
class ProjectDiffer(private val weights: DiffWeights = DiffWeights()) {

    /** Diff two projects represented as `className → bytecode` maps. */
    fun diff(old: Map<String, ByteArray>, new: Map<String, ByteArray>): DiffResult {
        val oldFps = fingerprintAll(old)
        val newFps = fingerprintAll(new)
        return diffFingerprints(oldFps, newFps)
    }

    /**
     * Diff with already-extracted fingerprints. Useful for tests and for
     * caching across multiple comparisons of the same project.
     */
    fun diffFingerprints(
        oldFps: List<MethodFingerprint>,
        newFps: List<MethodFingerprint>,
    ): DiffResult {
        val oldByArity = oldFps.groupBy { it.parameterArity }
        val newByArity = newFps.groupBy { it.parameterArity }

        val candidates = mutableListOf<Triple<MethodFingerprint, MethodFingerprint, Pair<Double, ConfidenceFeatures>>>()
        for ((arity, news) in newByArity) {
            val olds = oldByArity[arity] ?: continue
            for (n in news) {
                for (o in olds) {
                    val (score, feats) = scorePair(o, n)
                    if (score >= weights.confidenceThreshold) {
                        candidates.add(Triple(o, n, score to feats))
                    }
                }
            }
        }

        // Greedy match — highest score first; lock both sides on each pick.
        candidates.sortByDescending { it.third.first }
        val matched = mutableListOf<MatchedPair>()
        val takenOld = mutableSetOf<String>()
        val takenNew = mutableSetOf<String>()
        for ((o, n, sf) in candidates) {
            if (o.key in takenOld || n.key in takenNew) continue
            matched.add(MatchedPair(o, n, sf.first, sf.second))
            takenOld.add(o.key)
            takenNew.add(n.key)
        }

        val removed = oldFps.filter { it.key !in takenOld }
        val added = newFps.filter { it.key !in takenNew }

        return DiffResult(
            matched = matched.sortedByDescending { it.confidence },
            addedInNew = added,
            removedFromOld = removed,
            confidenceThreshold = weights.confidenceThreshold,
        )
    }

    private fun fingerprintAll(project: Map<String, ByteArray>): List<MethodFingerprint> =
        project.flatMap { (className, bytes) -> MethodFingerprint.extractAll(className, bytes) }

    private fun scorePair(old: MethodFingerprint, new: MethodFingerprint): Pair<Double, ConfidenceFeatures> {
        val sOp = Similarity.cosine(old.opcodeHistogram, new.opcodeHistogram)
        val sCall = Similarity.jaccard(old.calleeFqns, new.calleeFqns)
        val sSig = signatureScore(old, new)
        val sStr = Similarity.jaccard(old.stringConstants, new.stringConstants)
        val confidence =
            weights.opcodes * sOp +
                weights.callees * sCall +
                weights.signature * sSig +
                weights.strings * sStr
        return confidence to ConfidenceFeatures(sOp, sCall, sSig, sStr)
    }

    private fun signatureScore(old: MethodFingerprint, new: MethodFingerprint): Double {
        val returnMatch = if (old.returnType == new.returnType) 1.0 else 0.0
        // Arity already gates pairing, so equal lists is the strict case;
        // partial gives us some signal for type-renamed parameters.
        val paramMatch = if (old.parameterTypes == new.parameterTypes) 1.0 else 0.5
        return 0.5 * returnMatch + 0.5 * paramMatch
    }
}
