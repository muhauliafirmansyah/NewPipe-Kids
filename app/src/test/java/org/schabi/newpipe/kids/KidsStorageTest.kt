/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsStorageTest {
    @Test fun `state round trip keeps profile isolation and source snapshots`() {
        val video = StoredContentId(0, "e_04ZrNroTo")
        val source = StoredSource(SourceKind.PLAYLIST, StoredContentId(0, "list"), videos = listOf(video))
        val profile = ChildProfile(
            "one",
            "Alex",
            sessionLimitMinutes = 30,
            remainingMillis = 25_000,
            sources = listOf(source),
            hiddenVideos = setOf(video)
        )
        val other = ChildProfile(
            "two",
            "Sam",
            sessionLimitMinutes = 1,
            remainingMillis = 60_000,
            approvedVideos = setOf(video)
        )
        val state = KidsState(profiles = listOf(profile, other), lockProfileSwitching = true)
        val restored = KidsStateCodec.decode(KidsStateCodec.encode(state))
        assertEquals(state, restored)
        assertFalse(restored.profiles[0].approvedLibrary().canPlay(video.toContentId()))
        assertTrue(restored.profiles[1].approvedLibrary().canPlay(video.toContentId()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported version is rejected`() {
        KidsStateCodec.decode("""{"version":2,"profiles":[],"lockProfileSwitching":false}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate profile ids are rejected`() {
        val profile = ChildProfile("same", "Alex", sessionLimitMinutes = 1, remainingMillis = 0)
        KidsState(profiles = listOf(profile, profile))
    }
}
