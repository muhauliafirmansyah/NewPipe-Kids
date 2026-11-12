/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.net.URI
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

/** Service-qualified identities. YouTube's 11-character ID is case-sensitive. */
object VideoIdentity {
    private val youtubeId = Regex("^[A-Za-z0-9_-]{11}$")

    /** Accepts a YouTube video ID or a video URL supported by NewPipe Extractor. */
    fun youtube(value: String): StoredContentId {
        val raw = value.trim()
        val id = if (youtubeId.matches(raw)) raw else YoutubeStreamLinkHandlerFactory.getInstance().getId(raw)
        require(youtubeId.matches(id)) { "Invalid YouTube video ID" }
        return StoredContentId(ServiceList.YouTube.serviceId, id)
    }

    fun canonicalize(video: StoredContentId): StoredContentId {
        if (video.serviceId == ServiceList.YouTube.serviceId) return youtube(video.id)
        val service = service(video.serviceId)
        val input = URI(video.id.trim())
        require(input.scheme in setOf("http", "https") && input.host != null && input.userInfo == null) { "Invalid video URL" }
        if (service.serviceId == ServiceList.PeerTube.serviceId) {
            require(input.path.startsWith("/w/") || input.path.startsWith("/videos/")) { "Invalid PeerTube video URL" }
        } else {
            require(input.host.lowercase() in setOf("media.ccc.de", "api.media.ccc.de", "streaming.media.ccc.de")) { "Invalid media.ccc.de host" }
        }
        val url = service.streamLHFactory.fromUrl(input.toString()).url
        val canonical = URI(url)
        require(canonical.scheme in setOf("http", "https") && canonical.host != null) { "Invalid canonical video URL" }
        if (service.serviceId == ServiceList.PeerTube.serviceId) {
            require(input.host.equals(canonical.host, ignoreCase = true) && input.port == canonical.port) { "PeerTube instance mismatch" }
        }
        return StoredContentId(service.serviceId, url)
    }

    fun fromUrl(serviceId: Int, url: String): StoredContentId = canonicalize(StoredContentId(serviceId, url))

    fun fromAnyUrl(url: String): StoredContentId? = listOf(ServiceList.YouTube, ServiceList.MediaCCC, ServiceList.PeerTube)
        .firstNotNullOfOrNull { service -> runCatching { fromUrl(service.serviceId, url) }.getOrNull() }

    internal fun service(serviceId: Int): StreamingService = listOf(ServiceList.YouTube, ServiceList.PeerTube, ServiceList.MediaCCC)
        .firstOrNull { it.serviceId == serviceId } ?: throw IllegalArgumentException("Unsupported video service")
}
