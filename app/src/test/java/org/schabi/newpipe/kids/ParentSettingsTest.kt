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
        val video = StoredContentId(0, "e_04ZrNroTo")
        parent.approveVideo("1234", setOf("a"), video)
        val before = store.read()
        try {
            parent.setVideoHidden("9999", setOf("a"), video, true)
            throw AssertionError("Incorrect PIN should fail")
        } catch (_: IllegalArgumentException) {
            val after = store.read()
            assertEquals(before.profiles, after.profiles)
            assertEquals(before.parentPin, after.parentPin)
            assertEquals(1, after.failedPinAttempts)
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

    @Test fun `repeated wrong PINs lock parent actions until timeout`() {
        val store = storage()
        var now = 100_000L
        val parent = ParentSettings(store) { now }
        parent.setup("1234", "a", "Alex", 10)
        repeat(5) {
            try {
                parent.renameProfile("9999", "a", "Wrong")
                throw AssertionError("Wrong PIN should fail")
            } catch (_: IllegalArgumentException) {
                assertEquals("Alex", store.read().profiles.single().name)
            }
        }
        assertEquals(130_000L, store.read().pinLockedUntilMillis)
        try {
            parent.renameProfile("1234", "a", "Pat")
            throw AssertionError("Correct PIN should remain locked until timeout")
        } catch (_: IllegalArgumentException) {
            assertEquals("Alex", store.read().profiles.single().name)
        }
        now = 130_000L
        parent.renameProfile("1234", "a", "Pat")
        assertEquals("Pat", store.read().profiles.single().name)
        assertEquals(0, store.read().failedPinAttempts)
        assertEquals(0L, store.read().pinLockedUntilMillis)
    }

    @Test fun `parent search approval saves display metadata without broadening profiles`() {
        val store = storage()
        val parent = ParentSettings(store)
        parent.setup("1234", "a", "Alex", 10)
        parent.addProfile("1234", "b", "Sam", 10)
        val video = VideoIdentity.youtube("e_04ZrNroTo")
        val item = ParentSearchItem(
            ParentContentKind.VIDEO,
            video,
            "A song",
            "https://youtu.be/e_04ZrNroTo"
        )
        val saved = parent.approveSearchItem("1234", setOf("a"), item)
        assertEquals(item, saved.catalog.single())
        assertTrue(saved.profiles.first().approvedLibrary().canPlay(video.toContentId()))
        assertFalse(saved.profiles.last().approvedLibrary().canPlay(video.toContentId()))
        assertEquals(saved, KidsStateCodec.decode(KidsStateCodec.encode(saved)))
        val forged = item.copy(id = VideoIdentity.youtube("dQw4w9WgXcQ"))
        try {
            parent.approveSearchItem("1234", setOf("a"), forged)
            throw AssertionError("Mismatched video ID should fail")
        } catch (_: IllegalArgumentException) {
            assertEquals(saved, store.read())
        }
    }
}
