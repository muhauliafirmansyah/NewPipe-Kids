/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    @Test fun backgroundingPausesPlaybackAndRestoresThePlayerAndSession() {
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
                var beforePause = 0L
                scenario.onActivity {
                    player = it.findViewById<PlayerView>(R.id.kids_player_view).player
                    beforePause = requireNotNull(player).currentPosition
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                Thread.sleep(500)
                var playing = true
                instrumentation.runOnMainSync { playing = player?.isPlaying == true }
                assertFalse(playing)
                val paused = storage.read().profiles.single().remainingMillis
                Thread.sleep(1_200)
                assertTrue(storage.read().profiles.single().remainingMillis == paused)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario, 20_000) { activity ->
                    activity.findViewById<PlayerView>(R.id.kids_player_view)?.player?.let {
                        it.isPlaying && it.currentPosition >= beforePause
                    } == true
                }
                scenario.onActivity { assertSame(player, it.findViewById<PlayerView>(R.id.kids_player_view).player) }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun pausedPlayerStaysPausedAfterBackgroundAndRecreation() {
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
                var position = 0L
                scenario.onActivity {
                    val player = requireNotNull(it.findViewById<PlayerView>(R.id.kids_player_view).player)
                    player.pause()
                    position = player.currentPosition
                }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario) { it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.playWhenReady == false }
                scenario.recreate()
                waitFor(scenario, 40_000) { activity ->
                    activity.findViewById<PlayerView>(R.id.kids_player_view)?.player?.let {
                        !it.playWhenReady && it.currentPosition >= position - 500
                    } == true
                }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun approvalIsRecheckedBeforeReturningToPlayback() {
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
                scenario.moveToState(Lifecycle.State.CREATED)
                parent.setVideoHidden("1234", setOf("child"), id, true)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario) { activity ->
                    activity.findViewById<PlayerView>(R.id.kids_player_view) == null &&
                        findButton(activity.window.decorView, "Approved song") == null
                }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun loadingVideoContinuesAfterLeavingAndReturning() {
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
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000 }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun fullscreenButtonAndRotationKeepTheSamePlayer() {
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
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                waitFor(scenario) { findButton(it.window.decorView, "Approved song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Approved song")).performClick() }
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000 }
                var player: Player? = null
                var beforeLandscape = 0L
                capture("portrait")
                scenario.onActivity { activity ->
                    player = activity.findViewById<PlayerView>(R.id.kids_player_view).player
                    activity.findViewById<ImageButton>(R.id.kids_player_fullscreen).performClick()
                    assertTrue(activity.findViewById<ImageButton>(R.id.kids_player_fullscreen).contentDescription == activity.getString(R.string.kids_exit_fullscreen))
                    assertFalse(activity.findViewById<View>(R.id.kids_player_back).isShown)
                }
                capture("fullscreen-portrait")
                scenario.onActivity { activity ->
                    activity.findViewById<ImageButton>(R.id.kids_player_fullscreen).performClick()
                    assertTrue(activity.findViewById<View>(R.id.kids_player_back).isShown)
                    beforeLandscape = requireNotNull(player).currentPosition
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                }
                waitFor(scenario, 20_000) { activity ->
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        activity.findViewById<ImageButton>(R.id.kids_player_fullscreen)?.contentDescription == activity.getString(R.string.kids_exit_fullscreen) &&
                        activity.findViewById<PlayerView>(R.id.kids_player_view)?.player?.let { it.isPlaying && it.currentPosition >= beforeLandscape + 500 } == true
                }
                capture("fullscreen-landscape")
                scenario.onActivity { activity ->
                    val view = activity.findViewById<PlayerView>(R.id.kids_player_view)
                    assertSame(player, view.player)
                    assertTrue(view.width == activity.window.decorView.width && view.height == activity.window.decorView.height)
                    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
                waitFor(scenario, 20_000) { activity ->
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT &&
                        activity.findViewById<ImageButton>(R.id.kids_player_fullscreen)?.contentDescription == activity.getString(R.string.kids_enter_fullscreen)
                }
                scenario.onActivity { assertSame(player, it.findViewById<PlayerView>(R.id.kids_player_view).player) }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun fullscreenShowsSessionWarningAndStopsAtTheLimit() {
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
                scenario.onActivity { it.findViewById<ImageButton>(R.id.kids_player_fullscreen).performClick() }
                storage.update { state -> state.copy(profiles = state.profiles.map { it.copy(remainingMillis = 5_000) }) }
                waitFor(scenario) { it.findViewById<View>(R.id.kids_fullscreen_warning)?.isShown == true }
                waitFor(scenario, 15_000) { it.findViewById<View>(R.id.kids_session_ended) != null }
                scenario.onActivity { assertTrue(it.findViewById<PlayerView>(R.id.kids_player_view) == null) }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun playerSuggestionsRespectApprovalsAndPlaylistContext() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            parent.addProfile("1234", "other", "Other child", 2)
            fun video(id: String, title: String) = ParentSearchItem(
                ParentContentKind.VIDEO,
                VideoIdentity.youtube(id),
                title,
                "https://youtu.be/$id",
                "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                100,
                "Test channel"
            )
            val first = video("e_04ZrNroTo", "First song")
            val second = video("jNQXAC9IVRw", "Second song")
            val third = video("aqz-KE-bpKQ", "Third song")
            val outside = video("5qap5aO4i9A", "Outside playlist")
            val hidden = video("dQw4w9WgXcQ", "Hidden song")
            val short = video("9bZkp7q19f0", "Hidden short").copy(isShortForm = true)
            val other = video("M7lc1UVf-VE", "Other child's song")
            listOf(first, second, third, outside, hidden, short).forEach { parent.approveSearchItem("1234", setOf("child"), it) }
            parent.approveSearchItem("1234", setOf("other"), other)
            parent.setVideoHidden("1234", setOf("child"), hidden.id, true)
            parent.setShowShorts("1234", "child", false)
            val playlist = StoredContentId(0, "player-suggestions-playlist")
            storage.update { state ->
                val approved = ApprovalManager.approveSource(state, setOf("child"), SourceKind.PLAYLIST, playlist)
                val refresh = ApprovalManager.beginSourceRefresh(approved, setOf("child"), SourceKind.PLAYLIST, playlist)
                ApprovalManager.completeSourceRefresh(refresh.state, refresh.ticket, listOf(first.id, second.id, third.id)).copy(
                    selectedProfileId = "child",
                    catalog = state.catalog + ParentSearchItem(ParentContentKind.PLAYLIST, playlist, "Test playlist", "https://example.org/playlist")
                )
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Test playlist") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test playlist")).performClick() }
                scenario.onActivity { requireNotNull(it.window.decorView.findViewWithTag<View>("kids-thumbnail-${first.id.id}")).performClick() }
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000 }
                scenario.onActivity { activity ->
                    val suggestions = activity.findViewById<ViewGroup>(R.id.kids_player_suggestions)
                    assertTrue(countTag(suggestions, "kids-player-suggestion-${first.id.id}") == 1)
                    assertTrue(countTag(suggestions, "kids-player-suggestion-${second.id.id}") == 1)
                    assertTrue(countTag(suggestions, "kids-player-suggestion-${third.id.id}") == 1)
                    assertTrue(suggestions.findViewWithTag<View>("kids-player-suggestion-${hidden.id.id}") == null)
                    assertTrue(suggestions.findViewWithTag<View>("kids-player-suggestion-${short.id.id}") == null)
                    assertTrue(suggestions.findViewWithTag<View>("kids-player-suggestion-${other.id.id}") == null)
                    assertTrue(suggestions.findViewWithTag<View>("kids-player-suggestion-${outside.id.id}") == null)
                }
                scenario.onActivity { it.findViewById<ImageButton>(R.id.kids_player_options).performClick() }
                tapOption("Autoplay: On")
                waitFor(scenario) { !storage.read().profiles.first { it.id == "child" }.autoplay }
                scenario.onActivity { it.findViewById<ImageButton>(R.id.kids_player_options).performClick() }
                tapOption("Autoplay: Off")
                waitFor(scenario) { storage.read().profiles.first { it.id == "child" }.autoplay }
                capture("suggestions-portrait")
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                waitFor(scenario, 20_000) { it.findViewById<HorizontalScrollView>(R.id.kids_player_suggestions_fullscreen)?.isShown == true }
                scenario.onActivity { activity ->
                    val row = activity.findViewById<HorizontalScrollView>(R.id.kids_player_suggestions_fullscreen)
                    assertTrue(row.findViewWithTag<View>("kids-player-suggestion-${second.id.id}") != null)
                    assertTrue(row.findViewWithTag<View>("kids-player-suggestion-${hidden.id.id}") == null)
                    assertTrue(row.findViewWithTag<View>("kids-player-suggestion-${short.id.id}") == null)
                }
                capture("suggestions-landscape")
                scenario.onActivity { it.findViewById<PlayerView>(R.id.kids_player_view).hideController() }
                waitFor(scenario) { activity ->
                    val player = activity.findViewById<PlayerView>(R.id.kids_player_view)
                    activity.findViewById<HorizontalScrollView>(R.id.kids_player_suggestions_fullscreen)?.isShown == false &&
                        player.height == activity.window.decorView.height
                }
                scenario.onActivity { it.findViewById<PlayerView>(R.id.kids_player_view).showController() }
                waitFor(scenario) { it.findViewById<HorizontalScrollView>(R.id.kids_player_suggestions_fullscreen)?.isShown == true }
                parent.setVideoHidden("1234", setOf("child"), third.id, true)
                waitFor(scenario) { activity ->
                    activity.findViewById<HorizontalScrollView>(R.id.kids_player_suggestions_fullscreen)
                        ?.findViewWithTag<View>("kids-player-suggestion-${third.id.id}") == null
                }
                scenario.onActivity { activity ->
                    val row = activity.findViewById<HorizontalScrollView>(R.id.kids_player_suggestions_fullscreen)
                    requireNotNull(row.findViewWithTag<View>("kids-player-suggestion-${second.id.id}")).performClick()
                }
                waitFor(scenario, 40_000) { activity ->
                    activity.findViewById<TextView>(R.id.kids_player_title)?.text == "Second song" &&
                        (activity.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000
                }
                scenario.onActivity { activity -> assertTrue(activity.findViewById<ImageButton>(R.id.kids_player_next).visibility == View.VISIBLE) }
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

    private fun countTag(view: View, tag: String): Int {
        val current = if (view.tag == tag) 1 else 0
        return current + if (view is ViewGroup) (0 until view.childCount).sumOf { countTag(view.getChildAt(it), tag) } else 0
    }

    private fun tapOption(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        var clicked = false
        while (!clicked && System.currentTimeMillis() < deadline) {
            clicked = TestAppWindow.clickText(text)
            if (!clicked) Thread.sleep(50)
        }
        assertTrue("Could not select $text in the player options", clicked)
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
        Thread.sleep(500)
        val deadline = System.currentTimeMillis() + 5_000
        while (TestAppWindow.activeRoot() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        // Screenshots are optional; never capture another app if the emulator changes focus.
        if (TestAppWindow.activeRoot() == null) return
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "kids-player-$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun waitFor(scenario: ActivityScenario<KidsChildActivity>, timeout: Long = 10_000, condition: (KidsChildActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        var satisfied = false
        while (!satisfied && System.currentTimeMillis() < deadline) {
            scenario.onActivity { activity ->
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                (activity.findViewById<PlayerView>(R.id.kids_player_view)?.player as? ExoPlayer)?.volume = 0f
                satisfied = condition(activity)
            }
            if (!satisfied) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for child playback", satisfied)
    }
}
