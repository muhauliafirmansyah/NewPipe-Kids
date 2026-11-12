/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import org.schabi.newpipe.R

/** Uses the device's emoji artwork; stored identifiers are independent of the displayed glyph. */
internal object KidsAvatars {
    data class Option(val id: String, val glyph: String, val color: Int, val label: Int)

    val options = listOf(
        Option("initial", "", 0xFF286BC5.toInt(), R.string.kids_avatar_initial),
        Option("fox", "🦊", 0xFFB37AF4.toInt(), R.string.kids_avatar_fox),
        Option("cat", "🐱", 0xFFFFAB6B.toInt(), R.string.kids_avatar_cat),
        Option("panda", "🐼", 0xFFFB7D96.toInt(), R.string.kids_avatar_panda),
        Option("frog", "🐸", 0xFF27BFA3.toInt(), R.string.kids_avatar_frog),
        Option("rabbit", "🐰", 0xFF70BCE4.toInt(), R.string.kids_avatar_rabbit),
        Option("bear", "🐻", 0xFFFFC45E.toInt(), R.string.kids_avatar_bear),
        Option("unicorn", "🦄", 0xFFCF87E7.toInt(), R.string.kids_avatar_unicorn),
        Option("rocket", "🚀", 0xFF6579DB.toInt(), R.string.kids_avatar_rocket)
    )

    fun view(context: Context, profile: ChildProfile, size: Int, selected: Boolean = false): TextView {
        val option = options.firstOrNull { it.id == profile.avatarId } ?: options.first()
        val name = profile.name.trim()
        val initial = if (name.isEmpty()) "?" else name.substring(0, name.offsetByCodePoints(0, 1)).uppercase(Locale.ROOT)
        return TextView(context).apply {
            text = option.glyph.ifEmpty { initial }
            textSize = size * if (option.glyph.isEmpty()) 0.48f else 0.56f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = KidsUi.shape(context, option.color, 100).apply {
                if (selected) setStroke(KidsUi.dp(context, if (size >= 100) 5 else 3), Color.WHITE)
            }
            contentDescription = profile.name
        }
    }

    fun icon(button: Button, profile: ChildProfile, size: Int = 28) {
        val pixels = KidsUi.dp(button.context, size)
        val avatar = view(button.context, profile, size)
        val spec = View.MeasureSpec.makeMeasureSpec(pixels, View.MeasureSpec.EXACTLY)
        avatar.measure(spec, spec)
        avatar.layout(0, 0, pixels, pixels)
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        avatar.draw(Canvas(bitmap))
        val drawable = BitmapDrawable(button.resources, bitmap).apply { setBounds(0, 0, pixels, pixels) }
        button.setCompoundDrawables(null, drawable, null, null)
        button.compoundDrawablePadding = KidsUi.dp(button.context, 2)
    }
}
