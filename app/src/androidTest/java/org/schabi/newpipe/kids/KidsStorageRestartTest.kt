/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run with phase=write, force-stop the target process, then run with phase=read. */
@RunWith(AndroidJUnit4::class)
class KidsStorageRestartTest {
    @Test fun storageSurvivesProcessRestart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val storage = KidsStorage.database(context, "kids_storage_restart_test.db")
        val phase = InstrumentationRegistry.getArguments().getString("phase")
        val video = VideoIdentity.youtube("e_04ZrNroTo")
        val expected = ChildProfile(
            "restart-test",
            "Test",
            sessionLimitMinutes = 5,
            remainingMillis = 123_000,
            approvedVideos = setOf(video),
            favorites = setOf(video),
            history = listOf(StoredWatch(video, 1_000, false, 100, listOf(WatchedRange(0, 50))))
        )
        if (phase == "write") {
            storage.update { ParentPin.setup(KidsState(profiles = listOf(expected)), "1234") }
        } else if (phase == "read") {
            val actual = storage.read()
            assertEquals(listOf(expected), actual.profiles)
            assertTrue(ParentPin.verify(actual, "1234"))
            storage.update { KidsState() }
        } else {
            // Ordinary test runs exercise write/read without altering the real app preferences.
            storage.update { ParentPin.setup(KidsState(profiles = listOf(expected)), "1234") }
            assertEquals(listOf(expected), storage.read().profiles)
            storage.update { KidsState() }
        }
    }
}
