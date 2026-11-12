/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildLibraryTest {
    private val a = VideoIdentity.youtube("e_04ZrNroTo")
    private val b = VideoIdentity.youtube("dQw4w9WgXcQ")
    private val c = VideoIdentity.youtube("abcdefghijk")
    private fun item(id: StoredContentId, name: String) = ParentSearchItem(
        ParentContentKind.VIDEO,
        id,
        name,
        "https://www.youtube.com/watch?v=${id.id}"
    )

    @Test fun `search never returns videos outside the active profiles approved library`() {
        val first = ChildProfile("a", "A", sessionLimitMinutes = 5, remainingMillis = 1, approvedVideos = setOf(a, b), hiddenVideos = setOf(b))
        val second = ChildProfile("b", "B", sessionLimitMinutes = 5, remainingMillis = 1, approvedVideos = setOf(c))
        val state = KidsState(profiles = listOf(first, second), catalog = listOf(item(a, "Bus song"), item(b, "Bus hidden"), item(c, "Bus other")))
        assertEquals(listOf(a), ChildLibrary.videos(state, "a", "BUS").map { it.id })
        assertEquals(listOf(c), ChildLibrary.videos(state, "b", "bus").map { it.id })
        assertTrue(ChildLibrary.videos(state, "a", "other").isEmpty())
    }

    @Test fun `disabled missing or expired profiles expose no catalog`() {
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, approvedVideos = setOf(a))
        val state = KidsState(profiles = listOf(profile), catalog = listOf(item(a, "Song")))
        assertTrue(ChildLibrary.videos(state, "missing").isEmpty())
        assertTrue(ChildLibrary.videos(state.copy(profiles = listOf(profile.copy(enabled = false))), "a").isEmpty())
        assertTrue(ChildLibrary.videos(state.copy(profiles = listOf(profile.copy(remainingMillis = 0))), "a").isEmpty())
    }

    @Test fun `cached channel videos are visible only while their source is approved`() {
        val source = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "channel"), videos = listOf(a))
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, sources = listOf(source))
        val state = KidsState(profiles = listOf(profile), catalog = listOf(item(a, "Song")))
        assertEquals(listOf(a), ChildLibrary.videos(state, "a").map { it.id })
        assertTrue(ChildLibrary.videos(state.copy(profiles = listOf(profile.copy(sources = emptyList()))), "a").isEmpty())
    }
}
