/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ParentSettingsTest {
    private fun storage(): KidsStorage {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        var saved: String? = null
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

    @Test fun `setup and authorized changes persist while bad PIN leaves state intact`() {
        val store = storage()
        val parent = ParentSettings(store)
        parent.setup("1234", "a", "Alex", 10)
        val video = StoredContentId(0, "video")
        parent.approveVideo("1234", setOf("a"), video)
        val before = store.read()
        try {
            parent.setVideoHidden("9999", setOf("a"), video, true)
            throw AssertionError("Incorrect PIN should fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(before, store.read())
        }
        assertTrue(store.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
        parent.setVideoHidden("1234", setOf("a"), video, true)
        assertFalse(store.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
        parent.changePin("1234", "5678")
        assertFalse(ParentPin.verify(store.read(), "1234"))
        assertTrue(ParentPin.verify(store.read(), "5678"))
    }

    @Test fun `failed setup does not save PIN or a partial profile`() {
        val store = storage()
        try {
            ParentSettings(store).setup("1234", "a", " ", 10)
            throw AssertionError("Invalid profile should fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(KidsState(), store.read())
        }
    }
}
