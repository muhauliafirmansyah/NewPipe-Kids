/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

@RunWith(AndroidJUnit4::class)
class KidsUiNavigationTest {
    @Test fun navigationAndFiltersStayWithinTheSelectedApprovedLibrary() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storage = KidsStorage.get(instrumentation.targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Maya", 30)
            parent.addProfile("1234", "b", "Leo", 30)
            val first = video("e_04ZrNroTo", "A playful song for kids", 599)
            val second = video("jNQXAC9IVRw", "Meet the animals", 65)
            val third = video("aqz-KE-bpKQ", "Story time", 180)
            val hidden = video("dQw4w9WgXcQ", "Hidden video")
            val other = video("9bZkp7q19f0", "Another profile video")
            listOf(first, second, third, hidden).forEach { parent.approveSearchItem("1234", setOf("a"), it) }
            parent.approveSearchItem("1234", setOf("b"), other)
            parent.setVideoHidden("1234", setOf("a"), hidden.id, true)
            val playlist = StoredContentId(0, "visual-test-playlist")
            val channel = StoredContentId(0, "visual-test-channel")
            storage.update { state ->
                val withChannel = ApprovalManager.approveSource(state, setOf("a"), SourceKind.CHANNEL, channel)
                val approved = ApprovalManager.approveSource(withChannel, setOf("a"), SourceKind.PLAYLIST, playlist)
                val started = ApprovalManager.beginSourceRefresh(approved, setOf("a"), SourceKind.PLAYLIST, playlist)
                val refreshed = ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(hidden.id, first.id, second.id))
                refreshed.copy(
                    selectedProfileId = "a",
                    profiles = refreshed.profiles.map { if (it.id == "a") it.copy(favorites = setOf(first.id), avatarId = "fox") else it.copy(avatarId = "panda") },
                    catalog = refreshed.catalog + listOf(
                        ParentSearchItem(ParentContentKind.CHANNEL, channel, "Cocomelon", "https://example.org/channel", first.thumbnailUrl),
                        ParentSearchItem(ParentContentKind.PLAYLIST, playlist, "Sing together", "https://example.org/playlist", second.thumbnailUrl, uploaderName = "Cocomelon")
                    )
                )
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, first.title) != null }
                scenario.onActivity { activity ->
                    assertNotNull(activity.findViewById<View>(R.id.kids_nav_home))
                    assertNotNull(activity.findViewById<View>(R.id.kids_nav_search))
                    assertNotNull(activity.findViewById<View>(R.id.kids_nav_stuff))
                    assertNotNull(activity.findViewById<View>(R.id.kids_nav_profile))
                    assertFalse(findButton(activity.window.decorView, hidden.title) != null)
                    assertFalse(findButton(activity.window.decorView, other.title) != null)
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("kids-playlist-count-${playlist.id}").text == "2 videos")
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("kids-duration-${first.id.id}").text == "9:59")
                }
                InstrumentationRegistry.getArguments().getString("expected_portrait_columns")?.toIntOrNull()?.let { expected ->
                    waitFor(scenario) { it.window.decorView.findViewWithTag<View>("kids-video-card-${first.id.id}")?.width ?: 0 > 0 }
                    scenario.onActivity { assertGrid(it, expected, channel.id, playlist.id, first.id.id) }
                }
                capture("home")
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_category_videos))).performClick() }
                capture("videos")
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_approved_for_you))).performClick() }
                scenario.onActivity { it.findViewById<Button>(R.id.kids_nav_search).performClick() }
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.kids_child_search).setText("animals")
                    assertNotNull(findButton(activity.window.decorView, second.title))
                    assertFalse(findButton(activity.window.decorView, first.title) != null)
                    assertFalse(findButton(activity.window.decorView, hidden.title) != null)
                }
                capture("search")
                scenario.recreate()
                waitFor(scenario) { it.findViewById<EditText>(R.id.kids_child_search)?.text?.toString() == "animals" }
                scenario.onActivity { activity ->
                    assertNotNull(findButton(activity.window.decorView, second.title))
                    assertFalse(findButton(activity.window.decorView, hidden.title) != null)
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertTrue(activity.findViewById<Button>(R.id.kids_nav_home).isSelected)
                }
                scenario.onActivity { it.findViewById<Button>(R.id.kids_nav_stuff).performClick() }
                scenario.onActivity { activity ->
                    assertNotNull(findButton(activity.window.decorView, first.title))
                    assertFalse(findButton(activity.window.decorView, second.title) != null)
                }
                capture("favorites")
                scenario.onActivity { it.findViewById<Button>(R.id.kids_nav_profile).performClick() }
                scenario.onActivity { activity ->
                    assertNotNull(findButton(activity.window.decorView, "Maya"))
                    assertNotNull(findButton(activity.window.decorView, "Leo"))
                    assertNotNull(activity.findViewById<View>(R.id.kids_edit_profile))
                }
                capture("profile-picker")
                if (InstrumentationRegistry.getArguments().getString("capture") == "true") {
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    waitFor(scenario) { it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp && it.findViewById<View>(R.id.kids_profile_picker) != null }
                    waitFor(scenario) { fullyVisible(it.findViewById(R.id.kids_edit_profile)) }
                    capture("profile-picker-landscape")
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    waitFor(scenario) { it.resources.configuration.screenWidthDp < it.resources.configuration.screenHeightDp && it.findViewById<View>(R.id.kids_edit_profile) != null }
                }
                scenario.onActivity { it.findViewById<Button>(R.id.kids_edit_profile).performClick() }
                waitFor(scenario) { it.findViewById<View>(R.id.kids_edit_profile_name) != null }
                capture("profile-editor")
                if (InstrumentationRegistry.getArguments().getString("capture") == "true") {
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    waitFor(scenario) { it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp && it.findViewById<View>(R.id.kids_edit_profile_name) != null }
                    waitFor(scenario) { fullyVisible(it.window.decorView.findViewWithTag("kids-avatar-fox")) }
                    capture("profile-editor-landscape")
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                    waitFor(scenario) { it.resources.configuration.screenWidthDp < it.resources.configuration.screenHeightDp && it.findViewById<View>(R.id.kids_edit_profile_name) != null }
                }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                scenario.onActivity { activity ->
                    requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.kids_category_playlists))).performClick()
                    assertNotNull(findButton(activity.window.decorView, "Sing together"))
                    assertFalse(findButton(activity.window.decorView, first.title) != null)
                    assertTrue(activity.window.decorView.findViewWithTag<TextView>("kids-playlist-count-${playlist.id}").text == "2 videos")
                }
                capture("playlist")
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("kids-playlist-thumbnail-${playlist.id}").performClick() }
                waitFor(scenario) { findButton(it.window.decorView, first.title) != null }
                scenario.onActivity { activity ->
                    assertNotNull(findButton(activity.window.decorView, second.title))
                    assertFalse(findButton(activity.window.decorView, hidden.title) != null)
                    activity.onBackPressedDispatcher.onBackPressed()
                }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_approved_for_you))).performClick() }
                if (InstrumentationRegistry.getArguments().getString("capture") == "true") {
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                    waitFor(scenario) { it.resources.configuration.screenWidthDp > it.resources.configuration.screenHeightDp }
                    waitFor(scenario) { findButton(it.window.decorView, first.title) != null }
                    InstrumentationRegistry.getArguments().getString("expected_landscape_columns")?.toIntOrNull()?.let { expected ->
                        waitFor(scenario) { it.window.decorView.findViewWithTag<View>("kids-video-card-${first.id.id}")?.width ?: 0 > 0 }
                        scenario.onActivity { assertGrid(it, expected, channel.id, playlist.id, first.id.id) }
                    }
                    capture("home-landscape")
                    scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
                }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun captureSetupAndParentScreensWhenRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("capture") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ActivityScenario.launch(KidsSetupActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                capture("setup")
            }
            ParentSettings(storage).setup("1234", "a", "Maya", 30)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                capture("parent-unlock")
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    activity.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                val deadline = System.currentTimeMillis() + 10_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { ready = findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content)) != null }
                    if (!ready) Thread.sleep(50)
                }
                assertTrue("Parent screen did not open", ready)
                capture("parent-approved")
            }
        } finally {
            storage.update { original }
        }
    }

    private fun video(id: String, title: String, durationSeconds: Long? = null) = ParentSearchItem(
        ParentContentKind.VIDEO,
        VideoIdentity.youtube(id),
        title,
        "https://youtu.be/$id",
        "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        durationSeconds = durationSeconds
    )

    private fun assertGrid(activity: KidsChildActivity, columns: Int, channel: String, playlist: String, video: String) {
        val window = activity.window.decorView
        val channelCard = requireNotNull(window.findViewWithTag<LinearLayout>("kids-source-$channel"))
        val videoCard = requireNotNull(window.findViewWithTag<LinearLayout>("kids-video-card-$video"))
        val playlistCard = requireNotNull(window.findViewWithTag<LinearLayout>("kids-source-$playlist"))
        assertEquals(LinearLayout.VERTICAL, channelCard.orientation)
        assertEquals(LinearLayout.VERTICAL, videoCard.orientation)
        assertEquals(LinearLayout.VERTICAL, playlistCard.orientation)
        val row = channelCard.parent as ViewGroup
        assertEquals(columns * 2 - 1, row.childCount)
        assertEquals(minOf(columns, 5), (0 until row.childCount).count { row.getChildAt(it) !is Space })
        assertTrue("Channel card should have visible height", channelCard.height > 100)
        assertTrue("Video card should have visible height", videoCard.height > 100)
        assertTrue("Playlist card should have visible height", playlistCard.height > 100)
        if (columns >= 2) {
            assertSame(row, videoCard.parent)
            assertEquals(channelCard.height, videoCard.height)
        }
        if (columns >= 3) {
            assertSame(row, playlistCard.parent)
            assertEquals(channelCard.height, playlistCard.height)
            val channelTitle = requireNotNull(channelCard.findViewWithTag<View>("kids-source-title-$channel"))
            val videoTitle = requireNotNull(videoCard.findViewWithTag<View>("kids-video-$video"))
            val playlistTitle = requireNotNull(playlistCard.findViewWithTag<View>("kids-source-title-$playlist"))
            assertTrue("Card titles should be visible", fullyVisible(channelTitle) && fullyVisible(videoTitle) && fullyVisible(playlistTitle))
            if (activity.resources.configuration.screenHeightDp >= 360) {
                assertTrue("First row should be fully visible", fullyVisible(channelCard) && fullyVisible(videoCard) && fullyVisible(playlistCard))
            }
        }
        val thumbnail = requireNotNull(window.findViewWithTag<View>("kids-thumbnail-$video"))
        assertTrue("Thumbnail should be visible", thumbnail.width > 0 && thumbnail.height > 0)
        assertTrue("Thumbnail should retain a 16:9 shape", kotlin.math.abs(thumbnail.width * 9 - thumbnail.height * 16) <= 32)
    }

    private fun fullyVisible(view: View?): Boolean {
        val bounds = Rect()
        return view != null && view.getGlobalVisibleRect(bounds) && bounds.height() == view.height && bounds.width() == view.width
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("capture") != "true") return
        Thread.sleep(1_000)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 5_000
        while (TestAppWindow.activeRoot() == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
        checkNotNull(TestAppWindow.activeRoot()) { "The test app is not in the foreground" }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(instrumentation.targetContext.cacheDir, "kids-ui-$name.png").outputStream().use {
            check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        screenshot.recycle()
    }

    private fun waitFor(scenario: ActivityScenario<KidsChildActivity>, condition: (KidsChildActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        var ready = false
        while (!ready && System.currentTimeMillis() < deadline) {
            scenario.onActivity { ready = condition(it) }
            if (!ready) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for the child screen", ready)
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findButton(view.getChildAt(index), text)?.let { return it }
        return null
    }
}
