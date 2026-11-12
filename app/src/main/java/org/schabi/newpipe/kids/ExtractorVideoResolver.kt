/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Context
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory
import com.google.android.exoplayer2.source.MediaSource
import com.google.android.exoplayer2.source.MergingMediaSource
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo

data class ResolvedVideo(val videoUrl: String, val audioUrl: String? = null)

/** Resolve on a network worker, then check the profile's approval again before playing. */
object ExtractorVideoResolver {
    fun resolve(id: StoredContentId): ResolvedVideo {
        val canonical = VideoIdentity.canonicalize(id)
        val service = VideoIdentity.service(canonical.serviceId)
        val url = if (canonical.serviceId == ServiceList.YouTube.serviceId) "https://www.youtube.com/watch?v=${canonical.id}" else canonical.id
        val extracted = runCatching { StreamInfo.getInfo(service, url) }
        val info = extracted.getOrNull()
        val combined = info?.videoStreams?.firstOrNull {
            it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.url != null
        }?.url
        if (combined != null) return ResolvedVideo(combined)
        if (canonical.serviceId == ServiceList.YouTube.serviceId) {
            val fallback = runCatching { YoutubePlaybackFallback.resolve(canonical.id) }.getOrNull()
            if (fallback != null) return ResolvedVideo(fallback)
        }
        val available = info ?: throw requireNotNull(extracted.exceptionOrNull())
        val video = available.videoOnlyStreams.firstOrNull { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.url != null }?.url
        val audio = available.audioStreams.firstOrNull { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.url != null }?.url
        return ResolvedVideo(requireNotNull(video), requireNotNull(audio))
    }

    fun mediaSource(context: Context, video: ResolvedVideo): MediaSource {
        val factory = DefaultMediaSourceFactory(context)
        fun source(url: String): MediaSource = factory.createMediaSource(MediaItem.fromUri(url))
        val picture = source(video.videoUrl)
        val audio = video.audioUrl ?: return picture
        return MergingMediaSource(picture, source(audio))
    }
}
