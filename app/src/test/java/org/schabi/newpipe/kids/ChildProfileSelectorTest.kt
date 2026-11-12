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

class ChildProfileSelectorTest {
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

    @Test fun `locked switching requires parent authorization and preserves sessions`() {
        var state = ProfileManager.add(KidsState(), "a", "A", 5)
        state = ProfileManager.add(state, "b", "B", 10)
        state = ParentPin.setup(state, "1234").copy(lockProfileSwitching = true)
        val storage = storage(state)
        val selector = ChildProfileSelector(storage)
        selector.select("a")
        try {
            selector.select("b")
            throw AssertionError("Locked switch should fail")
        } catch (_: IllegalArgumentException) {
            assertEquals("a", storage.read().selectedProfileId)
        }
        selector.select("b", "1234")
        val result = storage.read()
        assertEquals("b", result.selectedProfileId)
        assertEquals(300_000L, result.profiles[0].remainingMillis)
        assertEquals(600_000L, result.profiles[1].remainingMillis)
        assertEquals(null, ProfileManager.remove(result, "b").selectedProfileId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `disabled profile cannot be selected`() {
        val state = ProfileManager.setEnabled(ProfileManager.add(KidsState(), "a", "A", 1), "a", false)
        ChildProfileSelector(storage(state)).select("a")
    }
}
