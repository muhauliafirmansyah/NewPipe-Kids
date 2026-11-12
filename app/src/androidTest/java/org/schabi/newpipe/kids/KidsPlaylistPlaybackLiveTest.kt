/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

/** Opt in with -e live true; tests the actual player with an approved playlist. */
@RunWith(AndroidJUnit4::class)
class KidsPlaylistPlaybackLiveTest {
    @Test fun playlistAutoplayAndManualNextRespectTheSetting() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val videos = listOf(
                item("e_04ZrNroTo", "First song"),
                item("00000000000", "Unavailable video"),
                item("dQw4w9WgXcQ", "Second song"),
                item("jNQXAC9IVRw", "Third song")
            )
            videos.forEach { parent.approveSearchItem("1234", setOf("child"), it) }
            val playlist = StoredContentId(0, "playlist-for-player-test")
            storage.update { state ->
                val approved = ApprovalManager.approveSource(state, setOf("child"), SourceKind.PLAYLIST, playlist)
                val started = ApprovalManager.beginSourceRefresh(approved, setOf("child"), SourceKind.PLAYLIST, playlist)
                val refreshed = ApprovalManager.completeSourceRefresh(started.state, started.ticket, videos.map { it.id })
                refreshed.copy(
                    selectedProfileId = "child",
                    catalog = refreshed.catalog + ParentSearchItem(ParentContentKind.PLAYLIST, playlist, "Test playlist", "https://youtube.com/playlist?list=test")
                )
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Playlist: Test playlist") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Playlist: Test playlist")).performClick() }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "First song")).performClick() }
                waitForPlaying(scenario, "First song")
                seekToEnd(scenario)
                waitForPlaying(scenario, "Second song")
                scenario.onActivity { activity ->
                    val on = activity.getString(R.string.kids_autoplay_status, activity.getString(R.string.kids_on))
                    requireNotNull(findButton(activity.window.decorView, on)).performClick()
                }
                waitFor(scenario) { !storage.read().profiles.single().autoplay }
                seekToEnd(scenario)
                waitFor(scenario, 40_000) { it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.playbackState == Player.STATE_ENDED }
                Thread.sleep(1_200)
                scenario.onActivity { activity ->
                    assertTrue(hasText(activity.window.decorView, "Second song"))
                    requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.kids_next_video))).performClick()
                }
                waitForPlaying(scenario, "Third song")
                scenario.onActivity { activity ->
                    val off = activity.getString(R.string.kids_autoplay_status, activity.getString(R.string.kids_off))
                    requireNotNull(findButton(activity.window.decorView, off)).performClick()
                }
                waitFor(scenario) { storage.read().profiles.single().autoplay }
                seekToEnd(scenario)
                waitForPlaying(scenario, "First song")
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, activity.getString(R.string.kids_next_video)) == null)
                }
            }
        } finally {
            storage.update { original }
        }
    }

    private fun item(id: String, title: String) = ParentSearchItem(ParentContentKind.VIDEO, VideoIdentity.youtube(id), title, "https://youtu.be/$id")

    private fun seekToEnd(scenario: ActivityScenario<KidsChildActivity>) {
        scenario.onActivity { activity ->
            val player = requireNotNull(activity.findViewById<PlayerView>(R.id.kids_player_view)?.player)
            check(player.duration > 1_000)
            player.seekTo(player.duration - 500)
            player.play()
        }
    }

    private fun waitForPlaying(scenario: ActivityScenario<KidsChildActivity>, title: String) = waitFor(scenario, 40_000) { activity ->
        val player = activity.findViewById<PlayerView>(R.id.kids_player_view)?.player as? ExoPlayer
        val counters = player?.videoDecoderCounters
        counters?.ensureUpdated()
        hasText(activity.window.decorView, title) && (player?.currentPosition ?: 0) >= 1_000 && (counters?.renderedOutputBufferCount ?: 0) > 0
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findButton(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun hasText(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
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
        var detail = ""
        if (!satisfied) {
            scenario.onActivity { activity ->
                fun text(view: View): List<String> {
                    val own = if (view is TextView && view.text.isNotEmpty()) listOf(view.text.toString()) else emptyList()
                    return own + if (view is ViewGroup) (0 until view.childCount).flatMap { text(view.getChildAt(it)) } else emptyList()
                }
                val player = activity.findViewById<PlayerView>(R.id.kids_player_view)?.player
                detail = "screen=${text(activity.window.decorView)}, state=${player?.playbackState}, position=${player?.currentPosition}"
            }
        }
        assertTrue("Timed out waiting for playlist playback: $detail", satisfied)
    }
}
