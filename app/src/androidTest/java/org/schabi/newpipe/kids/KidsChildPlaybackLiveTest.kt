/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

/** Opt in with -e live true; exercises extraction, playback, and approval revocation. */
@RunWith(AndroidJUnit4::class)
class KidsChildPlaybackLiveTest {
    @Test fun approvedSharedLinkPlaysForTheSelectedProfile() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = KidsStorage.get(context)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val id = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.VIDEO, id, "Approved song", "https://youtu.be/e_04ZrNroTo"))
            storage.update { it.copy(selectedProfileId = "child") }
            val intent = Intent(Intent.ACTION_SEND).setType("text/plain").setClass(context, KidsChildActivity::class.java)
                .putExtra(Intent.EXTRA_TEXT, "Watch https://youtu.be/e_04ZrNroTo")
            ActivityScenario.launch<KidsChildActivity>(intent).use { scenario ->
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000 }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun backgroundingStopsPlaybackAndPausesTheSession() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storage = KidsStorage.get(instrumentation.targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val id = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.VIDEO, id, "Approved song", "https://youtu.be/e_04ZrNroTo"))
            storage.update { it.copy(selectedProfileId = "child") }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Approved song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Approved song")).performClick() }
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000 }
                var player: Player? = null
                scenario.onActivity { player = it.findViewById<PlayerView>(R.id.kids_player_view).player }
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(500)
                var playing = true
                instrumentation.runOnMainSync { playing = player?.isPlaying == true }
                assertFalse(playing)
                val paused = storage.read().profiles.single().remainingMillis
                Thread.sleep(1_200)
                assertTrue(storage.read().profiles.single().remainingMillis == paused)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario) { it.findViewById<View>(R.id.kids_player_view) == null && findButton(it.window.decorView, "Approved song") != null }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun playingVideoStopsWhenParentHidesIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val id = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.VIDEO, id, "Approved song", "https://youtu.be/e_04ZrNroTo"))
            storage.update { it.copy(selectedProfileId = "child") }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Approved song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Approved song")).performClick() }
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 2_000 }
                waitFor(scenario) { storage.read().profiles.single().history.singleOrNull()?.playedRanges?.isNotEmpty() == true }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_add_favorite))).performClick() }
                waitFor(scenario) { id in storage.read().profiles.single().favorites }
                parent.setVideoHidden("1234", setOf("child"), id, true)
                waitFor(scenario) { it.findViewById<View>(R.id.kids_player_view) == null }
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, "Approved song") == null)
                }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun playingVideoStopsWhenSessionExpires() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val id = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.VIDEO, id, "Approved song", "https://youtu.be/e_04ZrNroTo"))
            storage.update { it.copy(selectedProfileId = "child") }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Approved song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Approved song")).performClick() }
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000 }
                storage.update { state -> state.copy(profiles = state.profiles.map { it.copy(remainingMillis = 0) }) }
                waitFor(scenario) { it.findViewById<View>(R.id.kids_session_ended) != null }
                scenario.onActivity { assertTrue(it.findViewById<View>(R.id.kids_player_view) == null) }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun playingVideoContinuesUntilItsLastApprovalIsRemoved() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val id = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.VIDEO, id, "Approved song", "https://youtu.be/e_04ZrNroTo"))
            val playlist = StoredContentId(0, "local-test-playlist")
            storage.update { state ->
                val approved = ApprovalManager.approveSource(state, setOf("child"), SourceKind.PLAYLIST, playlist)
                val started = ApprovalManager.beginSourceRefresh(approved, setOf("child"), SourceKind.PLAYLIST, playlist)
                ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(id)).copy(selectedProfileId = "child")
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Approved song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Approved song")).performClick() }
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 2_000 }
                parent.removeVideoApproval("1234", setOf("child"), id)
                waitFor(scenario, 20_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 4_000 }
                parent.removeSource("1234", setOf("child"), SourceKind.PLAYLIST, playlist)
                waitFor(scenario) { it.findViewById<View>(R.id.kids_player_view) == null }
                assertFalse(storage.read().profiles.single().approvedLibrary().canPlay(id.toContentId()))
            }
        } finally {
            storage.update { original }
        }
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButton(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun waitFor(scenario: ActivityScenario<KidsChildActivity>, timeout: Long = 10_000, condition: (KidsChildActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        var satisfied = false
        while (!satisfied && System.currentTimeMillis() < deadline) {
            scenario.onActivity { satisfied = condition(it) }
            if (!satisfied) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for child playback", satisfied)
    }
}
