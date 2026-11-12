/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Evaluate immediately before starting playback and whenever the saved library changes. */
object ChildPlaybackAccess {
    fun allowed(state: KidsState, profileId: String, video: StoredContentId): Boolean {
        if (state.selectedProfileId != profileId) return false
        val profile = state.profiles.firstOrNull { it.id == profileId && it.enabled } ?: return false
        if (profile.remainingMillis <= 0) return false
        return profile.approvedLibrary().canPlay(video.toContentId())
    }
}
