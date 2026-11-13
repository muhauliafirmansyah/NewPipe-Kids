/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

@RunWith(AndroidJUnit4::class)
class KidsShortsTest {
    @Test fun parentCanHideAndRestoreShortsForOneChild() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "First child", 10)
            parent.addProfile("1234", "b", "Second child", 10)
            val normal = ParentSearchItem(ParentContentKind.VIDEO, VideoIdentity.youtube("e_04ZrNroTo"), "Normal video", "https://youtu.be/e_04ZrNroTo")
            val short = ParentSearchItem(ParentContentKind.VIDEO, VideoIdentity.youtube("dQw4w9WgXcQ"), "Short video", "https://youtu.be/dQw4w9WgXcQ", isShortForm = true)
            parent.approveSearchItem("1234", setOf("a", "b"), normal)
            parent.approveSearchItem("1234", setOf("a", "b"), short)
            val playlistId = StoredContentId(0, "shorts-test-playlist")
            storage.update { state ->
                val approved = ApprovalManager.approveSource(state, setOf("a"), SourceKind.PLAYLIST, playlistId)
                val started = ApprovalManager.beginSourceRefresh(approved, setOf("a"), SourceKind.PLAYLIST, playlistId)
                val refreshed = ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(short.id, normal.id))
                refreshed.copy(selectedProfileId = "a", catalog = refreshed.catalog + ParentSearchItem(ParentContentKind.PLAYLIST, playlistId, "Test playlist", "https://example.org/list"))
            }
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitParent(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_parent_profiles)) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_profiles))).performClick() }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "First child")).performClick() }
                clickDialog("Hide Shorts")
                waitFor { !storage.read().profiles.first { it.id == "a" }.showShorts }
                assertTrue(storage.read().profiles.first { it.id == "b" }.showShorts)
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitChild(scenario) { findButton(it.window.decorView, normal.title) != null }
                scenario.onActivity { activity ->
                    assertFalse(findButton(activity.window.decorView, short.title) != null)
                    assertEquals("1 video", activity.window.decorView.findViewWithTag<TextView>("kids-playlist-count-${playlistId.id}").text)
                    requireNotNull(findButton(activity.window.decorView, "Test playlist")).performClick()
                    assertNotNull(findButton(activity.window.decorView, normal.title))
                    assertFalse(findButton(activity.window.decorView, short.title) != null)
                    activity.onBackPressedDispatcher.onBackPressed()
                    activity.findViewById<Button>(R.id.kids_nav_search).performClick()
                    activity.findViewById<EditText>(R.id.kids_child_search).setText("short")
                    assertFalse(findButton(activity.window.decorView, short.title) != null)
                }
                assertFalse(ChildPlaybackAccess.allowed(storage.read(), "a", short.id))
                assertTrue(ChildLibrary.videos(storage.read(), "b").any { it.id == short.id })
                parent.setShowShorts("1234", "a", true)
                waitChild(scenario) { findButton(it.window.decorView, short.title) != null }
            }
        } finally {
            storage.update { original }
        }
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findButton(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun clickDialog(text: String) {
        waitFor { TestAppWindow.clickText(text) }
    }

    private fun waitParent(scenario: ActivityScenario<KidsParentActivity>, condition: (KidsParentActivity) -> Boolean) {
        waitFor {
            var found = false
            scenario.onActivity { found = condition(it) }
            found
        }
    }

    private fun waitChild(scenario: ActivityScenario<KidsChildActivity>, condition: (KidsChildActivity) -> Boolean) {
        waitFor {
            var found = false
            scenario.onActivity { found = condition(it) }
            found
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        var found = condition()
        while (!found && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            found = condition()
        }
        assertTrue("Timed out waiting for Shorts test", found)
    }
}
