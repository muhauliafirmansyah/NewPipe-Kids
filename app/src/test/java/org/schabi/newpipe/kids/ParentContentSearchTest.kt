/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

class ParentContentSearchTest {
    @Test fun `search items get validated YouTube identities`() {
        val service = ServiceList.YouTube.serviceId
        val stream = StreamInfoItem(service, "https://youtu.be/e_04ZrNroTo", "Song", StreamType.VIDEO_STREAM)
        val result = requireNotNull(ParentContentSearch.toItem(stream))
        assertEquals(ParentContentKind.VIDEO, result.kind)
        assertEquals(VideoIdentity.youtube("e_04ZrNroTo"), result.id)
        val channel = ChannelInfoItem(service, "https://www.youtube.com/channel/UCTwECeGqMZee77BjdoYtI2Q", "Channel")
        assertEquals(ParentContentKind.CHANNEL, ParentContentSearch.toItem(channel)?.kind)
        val playlist = PlaylistInfoItem(service, "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8", "Playlist")
        assertEquals(ParentContentKind.PLAYLIST, ParentContentSearch.toItem(playlist)?.kind)
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
