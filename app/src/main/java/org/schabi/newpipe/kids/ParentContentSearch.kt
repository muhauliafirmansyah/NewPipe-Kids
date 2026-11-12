/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import kotlinx.serialization.Serializable
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo

@Serializable
enum class ParentContentKind { VIDEO, CHANNEL, PLAYLIST }

@Serializable
data class ParentSearchItem(
    val kind: ParentContentKind,
    val id: StoredContentId,
    val title: String,
    val url: String,
    val thumbnailUrl: String? = null
) {
    init {
        require(title.isNotBlank() && url.isNotBlank())
    }
}

data class ParentSearchPage(
    val items: List<ParentSearchItem>,
    val query: SearchQueryHandler,
    val next: Page?,
    val serviceId: Int
)

/** Unrestricted discovery for a parent screen only. Never use its results directly as child content. */
object ParentContentSearch {
    fun firstPage(query: String, serviceId: Int = ServiceList.YouTube.serviceId): ParentSearchPage {
        val cleaned = query.trim()
        require(cleaned.isNotEmpty()) { "Search query cannot be empty" }
        val service = VideoIdentity.service(serviceId)
        val handler = service.searchQHFactory.fromQuery(cleaned)
        val info = SearchInfo.getInfo(service, handler)
        return ParentSearchPage(info.relatedItems.mapNotNull(::toItem).filter { it.id.serviceId == serviceId }, handler, info.nextPage, serviceId)
    }

    fun nextPage(previous: ParentSearchPage): ParentSearchPage {
        val next = requireNotNull(previous.next) { "No more results" }
        val info = SearchInfo.getMoreItems(VideoIdentity.service(previous.serviceId), previous.query, next)
        check(info.errors.isEmpty()) { "Search results were incomplete" }
        return ParentSearchPage(info.items.mapNotNull(::toItem).filter { it.id.serviceId == previous.serviceId }, previous.query, info.nextPage, previous.serviceId)
    }

    /** Ignores unsupported item types and malformed URLs instead of approving them. */
    internal fun toItem(item: InfoItem): ParentSearchItem? {
        if (item.name.isNullOrBlank()) return null
        return try {
            val service = VideoIdentity.service(item.serviceId)
            val kind: ParentContentKind
            val id: StoredContentId
            val url: String
            when (item.infoType) {
                InfoItem.InfoType.STREAM -> {
                    kind = ParentContentKind.VIDEO
                    id = VideoIdentity.fromUrl(service.serviceId, item.url)
                    url = if (service.serviceId == ServiceList.YouTube.serviceId) service.streamLHFactory.getUrl(id.id) else id.id
                }

                InfoItem.InfoType.CHANNEL -> {
                    kind = ParentContentKind.CHANNEL
                    id = SourceIdentity.fromUrl(service.serviceId, SourceKind.CHANNEL, item.url)
                    url = id.id
                }

                InfoItem.InfoType.PLAYLIST -> {
                    kind = ParentContentKind.PLAYLIST
                    id = SourceIdentity.fromUrl(service.serviceId, SourceKind.PLAYLIST, item.url)
                    url = id.id
                }

                else -> return null
            }
            ParentSearchItem(kind, id, item.name, url, item.thumbnails.firstOrNull()?.url)
        } catch (_: Exception) {
            null
        }
    }
}
