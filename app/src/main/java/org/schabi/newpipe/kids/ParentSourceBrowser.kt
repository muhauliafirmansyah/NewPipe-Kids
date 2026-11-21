/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** A browsing page is independent of the complete snapshots used for source approval. */
internal data class ParentSourcePage(val videos: List<ParentSearchItem>, val next: ParentSourceCursor?, val incomplete: Boolean = false)

internal sealed class ParentSourceCursor {
    abstract val serviceId: Int
    abstract val pageCount: Int

    data class Playlist(override val serviceId: Int, val url: String, val page: Page, override val pageCount: Int) : ParentSourceCursor()
    data class Channel(
        override val serviceId: Int,
        val tabs: List<ListLinkHandler>,
        val index: Int,
        val page: Page?,
        override val pageCount: Int
    ) : ParentSourceCursor()
}

internal object ParentSourceBrowser {
    fun firstPage(kind: SourceKind, id: StoredContentId): ParentSourcePage {
        require(SourceIdentity.fromUrl(id.serviceId, kind, id.id) == id) { "Invalid source identity" }
        val service = VideoIdentity.service(id.serviceId)
        return when (kind) {
            SourceKind.PLAYLIST -> {
                val url = service.playlistLHFactory.fromUrl(id.id).url
                val info = PlaylistInfo.getInfo(service, url)
                check(info.errors.isEmpty() || info.relatedItems.isNotEmpty() || info.nextPage != null) { "Playlist extraction failed" }
                ParentSourcePage(videos(info.relatedItems, id.serviceId), info.nextPage?.let { ParentSourceCursor.Playlist(id.serviceId, url, it, 1) }, info.errors.isNotEmpty())
            }

            SourceKind.CHANNEL -> {
                val url = service.channelLHFactory.fromUrl(id.id).url
                val info = ChannelInfo.getInfo(service, url)
                val supported = setOf(ChannelTabs.VIDEOS, ChannelTabs.SHORTS, ChannelTabs.LIVESTREAMS)
                val tabs = info.tabs.filter { tab -> tab.contentFilters.any { it in supported } }
                check(tabs.isNotEmpty()) { "Could not determine the channel's video tabs" }
                channelPage(id.serviceId, tabs, 0, null, 1).let { it.copy(incomplete = it.incomplete || info.errors.isNotEmpty()) }
            }
        }
    }

    fun nextPage(cursor: ParentSourceCursor): ParentSourcePage {
        check(cursor.pageCount < MAX_PAGES) { "Source pagination limit exceeded" }
        val service = VideoIdentity.service(cursor.serviceId)
        return when (cursor) {
            is ParentSourceCursor.Playlist -> {
                val info = PlaylistInfo.getMoreItems(service, cursor.url, cursor.page)
                check(info.errors.isEmpty() || info.items.isNotEmpty() || info.nextPage != null) { "Playlist page extraction failed" }
                ParentSourcePage(videos(info.items, cursor.serviceId), info.nextPage?.let { cursor.copy(page = it, pageCount = cursor.pageCount + 1) }, info.errors.isNotEmpty())
            }

            is ParentSourceCursor.Channel -> channelPage(cursor.serviceId, cursor.tabs, cursor.index, cursor.page, cursor.pageCount + 1)
        }
    }

    private fun channelPage(serviceId: Int, tabs: List<ListLinkHandler>, index: Int, page: Page?, count: Int): ParentSourcePage {
        val service = VideoIdentity.service(serviceId)
        val tab = tabs[index]
        val shortTab = ChannelTabs.SHORTS in tab.contentFilters
        val items: List<StreamInfoItem>
        val next: Page?
        val incomplete: Boolean
        if (page == null) {
            val info = ChannelTabInfo.getInfo(service, tab)
            check(info.errors.isEmpty() || info.relatedItems.isNotEmpty() || info.nextPage != null) { "Channel tab extraction failed" }
            items = info.relatedItems.filterIsInstance<StreamInfoItem>()
            next = info.nextPage
            incomplete = info.errors.isNotEmpty()
        } else {
            val info = ChannelTabInfo.getMoreItems(service, tab, page)
            check(info.errors.isEmpty() || info.items.isNotEmpty() || info.nextPage != null) { "Channel page extraction failed" }
            items = info.items.filterIsInstance<StreamInfoItem>()
            next = info.nextPage
            incomplete = info.errors.isNotEmpty()
        }
        val cursor = when {
            next != null -> ParentSourceCursor.Channel(serviceId, tabs, index, next, count)
            index + 1 < tabs.size -> ParentSourceCursor.Channel(serviceId, tabs, index + 1, null, count)
            else -> null
        }
        return ParentSourcePage(videos(items, serviceId, shortTab), cursor, incomplete)
    }

    private fun videos(items: List<StreamInfoItem>, serviceId: Int, shortTab: Boolean = false): List<ParentSearchItem> = items.mapNotNull { item ->
        if (item.serviceId != serviceId) return@mapNotNull null
        ParentContentSearch.toItem(item)?.takeIf { it.kind == ParentContentKind.VIDEO }?.let {
            if (shortTab) it.copy(isShortForm = true) else it
        }
    }

    private const val MAX_PAGES = 1_000
}
