/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

/** Responsive browsing grid, measured in density-independent pixels. */
internal object KidsGridLayout {
    const val HORIZONTAL_PADDING = 16
    const val GAP = 12
    const val IMAGE_INSET = 8
    private const val MIN_CARD_WIDTH = 190
    private const val MAX_PHONE_LANDSCAPE_CARD_WIDTH = 240
    private const val TABLET_SMALLEST_WIDTH = 600

    fun columns(width: Int, smallestWidth: Int): Int {
        val available = (width - 2 * HORIZONTAL_PADDING).coerceAtLeast(1)
        val fitting = ((available + GAP) / (MIN_CARD_WIDTH + GAP)).coerceAtLeast(1)
        return if (smallestWidth in 1 until TABLET_SMALLEST_WIDTH) fitting.coerceAtMost(3) else fitting
    }

    fun extraSideInset(width: Int, smallestWidth: Int, columns: Int): Int {
        if (smallestWidth !in 1 until TABLET_SMALLEST_WIDTH || width <= smallestWidth || columns < 3) return 0
        val available = width - 2 * HORIZONTAL_PADDING - (columns - 1) * GAP
        return ((available - columns * MAX_PHONE_LANDSCAPE_CARD_WIDTH) / 2).coerceAtLeast(0)
    }

    fun thumbnailHeight(width: Int, columns: Int, extraSideInset: Int = 0): Int {
        require(columns > 0 && extraSideInset >= 0)
        val available = (width - 2 * HORIZONTAL_PADDING - 2 * extraSideInset - (columns - 1) * GAP).coerceAtLeast(columns)
        val imageWidth = (available / columns - 2 * IMAGE_INSET).coerceAtLeast(1)
        return (imageWidth * 9 / 16).coerceAtLeast(1)
    }
}
