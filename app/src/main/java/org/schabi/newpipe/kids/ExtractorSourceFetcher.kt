/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Fetches complete supported playlist and channel video snapshots on a worker thread. */
class ExtractorSourceFetcher : SourceVideoFetcher {
    override fun fetchAll(kind: SourceKind, id: StoredContentId): List<ParentSearchItem> {
        val service = VideoIdentity.service(id.serviceId)
        require(SourceIdentity.fromUrl(id.serviceId, kind, id.id) == id) { "Invalid source identity" }
        return when (kind) {
            SourceKind.PLAYLIST -> playlist(service, id.id)
            SourceKind.CHANNEL -> channel(service, id.id)
        }
    }

    private fun playlist(service: StreamingService, url: String): List<ParentSearchItem> {
        val canonicalUrl = service.playlistLHFactory.fromUrl(url).url
        val info = PlaylistInfo.getInfo(service, canonicalUrl)
        check(info.errors.isEmpty()) { "Playlist extraction was incomplete" }
        return collectSourcePages(SourcePage(info.relatedItems.map { video(it, service.serviceId) }, info.nextPage)) { page ->
            val next = PlaylistInfo.getMoreItems(service, canonicalUrl, page)
            check(next.errors.isEmpty()) { "Playlist page extraction was incomplete" }
            SourcePage(next.items.map { video(it, service.serviceId) }, next.nextPage)
        }
    }

    private fun channel(service: StreamingService, url: String): List<ParentSearchItem> {
        val canonicalUrl = service.channelLHFactory.fromUrl(url).url
        val info = ChannelInfo.getInfo(service, canonicalUrl)
        check(info.errors.isEmpty()) { "Channel extraction was incomplete" }
        val supportedTabs = setOf(ChannelTabs.VIDEOS, ChannelTabs.SHORTS, ChannelTabs.LIVESTREAMS)
        val tabs = info.tabs.filter { tab -> tab.contentFilters.any { it in supportedTabs } }
        check(tabs.isNotEmpty()) { "Could not determine the channel's video tabs" }
        return tabs.flatMap { tab ->
            val shortTab = ChannelTabs.SHORTS in tab.contentFilters
            val first = ChannelTabInfo.getInfo(service, tab)
            check(first.errors.isEmpty()) { "Channel tab extraction was incomplete" }
            collectSourcePages(SourcePage(first.relatedItems.filterIsInstance<StreamInfoItem>().map { video(it, service.serviceId, shortTab) }, first.nextPage)) { page ->
                val next = ChannelTabInfo.getMoreItems(service, tab, page)
                check(next.errors.isEmpty()) { "Channel page extraction was incomplete" }
                SourcePage(next.items.filterIsInstance<StreamInfoItem>().map { video(it, service.serviceId, shortTab) }, next.nextPage)
            }
        }
    }

    private fun video(item: StreamInfoItem, serviceId: Int, shortTab: Boolean = false): ParentSearchItem {
        require(item.serviceId == serviceId) { "Unexpected video service" }
        val video = requireNotNull(ParentContentSearch.toItem(item)) { "Could not read video identity or title" }
        return if (shortTab) video.copy(isShortForm = true) else video
    }
}

internal data class SourcePage(val videos: List<ParentSearchItem>, val next: Page?)

/** Never return a partial snapshot when pagination fails or exceeds the safety bound. */
internal fun collectSourcePages(first: SourcePage, loadNext: (Page) -> SourcePage): List<ParentSearchItem> {
    val videos = mutableListOf<ParentSearchItem>()
    var current = first
    var pages = 0
    while (true) {
        check(++pages <= 1_000) { "Source pagination limit exceeded" }
        videos.addAll(current.videos)
        val next = current.next ?: return videos
        current = loadNext(next)
    }
}
