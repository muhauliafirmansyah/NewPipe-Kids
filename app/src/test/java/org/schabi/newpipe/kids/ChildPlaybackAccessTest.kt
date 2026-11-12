/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildPlaybackAccessTest {
    private val video = VideoIdentity.youtube("e_04ZrNroTo")
    private val other = VideoIdentity.youtube("dQw4w9WgXcQ")
    private val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 60_000, approvedVideos = setOf(video))
    private val state = KidsState(profiles = listOf(profile), selectedProfileId = "a")

    @Test fun `playback requires the selected enabled profile, remaining time, and approval`() {
        assertTrue(ChildPlaybackAccess.allowed(state, "a", video))
        assertFalse(ChildPlaybackAccess.allowed(state, "a", other))
        assertFalse(ChildPlaybackAccess.allowed(state.copy(selectedProfileId = null), "a", video))
        assertFalse(ChildPlaybackAccess.allowed(state.copy(profiles = listOf(profile.copy(enabled = false))), "a", video))
        assertFalse(ChildPlaybackAccess.allowed(state.copy(profiles = listOf(profile.copy(remainingMillis = 0))), "a", video))
        assertFalse(ChildPlaybackAccess.allowed(state.copy(profiles = listOf(profile.copy(hiddenVideos = setOf(video)))), "a", video))
    }
}
