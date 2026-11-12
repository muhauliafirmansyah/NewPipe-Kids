/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Callers must authorize parent actions. Refresh results must be complete before applying them. */
object ApprovalManager {
    fun approveVideo(state: KidsState, profileIds: Set<String>, video: StoredContentId): KidsState = changeProfiles(state, profileIds) { it.copy(approvedVideos = it.approvedVideos + video) }

    fun removeVideoApproval(state: KidsState, profileIds: Set<String>, video: StoredContentId): KidsState = changeProfiles(state, profileIds) { it.copy(approvedVideos = it.approvedVideos - video) }

    /** Hiding a video overrides every source, even if it was not individually approved. */
    fun setVideoHidden(state: KidsState, profileIds: Set<String>, video: StoredContentId, hidden: Boolean): KidsState = changeProfiles(state, profileIds) {
        it.copy(hiddenVideos = if (hidden) it.hiddenVideos + video else it.hiddenVideos - video)
    }

    /** Reapproving an existing source leaves its cached membership and enabled state unchanged. */
    fun approveSource(state: KidsState, profileIds: Set<String>, kind: SourceKind, id: StoredContentId): KidsState = changeProfiles(state, profileIds) { profile ->
        if (profile.sources.any { it.kind == kind && it.id == id }) {
            profile
        } else {
            profile.copy(sources = profile.sources + StoredSource(kind, id))
        }
    }

    fun removeSource(state: KidsState, profileIds: Set<String>, kind: SourceKind, id: StoredContentId): KidsState = changeProfiles(state, profileIds) { profile ->
        profile.copy(sources = profile.sources.filterNot { it.kind == kind && it.id == id })
    }

    fun setSourceEnabled(
        state: KidsState,
        profileIds: Set<String>,
        kind: SourceKind,
        id: StoredContentId,
        enabled: Boolean
    ): KidsState = changeProfiles(state, profileIds) { profile ->
        require(profile.sources.any { it.kind == kind && it.id == id }) { "Source not approved" }
        profile.copy(
            sources = profile.sources.map {
                if (it.kind == kind && it.id == id) it.copy(enabled = enabled) else it
            }
        )
    }

    /** Apply only a fully fetched snapshot; failed or partial fetches must never call this. */
    fun replaceSourceVideos(
        state: KidsState,
        profileIds: Set<String>,
        kind: SourceKind,
        id: StoredContentId,
        videos: List<StoredContentId>
    ): KidsState = changeProfiles(state, profileIds) { profile ->
        require(profile.sources.any { it.kind == kind && it.id == id }) { "Source not approved" }
        profile.copy(
            sources = profile.sources.map {
                if (it.kind == kind && it.id == id) it.copy(videos = videos.toList()) else it
            }
        )
    }

    private fun changeProfiles(
        state: KidsState,
        ids: Set<String>,
        transform: (ChildProfile) -> ChildProfile
    ): KidsState {
        require(ids.isNotEmpty()) { "Select at least one profile" }
        require(state.profiles.map { it.id }.containsAll(ids)) { "Profile not found" }
        return state.copy(profiles = state.profiles.map { if (it.id in ids) transform(it) else it })
    }
}
