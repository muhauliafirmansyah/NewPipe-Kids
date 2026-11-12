/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

@RunWith(AndroidJUnit4::class)
class KidsLauncherSetupTest {
    @Test fun firstLaunchCreatesProfileThenOpensParentApprovalList() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storage = KidsStorage.get(instrumentation.targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ActivityScenario.launch(KidsChildActivity::class.java).use {
                val setup = waitForActivity(KidsSetupActivity::class.java)
                instrumentation.runOnMainSync {
                    setup.findViewById<EditText>(R.id.kids_profile_name).setText("New child")
                    setup.findViewById<EditText>(R.id.kids_session_minutes).setText("15")
                    setup.findViewById<EditText>(R.id.kids_parent_pin).setText("1234")
                    setup.findViewById<EditText>(R.id.kids_parent_pin_confirm).setText("1234")
                    setup.findViewById<Button>(R.id.kids_setup_create).performClick()
                }
                val parent = waitForActivity(KidsParentActivity::class.java)
                instrumentation.runOnMainSync {
                    parent.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    parent.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                val deadline = System.currentTimeMillis() + 10_000
                var listShown = false
                while (!listShown && System.currentTimeMillis() < deadline) {
                    instrumentation.runOnMainSync { listShown = findButton(parent.window.decorView, parent.getString(R.string.kids_parent_add_content)) != null }
                    if (!listShown) Thread.sleep(50)
                }
                assertTrue("Parent approval list did not open", listShown)
                assertEquals("New child", storage.read().profiles.single().name)
                instrumentation.runOnMainSync { parent.finish() }
            }
        } finally {
            storage.update { original }
        }
    }

    private fun <T : Activity> waitForActivity(type: Class<T>): T {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = System.currentTimeMillis() + 10_000
        var found: T? = null
        while (found == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { type.isInstance(it) }?.let(type::cast)
            }
            if (found == null) Thread.sleep(50)
        }
        return requireNotNull(found) { "Timed out waiting for ${type.simpleName}" }
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
}
