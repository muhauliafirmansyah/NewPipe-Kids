/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.util.Locale

/** Child-facing catalog. Missing metadata stays hidden until a successful refresh supplies it. */
object ChildLibrary {
    fun videos(state: KidsState, profileId: String, query: String = ""): List<ParentSearchItem> {
        val profile = state.profiles.firstOrNull { it.id == profileId && it.enabled } ?: return emptyList()
        if (profile.remainingMillis <= 0) return emptyList()
        val allowed = profile.approvedLibrary()
        val text = query.trim().lowercase(Locale.ROOT)
        return state.catalog.asSequence()
            .filter { it.kind == ParentContentKind.VIDEO }
            .filter { allowed.canPlay(it.id.toContentId()) }
            .filter { it.title.lowercase(Locale.ROOT).contains(text) }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .toList()
    }
}
