/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.schabi.newpipe.extractor.ServiceList

class SourceIdentityTest {
    @Test fun `youtube source links normalize by kind`() {
        val id = "PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8"
        val first = SourceIdentity.fromUrl(ServiceList.YouTube.serviceId, SourceKind.PLAYLIST, "https://m.youtube.com/playlist?list=$id")
        val second = SourceIdentity.fromUrl(ServiceList.YouTube.serviceId, SourceKind.PLAYLIST, "https://www.youtube.com/playlist?list=$id")
        assertEquals(first, second)
    }

    @Test fun `federated source identities retain their instance`() {
        val service = ServiceList.PeerTube.serviceId
        val first = SourceIdentity.fromUrl(service, SourceKind.CHANNEL, "https://video.example/video-channels/example")
        val second = SourceIdentity.fromUrl(service, SourceKind.CHANNEL, "https://other.example/video-channels/example")
        assertFalse(first == second)
        val playlist = SourceIdentity.fromUrl(service, SourceKind.PLAYLIST, "https://video.example/w/p/12345678-1234-1234-1234-123456789abc")
        assertEquals(playlist, SourceIdentity.fromUrl(service, SourceKind.PLAYLIST, playlist.id))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `youtube sources reject unrelated hosts`() {
        SourceIdentity.fromUrl(ServiceList.YouTube.serviceId, SourceKind.CHANNEL, "https://example.org/channel/UCTwECeGqMZee77BjdoYtI2Q")
    }
}
