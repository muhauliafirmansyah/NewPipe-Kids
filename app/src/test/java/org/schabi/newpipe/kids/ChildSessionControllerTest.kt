/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ChildSessionControllerTest {
    private fun storage(initial: KidsState): KidsStorage {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        var saved: String? = KidsStateCodec.encode(initial)
        var pending: String? = null
        `when`(preferences.getString(anyString(), org.mockito.ArgumentMatchers.isNull())).thenAnswer { saved }
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenAnswer {
            pending = it.getArgument(1)
            editor
        }
        `when`(editor.commit()).thenAnswer {
            saved = pending
            true
        }
        return KidsStorage(preferences)
    }

    @Test fun `only foreground time counts and switching profiles preserves each session`() {
        var state = ProfileManager.add(KidsState(), "a", "A", 1)
        state = ProfileManager.add(state, "b", "B", 2).copy(selectedProfileId = "a")
        val storage = storage(state)
        var now = 1_000L
        val sessions = ChildSessionController(storage) { now }
        sessions.resume("a")
        now += 10_000
        assertEquals(50_000L, sessions.pause())
        now += 100_000 // time spent outside the app
        storage.update { it.copy(selectedProfileId = "b") }
        sessions.resume("b")
        now += 5_000
        assertEquals(115_000L, sessions.pause())
        storage.update { it.copy(selectedProfileId = "a") }
        assertEquals(50_000L, sessions.resume("a"))
        assertEquals(50_000L, sessions.pause())
    }

    @Test fun `parent reset is not overwritten by an older in-memory timer`() {
        val initial = ProfileManager.add(KidsState(), "a", "A", 1).copy(selectedProfileId = "a")
        val storage = storage(initial)
        var now = 1_000L
        val sessions = ChildSessionController(storage) { now }
        sessions.resume("a")
        now += 20_000
        assertEquals(40_000L, sessions.checkpoint())
        storage.update { ProfileManager.resetSession(it, "a") }
        now += 1_000
        assertEquals(60_000L, sessions.checkpoint())
        now += 2_000
        assertEquals(58_000L, sessions.pause())
    }

    @Test(expected = IllegalStateException::class)
    fun `a disabled profile cannot keep running a session`() {
        val initial = ProfileManager.add(KidsState(), "a", "A", 1).copy(selectedProfileId = "a")
        val storage = storage(initial)
        val sessions = ChildSessionController(storage) { 1_000L }
        sessions.resume("a")
        storage.update { ProfileManager.setEnabled(it, "a", false) }
        sessions.checkpoint()
    }
}
