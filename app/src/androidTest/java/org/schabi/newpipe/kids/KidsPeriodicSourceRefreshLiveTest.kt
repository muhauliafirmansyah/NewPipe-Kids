/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.app.Activity
import android.os.SystemClock
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList

/** Opt in with live=true, periodic=true, and periodicMode=child or parent. Runs for 15 minutes. */
@RunWith(AndroidJUnit4::class)
class KidsPeriodicSourceRefreshLiveTest {
    @Test fun childModeRefreshesWhileItRemainsOpen() {
        verifyPeriodicRefresh("child", KidsChildActivity::class.java)
    }

    @Test fun parentModeRefreshesWhileItRemainsOpen() {
        verifyPeriodicRefresh("parent", KidsParentActivity::class.java)
    }

    private fun <T : Activity> verifyPeriodicRefresh(mode: String, activityClass: Class<T>) {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("live") == "true")
        assumeTrue(arguments.getString("periodic") == "true")
        assumeTrue(arguments.getString("periodicMode") == mode)
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        // Never overwrite a real configured profile when tests are run on a user's device.
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 120)
            val id = SourceIdentity.fromUrl(
                ServiceList.YouTube.serviceId,
                SourceKind.PLAYLIST,
                "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8"
            )
            val oldVideo = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.PLAYLIST, id, "Test playlist", id.id))
            fun replaceWithOldSnapshot() {
                storage.update { state ->
                    val started = ApprovalManager.beginSourceRefresh(state, setOf("child"), SourceKind.PLAYLIST, id)
                    ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(oldVideo)).copy(selectedProfileId = "child")
                }
            }
            fun refreshed(): Boolean {
                val source = storage.read().profiles.single().sources.single()
                return source.activeRefreshToken == null && source.videos.isNotEmpty() && source.videos != listOf(oldVideo)
            }
            replaceWithOldSnapshot()
            ActivityScenario.launch(activityClass).use { scenario ->
                scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                val firstDeadline = SystemClock.elapsedRealtime() + 40_000
                while (!refreshed() && SystemClock.elapsedRealtime() < firstDeadline) Thread.sleep(100)
                assertTrue("The approved playlist did not refresh when $mode mode opened", refreshed())
                // Let the initial worker schedule its next refresh before making the snapshot stale again.
                Thread.sleep(2_000)
                replaceWithOldSnapshot()
                val started = SystemClock.elapsedRealtime()
                val deadline = started + 16 * 60_000L + 30_000L
                var updated = false
                while (!updated && SystemClock.elapsedRealtime() < deadline) {
                    assertTrue("$mode mode did not remain resumed during the test", scenario.state == Lifecycle.State.RESUMED)
                    if (refreshed()) {
                        assertTrue("The snapshot refreshed too early to demonstrate the periodic job", SystemClock.elapsedRealtime() - started >= 14 * 60_000L)
                        updated = true
                    } else {
                        Thread.sleep(5_000)
                    }
                }
                assertTrue("The approved playlist did not refresh again while $mode mode remained open", updated)
            }
        } finally {
            storage.update { original }
        }
    }
}
