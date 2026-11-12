/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

/** Opt in with -e live true; drives parent search through source assignment. */
@RunWith(AndroidJUnit4::class)
class KidsParentSourceSearchLiveTest {
    @Test fun parentCanSearchForAndApproveAChannel() = approveSource("Creative Commons", ParentContentKind.CHANNEL, SourceKind.CHANNEL)

    @Test fun parentCanSearchForAndApproveAPlaylist() = approveSource("Crash Course ecology playlist", ParentContentKind.PLAYLIST, SourceKind.PLAYLIST)

    private fun approveSource(query: String, kind: ParentContentKind, sourceKind: SourceKind) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ParentSettings(storage).setup("1234", "test", "Test child", 10)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content)) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content))).performClick() }
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_search_input).setText(query)
                    it.findViewById<Button>(R.id.kids_parent_search_button).performClick()
                }
                waitFor(scenario, 30_000) { countResults(it.window.decorView) > 0 }
                var found = false
                repeat(4) {
                    if (!found) {
                        var count = 0
                        var more = false
                        scenario.onActivity { activity ->
                            val result = activity.window.decorView.findViewWithTag<Button>("kids-result-${kind.name}")
                            if (result != null) {
                                found = true
                                result.performClick()
                            } else {
                                count = countResults(activity.window.decorView)
                                val next = findButton(activity.window.decorView, activity.getString(R.string.kids_parent_more))
                                if (next != null) {
                                    more = true
                                    next.performClick()
                                }
                            }
                        }
                        if (!found && more) waitFor(scenario, 30_000) { countResults(it.window.decorView) > count }
                    }
                }
                assertTrue("No $kind result found", found)
                clickDialogText("Approve")
                clickDialogText("Test child")
                clickDialogText("Approve")
                waitFor(scenario, 30_000) { storage.read().profiles.single().sources.any { it.kind == sourceKind } }
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

    private fun countResults(view: View): Int {
        val self = if (view is Button && view.tag?.toString()?.startsWith("kids-result-") == true) 1 else 0
        return self + if (view is ViewGroup) (0 until view.childCount).sumOf { countResults(view.getChildAt(it)) } else 0
    }

    private fun clickDialogText(text: String) {
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
        assertTrue("Timed out waiting for parent source search", satisfied)
    }
}
