/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.widget.AdapterView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry

/** Do not inspect or interact with another app when the test app loses focus. */
internal object TestAppWindow {
    fun activeRoot(): AccessibilityNodeInfo? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        return root.takeIf { it.packageName?.toString() == instrumentation.targetContext.packageName }
    }

    fun clickText(text: String): Boolean {
        val root = activeRoot() ?: return false
        val node = root.findAccessibilityNodeInfosByText(text)
            .firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true } ?: return false
        if (clickListItem(text)) return true
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (activeRoot()?.windowId != root.windowId) return false
        return target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    /** AlertDialog list rows are not always accessibility-clickable; invoke the app-owned list directly. */
    private fun clickListItem(text: String): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        var clicked = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            fun find(view: View): TextView? {
                if (view is TextView && view.text?.toString()?.equals(text, ignoreCase = true) == true) return view
                if (view is ViewGroup) for (index in 0 until view.childCount) find(view.getChildAt(index))?.let { return it }
                return null
            }
            for (window in WindowInspector.getGlobalWindowViews().asReversed()) {
                if (!window.isShown || !window.hasWindowFocus()) continue
                val label = find(window) ?: continue
                var child: View = label
                var parent = child.parent
                while (parent is View && parent !is AdapterView<*>) {
                    child = parent
                    parent = child.parent
                }
                if (parent is AdapterView<*>) {
                    val position = parent.getPositionForView(child)
                    if (position != AdapterView.INVALID_POSITION) {
                        clicked = parent.performItemClick(child, position, parent.getItemIdAtPosition(position))
                        if (clicked) break
                    }
                }
            }
        }
        return clicked
    }
}
