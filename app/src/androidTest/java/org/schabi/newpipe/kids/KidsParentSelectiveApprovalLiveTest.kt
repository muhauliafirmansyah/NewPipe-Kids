/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList

/** Real search and source browsing in a fresh test-only app installation. */
@RunWith(AndroidJUnit4::class)
class KidsParentSelectiveApprovalLiveTest {
    @Test fun filteredPlaylistSearchApprovesOnlySelectedVideos() {
        withParent { scenario, storage ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.kids_parent_search_input).setText("cocomelon")
                activity.findViewById<Button>(R.id.kids_parent_filter_channels).performClick()
            }
            waitFor(scenario, 40_000) { it.window.decorView.findViewWithTag<Button>("kids-result-CHANNEL") != null }
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.findViewWithTag<Button>("kids-result-VIDEO") == null)
                assertTrue(activity.window.decorView.findViewWithTag<Button>("kids-result-PLAYLIST") == null)
                activity.findViewById<Button>(R.id.kids_parent_filter_videos).performClick()
            }
            waitFor(scenario, 40_000) { it.window.decorView.findViewWithTag<Button>("kids-result-VIDEO") != null }
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.findViewWithTag<Button>("kids-result-CHANNEL") == null)
                assertTrue(activity.window.decorView.findViewWithTag<Button>("kids-result-PLAYLIST") == null)
                activity.findViewById<EditText>(R.id.kids_parent_search_input).setText("Crash Course ecology playlist")
                activity.findViewById<Button>(R.id.kids_parent_filter_playlists).performClick()
            }
            waitFor(scenario, 40_000) { it.window.decorView.findViewWithTag<Button>("kids-result-PLAYLIST") != null }
            scenario.onActivity { activity ->
                assertTrue(activity.window.decorView.findViewWithTag<Button>("kids-result-VIDEO") == null)
                assertTrue(activity.window.decorView.findViewWithTag<Button>("kids-result-CHANNEL") == null)
                activity.window.decorView.findViewWithTag<Button>("kids-result-PLAYLIST").performClick()
            }
            clickDialog("Open")
            waitFor(scenario, 40_000) { choices(it.window.decorView).size >= 2 }
            val selectedIds = mutableSetOf<StoredContentId>()
            scenario.onActivity { activity ->
                val boxes = choices(activity.window.decorView)
                assertFalse(activity.findViewById<Button>(R.id.kids_parent_approve_selected).isEnabled)
                boxes.take(2).forEach { box ->
                    selectedIds.add(VideoIdentity.youtube(box.tag.toString().removePrefix("kids-source-select-")))
                    box.performClick()
                }
                assertTrue(activity.findViewById<Button>(R.id.kids_parent_approve_selected).isEnabled)
                activity.findViewById<Button>(R.id.kids_parent_approve_selected).performClick()
            }
            clickDialog("First child")
            clickDialog("Approve")
            waitFor(scenario) { storage.read().profiles.first { it.id == "first" }.approvedVideos == selectedIds }
            val state = storage.read()
            assertTrue(state.profiles.first { it.id == "first" }.sources.isEmpty())
            assertTrue(state.profiles.first { it.id == "second" }.approvedVideos.isEmpty())
            assertTrue(state.catalog.filter { it.kind == ParentContentKind.VIDEO }.map { it.id }.containsAll(selectedIds))
            waitFor(scenario) { choices(it.window.decorView).size >= 2 && !it.findViewById<Button>(R.id.kids_parent_approve_selected).isEnabled }
            scenario.onActivity { activity ->
                requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.kids_parent_back_search))).performClick()
                assertTrue(activity.findViewById<EditText>(R.id.kids_parent_search_input).text.toString() == "Crash Course ecology playlist")
                assertTrue(activity.findViewById<Button>(R.id.kids_parent_filter_playlists).isSelected)
                assertTrue(activity.window.decorView.findViewWithTag<Button>("kids-result-PLAYLIST") != null)
            }
        }
    }

    @Test fun publicPlaylistCanBeBrowsedWithoutApprovingIt() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val id = SourceIdentity.fromUrl(
            ServiceList.YouTube.serviceId,
            SourceKind.PLAYLIST,
            "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8"
        )
        val page = ParentSourceBrowser.firstPage(SourceKind.PLAYLIST, id)
        assertTrue(page.videos.isNotEmpty())
        assertTrue(page.videos.all { it.kind == ParentContentKind.VIDEO && it.id.serviceId == id.serviceId })
    }

    @Test fun publicChannelLoadsAdditionalPages() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val id = SourceIdentity.fromUrl(
            ServiceList.YouTube.serviceId,
            SourceKind.CHANNEL,
            "https://www.youtube.com/channel/UCbCmjCuTUZos6Inko4u57UQ"
        )
        val first = ParentSourceBrowser.firstPage(SourceKind.CHANNEL, id)
        assertTrue(first.videos.isNotEmpty())
        val more = ParentSourceBrowser.nextPage(requireNotNull(first.next))
        assertTrue(more.videos.isNotEmpty())
        assertTrue(more.videos.all { it.kind == ParentContentKind.VIDEO && it.id.serviceId == id.serviceId })
    }

    @Test fun channelVideoCanBeApprovedIndividuallyWithoutApprovingTheChannel() {
        withParent { scenario, storage ->
            scenario.onActivity { activity ->
                activity.findViewById<EditText>(R.id.kids_parent_search_input).setText("cocomelon")
                activity.findViewById<Button>(R.id.kids_parent_filter_channels).performClick()
            }
            waitFor(scenario, 40_000) { it.window.decorView.findViewWithTag<Button>("kids-result-CHANNEL") != null }
            scenario.onActivity { it.window.decorView.findViewWithTag<Button>("kids-result-CHANNEL").performClick() }
            clickDialog("Open")
            waitFor(scenario, 40_000) { choices(it.window.decorView).isNotEmpty() }
            var videoId: StoredContentId? = null
            var previousCount = 0
            var canLoadMore = false
            scenario.onActivity { activity ->
                val boxes = choices(activity.window.decorView)
                val box = boxes.first()
                val id = box.tag.toString().removePrefix("kids-source-select-")
                videoId = VideoIdentity.youtube(id)
                box.performClick()
                previousCount = boxes.size
                val more = findButton(activity.window.decorView, activity.getString(R.string.kids_parent_more))
                if (more != null) {
                    canLoadMore = true
                    more.performClick()
                }
            }
            if (canLoadMore) waitFor(scenario, 40_000) { choices(it.window.decorView).size > previousCount }
            scenario.onActivity { activity ->
                val id = requireNotNull(videoId).id
                assertTrue(requireNotNull(activity.window.decorView.findViewWithTag<CheckBox>("kids-source-select-$id")).isChecked)
                requireNotNull(activity.window.decorView.findViewWithTag<Button>("kids-source-video-$id")).performClick()
            }
            clickDialog("Approve")
            clickDialog("Second child")
            clickDialog("Approve")
            waitFor(scenario) { requireNotNull(videoId) in storage.read().profiles.first { it.id == "second" }.approvedVideos }
            val state = storage.read()
            assertTrue(state.profiles.all { it.sources.isEmpty() })
            assertTrue(state.profiles.first { it.id == "first" }.approvedVideos.isEmpty())
            waitFor(scenario) { choices(it.window.decorView).isNotEmpty() }
        }
    }

    private fun withParent(block: (ActivityScenario<KidsParentActivity>, KidsStorage) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "first", "First child", 10)
            parent.addProfile("1234", "second", "Second child", 10)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content)) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content))).performClick() }
                block(scenario, storage)
            }
        } finally {
            storage.update { original }
        }
    }

    private fun choices(view: View): List<CheckBox> {
        val current = if (view is CheckBox && view.tag?.toString()?.startsWith("kids-source-select-") == true) listOf(view) else emptyList()
        return current + if (view is ViewGroup) (0 until view.childCount).flatMap { choices(view.getChildAt(it)) } else emptyList()
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findButton(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun clickDialog(text: String) {
        val deadline = System.currentTimeMillis() + 20_000
        var clicked = false
        while (!clicked && System.currentTimeMillis() < deadline) {
            clicked = TestAppWindow.clickText(text)
            if (!clicked) Thread.sleep(50)
        }
        assertTrue("Could not select $text in the test app", clicked)
    }

    private fun waitFor(scenario: ActivityScenario<KidsParentActivity>, timeout: Long = 10_000, condition: (KidsParentActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        var satisfied = false
        while (!satisfied && System.currentTimeMillis() < deadline) {
            scenario.onActivity { satisfied = condition(it) }
            if (!satisfied) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for parent selection", satisfied)
    }
}
