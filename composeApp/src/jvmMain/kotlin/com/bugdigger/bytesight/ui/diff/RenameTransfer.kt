package com.bugdigger.bytesight.ui.diff

import com.bugdigger.core.diff.MatchedPair

/**
 * Pure rename-transfer action. Given the old project's renames map and a
 * matched method pair, produces an updated copy of the new project's
 * renames map with the old display name applied to the new method's key
 * (and to the matched class, if a class-level rename exists in old).
 *
 * Operates on plain `Map<String, String>` values rather than [RenameStore]
 * directly so callers can use it across either the global store (when the
 * "new" side is the active project) or a session-local map (Diff tab's
 * private "right side renames" — added in a later commit).
 */
object RenameTransfer {

    fun applyMethodRename(
        oldRenames: Map<String, String>,
        newRenames: Map<String, String>,
        pair: MatchedPair,
    ): Map<String, String> {
        var out = newRenames

        // Class rename
        oldRenames[pair.old.className]?.let { display ->
            out = out + (pair.new.className to display)
        }

        // Method rename
        val oldKey = "${pair.old.className}#${pair.old.methodName}${pair.old.descriptor}"
        oldRenames[oldKey]?.let { display ->
            val newKey = "${pair.new.className}#${pair.new.methodName}${pair.new.descriptor}"
            out = out + (newKey to display)
        }

        return out
    }
}
