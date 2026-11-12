/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import java.net.URI
import org.schabi.newpipe.extractor.ServiceList

/** Normalize a source with the extractor for that service, keeping federated hosts distinct. */
object SourceIdentity {
    fun fromUrl(serviceId: Int, kind: SourceKind, url: String): StoredContentId {
        val service = VideoIdentity.service(serviceId)
        val input = URI(url.trim())
        require(input.scheme in setOf("http", "https") && input.host != null && input.userInfo == null)
        val host = input.host.lowercase()
        when (serviceId) {
            ServiceList.YouTube.serviceId -> require(host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com"))

            ServiceList.MediaCCC.serviceId -> require(host in setOf("media.ccc.de", "api.media.ccc.de", "streaming.media.ccc.de"))

            ServiceList.PeerTube.serviceId -> {
                val prefixes = if (kind == SourceKind.CHANNEL) listOf("/accounts/", "/a/", "/video-channels/", "/c/") else listOf("/videos/watch/playlist/", "/w/p/", "/video-playlists/", "/api/v1/video-playlists/")
                require(prefixes.any { input.path.startsWith(it) })
            }
        }
        val factory = if (kind == SourceKind.CHANNEL) service.channelLHFactory else service.playlistLHFactory
        val canonical = factory.fromUrl(input.toString()).url
        val parsed = URI(canonical)
        require(parsed.scheme in setOf("http", "https") && parsed.host != null)
        if (serviceId == ServiceList.PeerTube.serviceId) {
            require(input.host.equals(parsed.host, ignoreCase = true) && input.port == parsed.port)
        }
        return StoredContentId(serviceId, canonical)
    }
}
