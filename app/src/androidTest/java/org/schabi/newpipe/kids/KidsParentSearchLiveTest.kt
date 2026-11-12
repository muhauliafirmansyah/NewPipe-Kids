/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
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
import org.schabi.newpipe.extractor.ServiceList

/** Opt in with -e live true; uses a real search but no personal app data. */
@RunWith(AndroidJUnit4::class)
class KidsParentSearchLiveTest {
    @Test fun searchAndApprovePeerTubeVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storage = KidsStorage.get(instrumentation.targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ParentSettings(storage).setup("1234", "test", "Test child", 10)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor {
                    var found = false
                    scenario.onActivity { found = findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content)) != null }
                    found
                }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content))).performClick() }
                scenario.onActivity {
                    val label = it.getString(R.string.kids_parent_service, "YouTube")
                    requireNotNull(findButton(it.window.decorView, label)).performClick()
                }
                clickAccessible("PeerTube")
                waitFor {
                    var found = false
                    scenario.onActivity { found = findButton(it.window.decorView, it.getString(R.string.kids_parent_service, "PeerTube")) != null }
                    found
                }
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_search_input).setText("animation")
                    it.findViewById<Button>(R.id.kids_parent_search_button).performClick()
                }
                waitFor {
                    var found = false
                    scenario.onActivity { found = it.window.decorView.findViewWithTag<Button>("kids-result-VIDEO") != null }
                    found
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<Button>("kids-result-VIDEO").performClick() }
                clickAccessible("Approve")
                clickAccessible("Test child")
                clickAccessible("Approve")
                waitFor { storage.read().profiles.single().approvedVideos.any { it.serviceId == ServiceList.PeerTube.serviceId } }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun searchAndApproveVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storage = KidsStorage.get(instrumentation.targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ParentSettings(storage).setup("1234", "test", "Test child", 10)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor {
                    var found = false
                    scenario.onActivity { found = findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content)) != null }
                    found
                }
                scenario.onActivity {
                    requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content))).performClick()
                    it.findViewById<EditText>(R.id.kids_parent_search_input).setText("cocomelon")
                    it.findViewById<Button>(R.id.kids_parent_search_button).performClick()
                }
                waitFor {
                    var found = false
                    scenario.onActivity { found = it.window.decorView.findViewWithTag<Button>("kids-result-VIDEO") != null }
                    found
                }
                scenario.onActivity { it.window.decorView.findViewWithTag<Button>("kids-result-VIDEO").performClick() }
                clickAccessible("Approve")
                clickAccessible("Test child")
                clickAccessible("Approve")
                waitFor { storage.read().profiles.single().approvedVideos.isNotEmpty() }
                assertTrue(storage.read().catalog.any { it.kind == ParentContentKind.VIDEO })
            }
        } finally {
            storage.update { original }
        }
    }

    private fun findButton(view: android.view.View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                findButton(view.getChildAt(i), text)?.let { return it }
            }
        }
        return null
    }

    private fun clickAccessible(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        waitFor {
            val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)
                ?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
            if (node == null) {
                false
            } else {
                var target: AccessibilityNodeInfo? = node
                while (target != null && !target.isClickable) target = target.parent
                if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return@waitFor true
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val tapped = automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true)
                down.recycle()
                up.recycle()
                tapped
            }
        }
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            done = condition()
            if (!done) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for parent search flow", done)
    }
}
