/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import org.schabi.newpipe.R

/** Focus feedback for the same screens when they are operated with a TV remote. */
internal object KidsTvNavigation {
    fun isTv(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    fun install(activity: Activity) {
        if (!isTv(activity)) return
        val decor = activity.window.decorView
        var outlined: View? = null
        var outline: LayerDrawable? = null
        val resize = View.OnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view === outlined) outline?.setBounds(0, 0, view.width, view.height)
        }
        decor.viewTreeObserver.addOnGlobalFocusChangeListener { _, focused ->
            outlined?.let { previous ->
                outline?.let { previous.overlay.remove(it) }
                previous.removeOnLayoutChangeListener(resize)
            }
            outlined = null
            outline = null
            if (focused != null && focused !is ScrollView && focused !is HorizontalScrollView && focused.id != R.id.kids_player_view) {
                val density = focused.resources.displayMetrics.density
                fun stroke(color: Int, width: Int) = GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    cornerRadius = 12 * density
                    setStroke((width * density).toInt().coerceAtLeast(1), color)
                }
                val marker = LayerDrawable(arrayOf(stroke(Color.BLACK, 5), stroke(Color.WHITE, 2))).apply {
                    setLayerInset(1, (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
                    setBounds(0, 0, focused.width, focused.height)
                }
                outlined = focused
                outline = marker
                focused.overlay.add(marker)
                focused.addOnLayoutChangeListener(resize)
            }
        }
    }

    fun requestFocus(view: View?) {
        if (view == null || !isTv(view.context)) return
        view.post {
            if (view.isAttachedToWindow && view.isShown) view.requestFocus()
        }
    }

    /** A single-line editor must not trap Up/Down after the TV keyboard is dismissed. */
    fun moveFromInput(activity: Activity, event: KeyEvent): Boolean {
        if (!isTv(activity) || event.action != KeyEvent.ACTION_DOWN) return false
        val input = activity.currentFocus as? EditText ?: return false
        if (input.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) return false
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> View.FOCUS_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> View.FOCUS_DOWN
            else -> return false
        }
        val next = input.focusSearch(direction) ?: return false
        return next !== input && next.isShown && next.requestFocus()
    }

    data class FocusKey(val id: Int, val tag: Any?)

    fun remember(view: View?): FocusKey? {
        if (view == null || !isTv(view.context)) return null
        if (view.id == View.NO_ID && view.tag == null) return null
        return FocusKey(view.id, view.tag)
    }

    fun restore(root: View, key: FocusKey?, fallback: View?) {
        if (!isTv(root.context)) return
        root.post {
            val target = key?.let {
                (it.tag?.let { tag -> root.findViewWithTag<View>(tag) })
                    ?: (if (it.id != View.NO_ID) root.findViewById<View>(it.id) else null)
            }
            val candidate = target?.takeIf { it.isShown && it.isFocusable } ?: fallback
            if (candidate?.isAttachedToWindow == true && candidate.isShown) candidate.requestFocus()
        }
    }
}
