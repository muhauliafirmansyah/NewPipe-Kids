/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileManagerTest {
    @Test fun `parents can manage profiles without affecting other profiles`() {
        var state = ProfileManager.add(KidsState(), "one", " Alex ", 30)
        state = ProfileManager.add(state, "two", "Sam", 10)
        state = ProfileManager.rename(state, "one", " Pat ")
        state = ProfileManager.setEnabled(state, "one", false)
        assertEquals(listOf("two"), ProfileManager.visibleProfiles(state).map { it.id })
        assertEquals("Pat", state.profiles.first().name)
        state = ProfileManager.setEnabled(state, "one", true)
        assertEquals(2, ProfileManager.visibleProfiles(state).size)
        state = ProfileManager.remove(state, "one")
        assertEquals(listOf("Sam"), state.profiles.map { it.name })
    }

    @Test fun `changing limit never resets session, while explicit reset does`() {
        var state = ProfileManager.add(KidsState(), "one", "Alex", 10)
        state = state.copy(profiles = listOf(state.profiles.single().copy(remainingMillis = 50_000)))
        state = ProfileManager.setSessionLimit(state, "one", 30)
        assertEquals(50_000L, state.profiles.single().remainingMillis)
        state = ProfileManager.resetSession(state, "one")
        assertEquals(1_800_000L, state.profiles.single().remainingMillis)
        state = ProfileManager.setSessionLimit(state, "one", 1)
        assertEquals(60_000L, state.profiles.single().remainingMillis)
        assertFalse(state.profiles.single().remainingMillis == 0L)
        assertTrue(KidsStateCodec.decode(KidsStateCodec.encode(state)) == state)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `duplicate profile cannot be added`() {
        val state = ProfileManager.add(KidsState(), "one", "Alex", 1)
        ProfileManager.add(state, "one", "Sam", 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unknown profile cannot be reset`() {
        ProfileManager.resetSession(KidsState(), "missing")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank profile name is rejected`() {
        ProfileManager.add(KidsState(), "one", "  ", 1)
    }
}
