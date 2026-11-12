/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestAppWindowTest {
    @Test fun ignoresWindowsOutsideTheTargetApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(home)
        val deadline = System.currentTimeMillis() + 10_000
        var anotherWindow = false
        while (!anotherWindow && System.currentTimeMillis() < deadline) {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            anotherWindow = root != null && root.packageName?.toString() != context.packageName
            if (!anotherWindow) Thread.sleep(50)
        }
        assertTrue("A non-test window did not become active", anotherWindow)
        assertNotNull(instrumentation.uiAutomation.rootInActiveWindow)
        assertNull(TestAppWindow.activeRoot())
        assertFalse(TestAppWindow.clickText("Open"))
    }
}
