/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.util.image.ImageStrategy
import org.schabi.newpipe.util.image.PreferredImageQuality

class ParentContentSearchTest {
    @Test fun `search items get validated YouTube identities`() {
        val service = ServiceList.YouTube.serviceId
        val stream = StreamInfoItem(service, "https://youtu.be/e_04ZrNroTo", "Song", StreamType.VIDEO_STREAM)
        stream.duration = 599
        val result = requireNotNull(ParentContentSearch.toItem(stream))
        assertEquals(ParentContentKind.VIDEO, result.kind)
        assertEquals(599L, result.durationSeconds)
        assertEquals(VideoIdentity.youtube("e_04ZrNroTo"), result.id)
        val channel = ChannelInfoItem(service, "https://www.youtube.com/channel/UCTwECeGqMZee77BjdoYtI2Q", "Channel")
        assertEquals(ParentContentKind.CHANNEL, ParentContentSearch.toItem(channel)?.kind)
        val playlist = PlaylistInfoItem(service, "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8", "Playlist")
        playlist.uploaderName = "Science channel"
        assertEquals(ParentContentKind.PLAYLIST, ParentContentSearch.toItem(playlist)?.kind)
        assertEquals("Science channel", ParentContentSearch.toItem(playlist)?.uploaderName)
    }

    @Test fun `shorts metadata and shorts URLs are retained for filtering`() {
        val service = ServiceList.YouTube.serviceId
        val flagged = StreamInfoItem(service, "https://youtu.be/e_04ZrNroTo", "Short", StreamType.VIDEO_STREAM)
        flagged.isShortFormContent = true
        assertEquals(true, ParentContentSearch.toItem(flagged)?.isShortForm)
        val linked = StreamInfoItem(service, "https://youtube.com/shorts/e_04ZrNroTo", "Short link", StreamType.VIDEO_STREAM)
        assertEquals(true, ParentContentSearch.toItem(linked)?.isShortForm)
    }

    @Test fun `search stores an appropriately sized thumbnail for larger cards`() {
        val stream = StreamInfoItem(ServiceList.YouTube.serviceId, "https://youtu.be/e_04ZrNroTo", "Song", StreamType.VIDEO_STREAM)
        stream.thumbnails = listOf(
            Image("https://example.org/small.jpg", 90, 160, Image.ResolutionLevel.LOW),
            Image("https://example.org/medium.jpg", 250, 444, Image.ResolutionLevel.MEDIUM)
        )
        ImageStrategy.setPreferredImageQuality(PreferredImageQuality.MEDIUM)
        assertEquals("https://example.org/medium.jpg", ParentContentSearch.toItem(stream)?.thumbnailUrl)
    }

    @Test fun `invalid search items are not exposed as approvable content`() {
        val service = ServiceList.YouTube.serviceId
        val bad = StreamInfoItem(service, "https://example.org/watch?v=e_04ZrNroTo", "Bad", StreamType.VIDEO_STREAM)
        val other = StreamInfoItem(999, "https://youtu.be/e_04ZrNroTo", "Other", StreamType.VIDEO_STREAM)
        assertNull(ParentContentSearch.toItem(bad))
        assertNull(ParentContentSearch.toItem(other))
    }

    @Test fun `peer tube and media ccc results receive service-qualified video identities`() {
        val peer = ServiceList.PeerTube.serviceId
        val ccc = ServiceList.MediaCCC.serviceId
        val peerItem = StreamInfoItem(peer, "https://video.example/w/12345678-1234-1234-1234-123456789abc", "Peer video", StreamType.VIDEO_STREAM)
        val cccItem = StreamInfoItem(ccc, "https://media.ccc.de/v/example-talk", "Conference video", StreamType.VIDEO_STREAM)
        assertEquals(VideoIdentity.fromUrl(peer, peerItem.url), ParentContentSearch.toItem(peerItem)?.id)
        assertEquals(VideoIdentity.fromUrl(ccc, cccItem.url), ParentContentSearch.toItem(cccItem)?.id)
    }
}
