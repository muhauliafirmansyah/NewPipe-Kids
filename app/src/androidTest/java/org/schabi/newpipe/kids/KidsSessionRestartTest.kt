/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run with phase=write, force-stop the app, then run with phase=read. */
@RunWith(AndroidJUnit4::class)
class KidsSessionRestartTest {
    @Test fun childSessionSurvivesAProcessRestartWithoutCountingBackgroundTime() {
        val arguments = InstrumentationRegistry.getArguments()
        val phase = arguments.getString("phase")
        assumeTrue(phase == "write" || phase == "read")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = KidsStorage.get(context)
        val backup = context.getSharedPreferences("kids_session_restart_test", Context.MODE_PRIVATE)
        if (phase == "write") {
            val original = storage.read()
            assumeTrue(original.parentPin == null && original.profiles.isEmpty())
            assertTrue(backup.edit().putString("original", KidsStateCodec.encode(original)).commit())
            ParentSettings(storage).setup("1234", "restart-child", "Test child", 2)
            storage.update { it.copy(selectedProfileId = "restart-child") }
            ActivityScenario.launch(KidsChildActivity::class.java).use {
                waitFor { storage.read().profiles.single().remainingMillis < 120_000 }
            }
            Thread.sleep(500)
            val remaining = storage.read().profiles.single().remainingMillis
            assertTrue(backup.edit().putLong("remaining", remaining).commit())
        } else {
            val original = KidsStateCodec.decode(requireNotNull(backup.getString("original", null)))
            try {
                val remaining = backup.getLong("remaining", -1)
                assertTrue(remaining in 1 until 120_000)
                assertEquals(remaining, storage.read().profiles.single().remainingMillis)
                ActivityScenario.launch(KidsChildActivity::class.java).use {
                    waitFor { storage.read().profiles.single().remainingMillis < remaining }
                }
                Thread.sleep(500)
            } finally {
                storage.update { original }
                backup.edit().clear().commit()
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for the session timer", condition())
    }
}
