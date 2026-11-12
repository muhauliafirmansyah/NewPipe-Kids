/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KidsStorageInstrumentedTest {
    @Test
    fun savesAndReadsProfileUsingAndroidPreferences() {
        // Use the test APK's own preferences, not the user's application data.
        val context = InstrumentationRegistry.getInstrumentation().context
        val storage = KidsStorage(
            context.getSharedPreferences("kids_storage_instrumentation", Context.MODE_PRIVATE)
        )
        val original = storage.read()
        try {
            val video = StoredContentId(0, "test-video")
            val profile = ChildProfile(
                id = "instrumentation-profile",
                name = "Test",
                sessionLimitMinutes = 5,
                remainingMillis = 120_000,
                approvedVideos = setOf(video)
            )
            val expected = KidsState(profiles = listOf(profile))
            storage.update { expected }
            assertEquals(expected, storage.read())
            assertTrue(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
            storage.update { state ->
                state.copy(profiles = state.profiles.map { it.copy(hiddenVideos = setOf(video)) })
            }
            assertFalse(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
        } finally {
            storage.update { original }
        }
    }
}
