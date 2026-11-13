/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.util.Locale

/** Child-facing catalog. Missing metadata stays hidden until a successful refresh supplies it. */
object ChildLibrary {
    /** Channel playlists are navigation only, limited to that channel's own snapshot. */
    fun sources(profile: ChildProfile, includeHiddenChannelPlaylists: Boolean = false): List<StoredSource> {
        val explicit = profile.sources.filter { it.enabled }
        val disabledPlaylists = profile.sources.filter { it.kind == SourceKind.PLAYLIST && !it.enabled }.mapTo(mutableSetOf()) { it.id }
        val explicitPlaylists = explicit.filter { it.kind == SourceKind.PLAYLIST }.mapTo(mutableSetOf()) { it.id }
        val inherited = linkedMapOf<StoredContentId, StoredSource>()
        explicit.filter { it.kind == SourceKind.CHANNEL }.forEach { channel ->
            val channelVideos = channel.videos.mapTo(mutableSetOf()) { VideoIdentity.canonicalize(it) }
            channel.channelPlaylists.forEach { playlist ->
                if (playlist.id !in disabledPlaylists && playlist.id !in explicitPlaylists &&
                    (includeHiddenChannelPlaylists || playlist.id !in profile.hiddenChannelPlaylists)
                ) {
                    val ownVideos = playlist.videos.map { VideoIdentity.canonicalize(it) }.filter { it in channelVideos }
                    val previous = inherited[playlist.id]
                    val combined = if (previous == null) ownVideos else previous.videos + ownVideos.filterNot { it in previous.videos }
                    inherited[playlist.id] = StoredSource(SourceKind.PLAYLIST, playlist.id, videos = combined)
                }
            }
        }
        return explicit + inherited.values
    }

    /** The extractor can omit Shorts labels; only positively identified Shorts are filtered. */
    fun hiddenShorts(state: KidsState, profile: ChildProfile): Set<StoredContentId> = if (profile.showShorts) {
        emptySet()
    } else {
        state.catalog.asSequence().filter { it.kind == ParentContentKind.VIDEO && it.isShortForm }
            .mapNotNull { runCatching { VideoIdentity.canonicalize(it.id) }.getOrNull() }.toSet()
    }

    fun canPlay(state: KidsState, profile: ChildProfile, video: StoredContentId): Boolean {
        val canonical = runCatching { VideoIdentity.canonicalize(video) }.getOrNull() ?: return false
        return canonical !in hiddenShorts(state, profile) && profile.approvedLibrary().canPlay(canonical.toContentId())
    }

    fun videos(state: KidsState, profileId: String, query: String = ""): List<ParentSearchItem> {
        val profile = state.profiles.firstOrNull { it.id == profileId && it.enabled } ?: return emptyList()
        if (profile.remainingMillis <= 0) return emptyList()
        val allowed = profile.approvedLibrary()
        val hiddenShorts = hiddenShorts(state, profile)
        val text = query.trim().lowercase(Locale.ROOT)
        return state.catalog.asSequence()
            .filter { it.kind == ParentContentKind.VIDEO }
            .mapNotNull { item ->
                val canonical = runCatching { VideoIdentity.canonicalize(item.id) }.getOrNull() ?: return@mapNotNull null
                if (item.id == canonical) item else item.copy(id = canonical)
            }
            .filter { it.id !in hiddenShorts && allowed.canPlay(it.id.toContentId()) }
            // A video may be covered by several sources, or stored under an older URL alias.
            .distinctBy { it.id }
            .filter { it.title.lowercase(Locale.ROOT).contains(text) }
            .sortedBy { it.title.lowercase(Locale.ROOT) }
            .toList()
    }
}
