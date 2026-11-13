/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Keep a positive Shorts identification when another source omits the label. */
object KidsCatalog {
    fun merge(existing: List<ParentSearchItem>, incoming: List<ParentSearchItem>): List<ParentSearchItem> {
        val previous = existing.associateBy { it.kind to it.id }
        val updated = linkedMapOf<Pair<ParentContentKind, StoredContentId>, ParentSearchItem>()
        incoming.forEach { item ->
            val key = item.kind to item.id
            val knownShort = item.isShortForm || previous[key]?.isShortForm == true || updated[key]?.isShortForm == true
            updated[key] = if (knownShort == item.isShortForm) item else item.copy(isShortForm = knownShort)
        }
        return existing.filterNot { (it.kind to it.id) in updated } + updated.values
    }
}
