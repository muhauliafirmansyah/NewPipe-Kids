/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.util.UUID
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem

/** Channel playlists are discovered separately from the channel's video approvals. */
interface ChannelPlaylistFetcher {
    fun playlists(channel: StoredContentId): List<ParentSearchItem>
    fun videos(playlist: StoredContentId): List<ParentSearchItem>
}

class ExtractorChannelPlaylistFetcher : ChannelPlaylistFetcher {
    override fun playlists(channel: StoredContentId): List<ParentSearchItem> {
        val service = VideoIdentity.service(channel.serviceId)
        require(SourceIdentity.fromUrl(channel.serviceId, SourceKind.CHANNEL, channel.id) == channel)
        val info = ChannelInfo.getInfo(service, channel.id)
        check(info.errors.isEmpty()) { "Channel extraction was incomplete" }
        val tabs = info.tabs.filter { ChannelTabs.PLAYLISTS in it.contentFilters }
        return tabs.flatMap { tab ->
            val first = ChannelTabInfo.getInfo(service, tab)
            check(first.errors.isEmpty()) { "Channel playlist extraction was incomplete" }
            fun items(values: List<org.schabi.newpipe.extractor.InfoItem>) = values.filterIsInstance<PlaylistInfoItem>().map {
                requireNotNull(ParentContentSearch.toItem(it)) { "Could not read channel playlist" }
            }
            collectChannelPlaylistPages(ChannelPlaylistPage(items(first.relatedItems), first.nextPage)) { page ->
                val next = ChannelTabInfo.getMoreItems(service, tab, page)
                check(next.errors.isEmpty()) { "Channel playlist page extraction was incomplete" }
                ChannelPlaylistPage(items(next.items), next.nextPage)
            }
        }
    }

    override fun videos(playlist: StoredContentId): List<ParentSearchItem> = ExtractorSourceFetcher().fetchAll(SourceKind.PLAYLIST, playlist)
}

internal data class ChannelPlaylistPage(val playlists: List<ParentSearchItem>, val next: Page?)

internal fun collectChannelPlaylistPages(first: ChannelPlaylistPage, next: (Page) -> ChannelPlaylistPage): List<ParentSearchItem> {
    val results = mutableListOf<ParentSearchItem>()
    var current = first
    var pages = 0
    while (true) {
        check(++pages <= 1_000) { "Channel playlist pagination limit exceeded" }
        results.addAll(current.playlists)
        current = next(current.next ?: return results)
    }
}

/** Keeps complete, independently refreshed snapshots and rejects stale results. */
class ChannelPlaylistRefreshCoordinator(
    private val storage: KidsStorage,
    private val fetcher: ChannelPlaylistFetcher,
    private val onDiscovered: () -> Unit = {}
) {
    fun refresh(profileIds: Set<String>, channel: StoredContentId): KidsState {
        val token = UUID.randomUUID().toString()
        storage.update { state -> updateChannels(state, profileIds, channel, null) { it.copy(activePlaylistRefreshToken = token) } }
        val playlists = fetcher.playlists(channel).onEach { item ->
            require(
                item.kind == ParentContentKind.PLAYLIST && item.id.serviceId == channel.serviceId &&
                    SourceIdentity.fromUrl(item.id.serviceId, SourceKind.PLAYLIST, item.url) == item.id
            ) { "Invalid channel playlist" }
        }.distinctBy { it.id }
        storage.update { state ->
            val updated = updateChannels(state, profileIds, channel, token) { source ->
                val old = source.channelPlaylists.associateBy { it.id }
                source.copy(channelPlaylists = playlists.map { old[it.id] ?: StoredChannelPlaylist(it.id) })
            }
            updateCatalog(updated, ParentContentKind.PLAYLIST, playlists)
        }
        onDiscovered()
        playlists.forEach { playlist ->
            // A broken playlist keeps its own last complete snapshot; others can still refresh.
            val videos = runCatching {
                fetcher.videos(playlist.id).onEach { item ->
                    require(
                        item.kind == ParentContentKind.VIDEO && item.id.serviceId == channel.serviceId &&
                            VideoIdentity.fromUrl(item.id.serviceId, item.url) == item.id
                    ) { "Invalid playlist video" }
                }
            }.getOrNull() ?: return@forEach
            storage.update { state ->
                val updated = updateChannels(state, profileIds, channel, token) { source ->
                    source.copy(
                        channelPlaylists = source.channelPlaylists.map {
                            if (it.id == playlist.id) it.copy(videos = videos.map { video -> video.id }) else it
                        }
                    )
                }
                updateCatalog(updated, ParentContentKind.VIDEO, videos)
            }
        }
        return storage.update { state -> updateChannels(state, profileIds, channel, token) { it.copy(activePlaylistRefreshToken = null) } }
    }

    private fun updateChannels(
        state: KidsState,
        profileIds: Set<String>,
        channel: StoredContentId,
        token: String?,
        change: (StoredSource) -> StoredSource
    ): KidsState {
        require(profileIds.isNotEmpty() && state.profiles.map { it.id }.containsAll(profileIds)) { "Profile no longer available" }
        return state.copy(
            profiles = state.profiles.map { profile ->
                if (profile.id !in profileIds) return@map profile
                require(
                    profile.sources.any {
                        it.kind == SourceKind.CHANNEL && it.id == channel && (token == null || it.activePlaylistRefreshToken == token)
                    }
                ) { "Channel refresh is stale or no longer approved" }
                profile.copy(sources = profile.sources.map { if (it.kind == SourceKind.CHANNEL && it.id == channel) change(it) else it })
            }
        )
    }

    private fun updateCatalog(state: KidsState, kind: ParentContentKind, items: List<ParentSearchItem>): KidsState {
        require(items.all { it.kind == kind })
        return state.copy(catalog = KidsCatalog.merge(state.catalog, items))
    }
}
