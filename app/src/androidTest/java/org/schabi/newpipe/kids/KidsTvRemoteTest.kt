/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.ui.R as PlayerUiR
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

/** Real D-pad key events on a TV emulator, with isolated synthetic profiles. */
@RunWith(AndroidJUnit4::class)
class KidsTvRemoteTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun remoteCompletesFirstRunSetup() {
        withEmptyState { storage ->
            ActivityScenario.launch(KidsSetupActivity::class.java).use { scenario ->
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_profile_name }
                instrumentation.sendStringSync("Maya")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_session_minutes }
                instrumentation.sendStringSync("30")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_pin }
                instrumentation.sendStringSync("1234")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_pin_confirm }
                instrumentation.sendStringSync("1234")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_setup_create }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val deadline = System.currentTimeMillis() + 10_000
                while (storage.read().profiles.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
                assertEquals("Maya", storage.read().profiles.single().name)
                assertEquals(30, storage.read().profiles.single().sessionLimitMinutes)
                ParentSettings(storage).authenticate("1234")
            }
        }
    }

    @Test fun remoteSelectsProfileAndNavigatesCardsAndPlaylist() {
        withEmptyState { storage ->
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Maya", 30)
            parent.addProfile("1234", "b", "Leo", 30)
            val first = video("e_04ZrNroTo", "Approved song")
            val second = video("jNQXAC9IVRw", "Approved animals")
            listOf(first, second).forEach { parent.approveSearchItem("1234", setOf("a"), it) }
            val channel = StoredContentId(0, "tv-test-channel")
            val playlist = StoredContentId(0, "tv-test-playlist")
            storage.update { state ->
                state.copy(
                    selectedProfileId = null,
                    profiles = state.profiles.map {
                        if (it.id != "a") {
                            it
                        } else {
                            it.copy(
                                sources = listOf(
                                    StoredSource(SourceKind.CHANNEL, channel),
                                    StoredSource(SourceKind.PLAYLIST, playlist, videos = listOf(first.id, second.id))
                                )
                            )
                        }
                    },
                    catalog = state.catalog + listOf(
                        ParentSearchItem(ParentContentKind.CHANNEL, channel, "Test channel", "https://example.org/channel"),
                        ParentSearchItem(ParentContentKind.PLAYLIST, playlist, "Test playlist", "https://example.org/playlist")
                    )
                )
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { it.currentFocus?.tag == "kids-profile-a" }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.tag == "kids-profile-b" }
                key(KeyEvent.KEYCODE_DPAD_LEFT)
                waitFor(scenario) { it.currentFocus?.tag == "kids-profile-a" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.currentFocus?.tag == "kids-category-ALL" }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.tag == "kids-source-${channel.id}" }
                capture("library")
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.tag == "kids-video-card-${second.id.id}" }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.tag == "kids-source-${playlist.id}" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.currentFocus?.tag == "kids-video-card-${first.id.id}" }
                scenario.onActivity { activity ->
                    assertTrue(activity.window.decorView.findViewWithTag<View>("kids-video-card-${second.id.id}") != null)
                    assertFalse(activity.window.decorView.findViewWithTag<View>("kids-source-${channel.id}") != null)
                }
                key(KeyEvent.KEYCODE_BACK)
                waitFor(scenario) { it.window.decorView.findViewWithTag<View>("kids-source-${channel.id}") != null }
            }
        }
    }

    @Test fun remoteUnlocksParentAndMovesThroughSearchFilters() {
        withEmptyState { storage ->
            ParentSettings(storage).setup("1234", "a", "Maya", 30)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_unlock_pin }
                instrumentation.sendStringSync("1234")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_unlock_button }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { (it.currentFocus as? Button)?.text == it.getString(R.string.kids_parent_profiles) }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { (it.currentFocus as? Button)?.text == it.getString(R.string.kids_parent_add_content) }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_search_input }
                instrumentation.sendStringSync("animals")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_filter_all }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_filter_videos }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_filter_channels }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_filter_playlists }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_search_button }
                key(KeyEvent.KEYCODE_BACK)
                waitFor(scenario) { it.findViewById<EditText>(R.id.kids_parent_search_input) == null }
                scenario.onActivity { assertEquals(it.getString(R.string.kids_parent_profiles), (it.currentFocus as? Button)?.text) }
            }
        }
    }

    @Test fun remoteControlsLivePlaybackAndBackReturnsToLibrary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        withEmptyState { storage ->
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Maya", 30)
            val item = video("e_04ZrNroTo", "Approved song")
            parent.approveSearchItem("1234", setOf("a"), item)
            storage.update { it.copy(selectedProfileId = "a") }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { it.currentFocus?.tag == "kids-category-ALL" }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.tag == "kids-video-card-${item.id.id}" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario, 50_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) > 1_000 }
                capture("playback")
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.playWhenReady == false }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.playWhenReady == true }
                waitFor(scenario, 15_000) { activity ->
                    val view = activity.findViewById<PlayerView>(R.id.kids_player_view)
                    view != null && !view.isControllerVisible && activity.currentFocus === view
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.playWhenReady == false }
                key(KeyEvent.KEYCODE_BACK)
                waitFor(scenario) {
                    it.findViewById<PlayerView>(R.id.kids_player_view) == null &&
                        it.window.decorView.findViewWithTag<View>("kids-video-card-${item.id.id}") != null
                }
            }
        }
    }

    @Test fun remoteSearchesAChannelAndApprovesOnlyASelectedVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        withEmptyState { storage ->
            ParentSettings(storage).setup("1234", "a", "Maya", 30)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_unlock_pin }
                instrumentation.sendStringSync("1234")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_unlock_button }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { (it.currentFocus as? Button)?.text == it.getString(R.string.kids_parent_profiles) }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_search_input }
                instrumentation.sendStringSync("cocomelon")
                dismissKeyboard(scenario)
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_filter_channels }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario, 40_000) {
                    it.currentFocus?.id == R.id.kids_parent_filter_channels &&
                        it.window.decorView.findViewWithTag<View>("kids-result-CHANNEL") != null
                }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_search_button }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.tag == "kids-result-CHANNEL" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                dialogFocus("Open", KeyEvent.KEYCODE_DPAD_LEFT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario, 40_000) { (it.currentFocus as? CheckBox)?.tag?.toString()?.startsWith("kids-source-select-") == true }
                var selectedId = ""
                scenario.onActivity { selectedId = requireNotNull(it.currentFocus).tag.toString().removePrefix("kids-source-select-") }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { (it.currentFocus as? CheckBox)?.isChecked == true }
                key(KeyEvent.KEYCODE_DPAD_UP)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_parent_approve_selected }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                dialogFocus("Maya", KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                dialogFocus("Cancel", KeyEvent.KEYCODE_DPAD_DOWN)
                dialogFocus("Approve", KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                val deadline = System.currentTimeMillis() + 20_000
                while (storage.read().profiles.single().approvedVideos.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50)
                val profile = storage.read().profiles.single()
                assertEquals(setOf(VideoIdentity.youtube(selectedId)), profile.approvedVideos)
                assertTrue(profile.sources.isEmpty())
            }
        }
    }

    @Test fun remoteAdvancesAnApprovedPlaylistWithAutoplayOff() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        withEmptyState { storage ->
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Maya", 30)
            val first = video("e_04ZrNroTo", "First song")
            val second = video("jNQXAC9IVRw", "Second video")
            listOf(first, second).forEach { parent.approveSearchItem("1234", setOf("a"), it) }
            val playlist = StoredContentId(0, "tv-playback-playlist")
            storage.update { state ->
                state.copy(
                    selectedProfileId = "a",
                    profiles = state.profiles.map {
                        it.copy(autoplay = false, sources = listOf(StoredSource(SourceKind.PLAYLIST, playlist, videos = listOf(first.id, second.id))))
                    },
                    catalog = state.catalog + ParentSearchItem(ParentContentKind.PLAYLIST, playlist, "Test playlist", "https://example.org/playlist")
                )
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { it.currentFocus?.tag == "kids-category-ALL" }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.tag == "kids-source-${playlist.id}" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.currentFocus?.tag == "kids-video-card-${first.id.id}" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario, 50_000) {
                    (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) > 1_000 &&
                        it.currentFocus?.id == PlayerUiR.id.exo_pause
                }
                key(KeyEvent.KEYCODE_DPAD_UP)
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.id == R.id.kids_player_next }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario, 50_000) {
                    it.findViewById<TextView>(R.id.kids_player_title)?.text == "Second video" &&
                        (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) > 1_000
                }
                assertFalse(storage.read().profiles.single().autoplay)
            }
        }
    }

    @Test fun remoteSeeksWithinAnApprovedVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        withEmptyState { storage ->
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Maya", 30)
            val item = video("e_04ZrNroTo", "Approved song")
            parent.approveSearchItem("1234", setOf("a"), item)
            storage.update { it.copy(selectedProfileId = "a") }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { it.currentFocus?.tag == "kids-category-ALL" }
                key(KeyEvent.KEYCODE_DPAD_DOWN)
                waitFor(scenario) { it.currentFocus?.tag == "kids-video-card-${item.id.id}" }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario, 50_000) {
                    (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) > 1_000 &&
                        it.currentFocus?.id == PlayerUiR.id.exo_pause
                }
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { it.currentFocus?.id == PlayerUiR.id.exo_play }
                var position = 0L
                scenario.onActivity { position = requireNotNull(it.findViewById<PlayerView>(R.id.kids_player_view).player).currentPosition }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                waitFor(scenario) { it.currentFocus?.id == PlayerUiR.id.exo_progress }
                key(KeyEvent.KEYCODE_DPAD_RIGHT)
                key(KeyEvent.KEYCODE_DPAD_CENTER)
                waitFor(scenario) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= position + 3_000 }
                key(KeyEvent.KEYCODE_BACK)
                waitFor(scenario) { it.findViewById<PlayerView>(R.id.kids_player_view) == null }
            }
        }
    }

    private fun video(id: String, title: String) = ParentSearchItem(
        ParentContentKind.VIDEO,
        VideoIdentity.youtube(id),
        title,
        "https://youtu.be/$id"
    )

    private fun withEmptyState(test: (KidsStorage) -> Unit) {
        assumeTrue(KidsTvNavigation.isTv(context))
        val storage = KidsStorage.get(context)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            test(storage)
        } finally {
            storage.update { original }
        }
    }

    private fun key(code: Int) {
        instrumentation.sendKeyDownUpSync(code)
        instrumentation.waitForIdleSync()
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
        checkNotNull(TestAppWindow.activeRoot())
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(context.cacheDir, "kids-tv-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun <T : Activity> dismissKeyboard(scenario: ActivityScenario<T>) {
        // TV keyboards can appear asynchronously after the editor receives focus.
        Thread.sleep(300)
        var shown = false
        scenario.onActivity {
            if (Build.VERSION.SDK_INT >= 30) shown = it.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        }
        shown = shown || instrumentation.uiAutomation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.isActive }
        if (shown) key(KeyEvent.KEYCODE_BACK)
    }

    private fun dialogFocus(text: String, direction: Int) {
        val deadline = System.currentTimeMillis() + 10_000
        var focused: String? = null
        while (System.currentTimeMillis() < deadline) {
            val root = TestAppWindow.activeRoot()
            focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.text?.toString()
            if (focused.equals(text, ignoreCase = true)) return
            val target = root?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
            if (target?.isFocused == true || target?.isSelected == true) return
            if (target != null) {
                key(direction)
                Thread.sleep(200)
            } else {
                Thread.sleep(50)
            }
        }
        assertTrue("Could not focus $text in the TV dialog; focused: $focused", false)
    }

    private fun <T : Activity> waitFor(scenario: ActivityScenario<T>, timeout: Long = 10_000, condition: (T) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        var found = false
        var focus = ""
        while (!found && System.currentTimeMillis() < deadline) {
            scenario.onActivity {
                found = it.hasWindowFocus() && condition(it)
                focus = "${it.currentFocus?.javaClass?.simpleName} id=${it.currentFocus?.id} tag=${it.currentFocus?.tag} window=${it.hasWindowFocus()}"
            }
            if (!found) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for TV navigation; focus: $focus", found)
    }
}
