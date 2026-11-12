/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalManagerTest {
    private val video = StoredContentId(0, "e_04ZrNroTo")
    private val second = StoredContentId(0, "dQw4w9WgXcQ")
    private val channel = StoredContentId(0, "c")
    private val playlist = StoredContentId(0, "p")
    private val initial = ProfileManager.add(ProfileManager.add(KidsState(), "a", "A", 5), "b", "B", 5)

    private fun refresh(state: KidsState, ids: Set<String>, kind: SourceKind, source: StoredContentId, videos: List<StoredContentId>): KidsState {
        val started = ApprovalManager.beginSourceRefresh(state, ids, kind, source)
        return ApprovalManager.completeSourceRefresh(started.state, started.ticket, videos)
    }

    @Test fun `source changes preserve overlap and explicit hides`() {
        var state = ApprovalManager.approveSource(initial, setOf("a"), SourceKind.CHANNEL, channel)
        state = refresh(state, setOf("a"), SourceKind.CHANNEL, channel, listOf(video))
        state = ApprovalManager.approveSource(state, setOf("a"), SourceKind.PLAYLIST, playlist)
        state = refresh(state, setOf("a"), SourceKind.PLAYLIST, playlist, listOf(video))
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
        state = refresh(state, setOf("a"), SourceKind.PLAYLIST, playlist, listOf(video))
        state = ApprovalManager.setSourceEnabled(state, setOf("a"), SourceKind.PLAYLIST, playlist, false)
        state = ApprovalManager.approveSource(state, setOf("a"), SourceKind.PLAYLIST, playlist)
        assertEquals(listOf(video), state.profiles[0].sources.single().videos)
        state = refresh(state, setOf("a"), SourceKind.PLAYLIST, playlist, listOf(second, video))
        assertEquals(listOf(second, video), state.profiles[0].sources.single().videos)
        assertFalse(state.profiles[0].sources.single().enabled)
        state = refresh(state, setOf("a"), SourceKind.PLAYLIST, playlist, emptyList())
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
        refresh(initial, setOf("a"), SourceKind.CHANNEL, channel, listOf(video))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown profile is rejected`() {
        ApprovalManager.approveVideo(initial, setOf("a", "missing"), video)
    }

    @Test fun `older refresh cannot overwrite a newer completed snapshot`() {
        val approved = ApprovalManager.approveSource(initial, setOf("a"), SourceKind.PLAYLIST, playlist)
        val older = ApprovalManager.beginSourceRefresh(approved, setOf("a"), SourceKind.PLAYLIST, playlist)
        val newer = ApprovalManager.beginSourceRefresh(older.state, setOf("a"), SourceKind.PLAYLIST, playlist)
        val latest = ApprovalManager.completeSourceRefresh(newer.state, newer.ticket, listOf(second))
        try {
            ApprovalManager.completeSourceRefresh(latest, older.ticket, listOf(video))
            throw AssertionError("A stale result must not overwrite the new snapshot")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(second), latest.profiles[0].sources.single().videos)
        }
    }

    @Test fun `a refresh cannot restore an approval removed and later readded`() {
        val approved = ApprovalManager.approveSource(initial, setOf("a"), SourceKind.CHANNEL, channel)
        val started = ApprovalManager.beginSourceRefresh(approved, setOf("a"), SourceKind.CHANNEL, channel)
        val removed = ApprovalManager.removeSource(started.state, setOf("a"), SourceKind.CHANNEL, channel)
        val readded = ApprovalManager.approveSource(removed, setOf("a"), SourceKind.CHANNEL, channel)
        try {
            ApprovalManager.completeSourceRefresh(readded, started.ticket, listOf(video))
            throw AssertionError("A removed approval must invalidate its refresh")
        } catch (_: IllegalArgumentException) {
            assertTrue(readded.profiles[0].sources.single().videos.isEmpty())
        }
    }

    @Test fun `failed refresh leaves previous snapshot intact`() {
        val approved = ApprovalManager.approveSource(initial, setOf("a"), SourceKind.CHANNEL, channel)
        val withVideos = refresh(approved, setOf("a"), SourceKind.CHANNEL, channel, listOf(video))
        val pending = ApprovalManager.beginSourceRefresh(withVideos, setOf("a"), SourceKind.CHANNEL, channel)
        assertTrue(pending.state.profiles[0].approvedLibrary().canPlay(video.toContentId()))
        assertEquals(pending.state, KidsStateCodec.decode(KidsStateCodec.encode(pending.state)))
    }
}
