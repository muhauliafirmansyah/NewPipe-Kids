/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.util.UUID

data class SourceRefreshTicket(
    val profileIds: Set<String>,
    val kind: SourceKind,
    val id: StoredContentId,
    val token: String
)

data class StartedSourceRefresh(val state: KidsState, val ticket: SourceRefreshTicket)

/** Callers must authorize parent actions. Refresh results must be complete before applying them. */
object ApprovalManager {
    fun approveVideo(state: KidsState, profileIds: Set<String>, video: StoredContentId): KidsState {
        val canonical = VideoIdentity.canonicalize(video)
        return changeProfiles(state, profileIds) { it.copy(approvedVideos = it.approvedVideos + canonical) }
    }

    fun removeVideoApproval(state: KidsState, profileIds: Set<String>, video: StoredContentId): KidsState {
        val canonical = VideoIdentity.canonicalize(video)
        return changeProfiles(state, profileIds) { it.copy(approvedVideos = it.approvedVideos - canonical) }
    }

    /** Hiding a video overrides every source, even if it was not individually approved. */
    fun setVideoHidden(state: KidsState, profileIds: Set<String>, video: StoredContentId, hidden: Boolean): KidsState {
        val canonical = VideoIdentity.canonicalize(video)
        return changeProfiles(state, profileIds) {
            it.copy(hiddenVideos = if (hidden) it.hiddenVideos + canonical else it.hiddenVideos - canonical)
        }
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

    /** Hides an inherited playlist without approving the other videos in it. */
    fun setChannelPlaylistHidden(state: KidsState, profileIds: Set<String>, playlist: StoredContentId, hidden: Boolean): KidsState = changeProfiles(state, profileIds) { profile ->
        require(profile.sources.any { it.kind == SourceKind.CHANNEL && it.channelPlaylists.any { entry -> entry.id == playlist } }) { "Channel playlist unavailable" }
        profile.copy(hiddenChannelPlaylists = if (hidden) profile.hiddenChannelPlaylists + playlist else profile.hiddenChannelPlaylists - playlist)
    }

    /** Persist the returned state before fetching; a newer refresh invalidates older tickets. */
    fun beginSourceRefresh(
        state: KidsState,
        profileIds: Set<String>,
        kind: SourceKind,
        id: StoredContentId
    ): StartedSourceRefresh {
        val ticket = SourceRefreshTicket(profileIds.toSet(), kind, id, UUID.randomUUID().toString())
        val updated = changeProfiles(state, profileIds) { profile ->
            require(profile.sources.any { it.kind == kind && it.id == id }) { "Source not approved" }
            profile.copy(
                sources = profile.sources.map {
                    if (it.kind == kind && it.id == id) it.copy(activeRefreshToken = ticket.token) else it
                }
            )
        }
        return StartedSourceRefresh(updated, ticket)
    }

    /** Apply only a complete fetch while its ticket is still current for every profile. */
    fun completeSourceRefresh(
        state: KidsState,
        ticket: SourceRefreshTicket,
        videos: List<StoredContentId>
    ): KidsState {
        val canonicalVideos = videos.map { video ->
            require(video.serviceId == ticket.id.serviceId) { "Video and source services differ" }
            VideoIdentity.canonicalize(video)
        }
        return changeProfiles(state, ticket.profileIds) { profile ->
            require(
                profile.sources.any {
                    it.kind == ticket.kind && it.id == ticket.id && it.activeRefreshToken == ticket.token
                }
            ) { "Source refresh is stale or no longer approved" }
            profile.copy(
                sources = profile.sources.map {
                    if (it.kind == ticket.kind && it.id == ticket.id) {
                        it.copy(videos = canonicalVideos, activeRefreshToken = null)
                    } else {
                        it
                    }
                }
            )
        }
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
