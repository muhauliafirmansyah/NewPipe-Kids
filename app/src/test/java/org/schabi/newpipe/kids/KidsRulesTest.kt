/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsRulesTest {
    private val video = ContentId(0, "e_04ZrNroTo")
    private val channel = ContentSource(SourceKind.CHANNEL, ContentId(0, "channel"))
    private val playlist = ContentSource(SourceKind.PLAYLIST, ContentId(0, "playlist"))

    @Test fun `access requires an approval and hidden videos override all approvals`() {
        assertFalse(ApprovedLibrary().canPlay(video))
        val library = ApprovedLibrary(videos = setOf(video), hiddenVideos = setOf(video))
        assertFalse(library.canPlay(video))
        assertTrue(library.playableVideos().isEmpty())
    }

    @Test fun `overlapping sources keep videos approved until all coverage is removed`() {
        val library = ApprovedLibrary(
            sources = setOf(channel, playlist),
            sourceVideos = mapOf(channel to setOf(video), playlist to setOf(video))
        )
        assertTrue(library.canPlay(video))
        assertTrue(library.copy(sources = setOf(playlist)).canPlay(video))
        assertFalse(library.copy(sources = emptySet()).canPlay(video))
        assertFalse(library.copy(disabledSources = setOf(channel, playlist)).canPlay(video))
        assertFalse(library.copy(hiddenVideos = setOf(video)).canPlay(video))
    }

    @Test fun `source snapshot cannot approve another service or an unapproved source`() {
        val library = ApprovedLibrary(sourceVideos = mapOf(channel to setOf(video)))
        assertFalse(library.canPlay(video))
        assertFalse(library.copy(sources = setOf(channel)).canPlay(ContentId(1, "video")))
    }

    @Test fun `session counts active time only and cannot resume after expiry`() {
        val session = SessionTime.newSession(1).resume(1_000).pause(11_000)
        assertEquals(50_000L, session.remainingMillis)
        val ended = session.resume(100_000).update(150_000)
        assertTrue(ended.exhausted)
        assertTrue(ended.resume(200_000).update(300_000).exhausted)
        assertEquals(50_000L, SessionTime.restore(session.remainingMillis).remainingMillis)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects session limit outside allowed range`() {
        SessionTime.newSession(121)
    }
}
