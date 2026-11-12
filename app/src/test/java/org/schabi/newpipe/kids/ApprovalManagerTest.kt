/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalManagerTest {
    private val video = StoredContentId(0, "v")
    private val second = StoredContentId(0, "v2")
    private val channel = StoredContentId(0, "c")
    private val playlist = StoredContentId(0, "p")
    private val initial = ProfileManager.add(ProfileManager.add(KidsState(), "a", "A", 5), "b", "B", 5)

    @Test fun `source changes preserve overlap and explicit hides`() {
        var state = ApprovalManager.approveSource(initial, setOf("a"), SourceKind.CHANNEL, channel)
        state = ApprovalManager.replaceSourceVideos(state, setOf("a"), SourceKind.CHANNEL, channel, listOf(video))
        state = ApprovalManager.approveSource(state, setOf("a"), SourceKind.PLAYLIST, playlist)
        state = ApprovalManager.replaceSourceVideos(state, setOf("a"), SourceKind.PLAYLIST, playlist, listOf(video))
        state = ApprovalManager.setVideoHidden(state, setOf("a"), video, true)
        assertFalse(state.profiles[0].approvedLibrary().canPlay(video.toContentId()))
        state = ApprovalManager.setVideoHidden(state, setOf("a"), video, false)
        state = ApprovalManager.removeSource(state, setOf("a"), SourceKind.CHANNEL, channel)
        assertTrue(state.profiles[0].approvedLibrary().canPlay(video.toContentId()))
        assertFalse(state.profiles[1].approvedLibrary().canPlay(video.toContentId()))
        state = ApprovalManager.setSourceEnabled(state, setOf("a"), SourceKind.PLAYLIST, playlist, false)
        assertFalse(state.profiles[0].approvedLibrary().canPlay(video.toContentId()))
    }

    @Test fun `successful refresh replaces membership and preserves source settings`() {
        var state = ApprovalManager.approveSource(initial, setOf("a"), SourceKind.PLAYLIST, playlist)
        state = ApprovalManager.replaceSourceVideos(state, setOf("a"), SourceKind.PLAYLIST, playlist, listOf(video))
        state = ApprovalManager.setSourceEnabled(state, setOf("a"), SourceKind.PLAYLIST, playlist, false)
        state = ApprovalManager.approveSource(state, setOf("a"), SourceKind.PLAYLIST, playlist)
        assertEquals(listOf(video), state.profiles[0].sources.single().videos)
        state = ApprovalManager.replaceSourceVideos(state, setOf("a"), SourceKind.PLAYLIST, playlist, listOf(second, video))
        assertEquals(listOf(second, video), state.profiles[0].sources.single().videos)
        assertFalse(state.profiles[0].sources.single().enabled)
        state = ApprovalManager.replaceSourceVideos(state, setOf("a"), SourceKind.PLAYLIST, playlist, emptyList())
        assertTrue(state.profiles[0].sources.single().videos.isEmpty())
    }

    @Test fun `individual approval applies only to selected profiles`() {
        var state = ApprovalManager.approveVideo(initial, setOf("a", "b"), video)
        state = ApprovalManager.removeVideoApproval(state, setOf("a"), video)
        assertFalse(state.profiles[0].approvedLibrary().canPlay(video.toContentId()))
        assertTrue(state.profiles[1].approvedLibrary().canPlay(video.toContentId()))
        assertEquals(state, KidsStateCodec.decode(KidsStateCodec.encode(state)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `refresh cannot recreate removed approval`() {
        ApprovalManager.replaceSourceVideos(initial, setOf("a"), SourceKind.CHANNEL, channel, listOf(video))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown profile is rejected`() {
        ApprovalManager.approveVideo(initial, setOf("a", "missing"), video)
    }
}
