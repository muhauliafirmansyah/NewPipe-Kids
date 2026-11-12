/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import org.schabi.newpipe.R

/** Shared visual language for the child and parent surfaces. */
internal object KidsUi {
    val ink = Color.rgb(30, 35, 45)
    val muted = Color.rgb(88, 101, 114)
    val purple = Color.rgb(104, 68, 214)
    val purpleLight = Color.rgb(238, 233, 255)
    val sky = Color.rgb(226, 250, 252)
    val white = Color.WHITE
    val border = Color.rgb(222, 229, 235)
    val coral = Color.rgb(244, 91, 95)

    enum class ButtonStyle { PRIMARY, SURFACE, SOFT, GHOST, SELECTED }

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun shape(context: Context, color: Int, radius: Int = 16, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(context, radius).toFloat()
        if (stroke != null) setStroke(dp(context, 1), stroke)
    }

    fun background(): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Color.rgb(249, 254, 255), Color.rgb(199, 246, 250), Color.rgb(74, 211, 225))
    )

    fun ripple(context: Context, color: Int, radius: Int = 16, stroke: Int? = null): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(0x22000000),
        shape(context, color, radius, stroke),
        shape(context, Color.WHITE, radius)
    )

    fun button(context: Context, text: String, style: ButtonStyle = ButtonStyle.SURFACE): Button = AppCompatButton(context).apply {
        this.text = text
        isAllCaps = false
        textSize = 14f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        minimumHeight = dp(context, 50)
        minHeight = 0
        minimumWidth = 0
        setPadding(dp(context, 16), dp(context, 8), dp(context, 16), dp(context, 8))
        backgroundTintList = null
        stateListAnimator = null
        val color = when (style) {
            ButtonStyle.PRIMARY, ButtonStyle.SELECTED -> ink
            ButtonStyle.SURFACE -> white
            ButtonStyle.SOFT -> purpleLight
            ButtonStyle.GHOST -> Color.TRANSPARENT
        }
        background = ripple(context, color, 16, if (style == ButtonStyle.SURFACE) border else null)
        setTextColor(if (style == ButtonStyle.PRIMARY || style == ButtonStyle.SELECTED) white else ink)
    }

    fun text(context: Context, value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        includeFontPadding = false
    }

    fun input(view: EditText) {
        val context = view.context
        view.backgroundTintList = null
        view.background = shape(context, white, 16, border)
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
        view.setTextColor(ink)
        view.setHintTextColor(muted)
        view.textSize = 16f
        view.minimumHeight = dp(context, 54)
    }

    fun icon(button: Button, drawableId: Int, color: Int, top: Boolean = false, size: Int = 24) {
        val drawable = ContextCompat.getDrawable(button.context, drawableId)?.mutate() ?: return
        drawable.setTint(color)
        drawable.setBounds(0, 0, dp(button.context, size), dp(button.context, size))
        if (top) button.setCompoundDrawables(null, drawable, null, null) else button.setCompoundDrawablesRelative(drawable, null, null, null)
        button.compoundDrawablePadding = dp(button.context, if (top) 3 else 8)
    }

    fun profileColor(key: String): Int {
        val colors = intArrayOf(purple, 0xFFE46A59.toInt(), 0xFF158D9F.toInt(), 0xFFE09A30.toInt())
        return colors[(key.hashCode() and Int.MAX_VALUE) % colors.size]
    }

    fun profileIcon(button: Button, key: String, top: Boolean = false) {
        val context = button.context
        val person = ContextCompat.getDrawable(context, R.drawable.ic_person)?.mutate() ?: return
        person.setTint(Color.WHITE)
        val drawable = LayerDrawable(arrayOf(shape(context, profileColor(key), 100), person)).apply {
            val inset = dp(context, 5)
            setLayerInset(1, inset, inset, inset, inset)
            setBounds(0, 0, dp(context, 28), dp(context, 28))
        }
        if (top) button.setCompoundDrawables(null, drawable, null, null) else button.setCompoundDrawablesRelative(drawable, null, null, null)
        button.compoundDrawablePadding = dp(context, if (top) 2 else 8)
    }

    fun logo(context: Context): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val mark = TextView(context).apply {
            text = "▶"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(white)
            background = shape(context, purple, 12)
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(mark, LinearLayout.LayoutParams(dp(context, 38), dp(context, 34)))
        val name = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 9), 0, 0, 0)
            addView(text(context, context.getString(R.string.kids_brand_small), 9f, muted, true).apply { letterSpacing = 0.08f })
            addView(text(context, context.getString(R.string.kids_brand_title), 21f, ink, true))
        }
        addView(name)
        contentDescription = context.getString(R.string.kids_brand_accessibility)
    }
}
