/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsPlaybackTest {
    private val a = VideoIdentity.youtube("e_04ZrNroTo")
    private val b = VideoIdentity.youtube("dQw4w9WgXcQ")
    private val c = VideoIdentity.youtube("abcdefghijk")
    private val profile = ChildProfile(
        "a",
        "A",
        sessionLimitMinutes = 5,
        remainingMillis = 300_000,
        approvedVideos = setOf(a, b)
    )

    @Test fun `only a completed viewing marks video watched, but later partial view updates recency`() {
        var p = KidsPlayback.recordPlaybackInterval(profile, a, 0, 89, 100, 10)
        assertFalse(p.history.single().watched)
        p = KidsPlayback.recordPlaybackInterval(p, a, 89, 90, 100, 20)
        assertTrue(p.history.single().watched)
        p = KidsPlayback.recordPlaybackInterval(p, a, 0, 1, 100, 30)
        assertTrue(p.history.single().watched)
        assertEquals(30, p.history.single().lastWatchedAtMillis)
    }

    @Test fun `autoplay prefers unwatched then least recently watched approved videos`() {
        var p = KidsPlayback.recordPlaybackInterval(profile, a, 0, 90, 100, 20)
        assertEquals(b, KidsPlayback.nextAutoplay(p, listOf(c, a, b)))
        p = KidsPlayback.recordPlaybackInterval(p, b, 0, 90, 100, 10)
        assertEquals(b, KidsPlayback.nextAutoplay(p, listOf(a, b)))
        assertEquals(a, KidsPlayback.nextAutoplay(p, listOf(a, b), setOf(b)))
        assertNull(KidsPlayback.nextAutoplay(p.copy(autoplay = false), listOf(a, b)))
    }

    @Test fun `playlist next honors order and skips unapproved and unavailable videos`() {
        assertEquals(b, KidsPlayback.nextInPlaylist(profile, listOf(a, c, b), 0))
        assertNull(KidsPlayback.nextInPlaylist(profile, listOf(a, c, b), 0, setOf(b)))
        assertEquals(b, KidsPlayback.nextInPlaylist(profile.copy(autoplay = false), listOf(a, b), 0))
    }

    @Test fun `manual next works with autoplay off and general autoplay leaves the playlist`() {
        val playlist = StoredSource(SourceKind.PLAYLIST, StoredContentId(0, "playlist"), videos = listOf(a, b))
        val extra = profile.copy(approvedVideos = setOf(a, b, c))
        assertEquals(NextPlayback(b, playlist.id), KidsPlayback.nextAfter(extra, playlist, a, listOf(c), emptySet(), manual = false))
        assertEquals(NextPlayback(c, null), KidsPlayback.nextAfter(extra, playlist, b, listOf(c), emptySet(), manual = false))
        assertEquals(NextPlayback(b, playlist.id), KidsPlayback.nextAfter(extra.copy(autoplay = false), playlist, a, listOf(c), emptySet(), manual = true))
        assertNull(KidsPlayback.nextAfter(extra.copy(autoplay = false), playlist, a, listOf(c), emptySet(), manual = false))
        assertNull(KidsPlayback.nextAfter(extra, playlist, b, listOf(c), emptySet(), manual = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cannot record watch progress for unapproved content`() {
        KidsPlayback.recordPlaybackInterval(profile, c, 0, 90, 100, 10)
    }

    @Test fun `seeked gaps and repeated playback do not count as watched coverage`() {
        var p = KidsPlayback.recordPlaybackInterval(profile, a, 0, 20, 100, 1)
        p = KidsPlayback.recordPlaybackInterval(p, a, 80, 100, 100, 2)
        p = KidsPlayback.recordPlaybackInterval(p, a, 0, 20, 100, 3)
        assertFalse(p.history.single().watched)
        assertEquals(2, p.history.single().playedRanges.size)
        p = KidsPlayback.recordPlaybackInterval(p, a, 20, 70, 100, 4)
        assertTrue(p.history.single().watched)
    }
}
