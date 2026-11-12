/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KidsGridLayoutTest {
    @Test fun `phone uses one portrait column and three landscape columns`() {
        assertEquals(1, KidsGridLayout.columns(320, 320))
        assertEquals(1, KidsGridLayout.columns(393, 393))
        assertEquals(3, KidsGridLayout.columns(640, 320))
        assertEquals(3, KidsGridLayout.columns(852, 393))
    }

    @Test fun `tablet portrait and landscape can show more columns`() {
        assertEquals(2, KidsGridLayout.columns(600, 600))
        assertEquals(3, KidsGridLayout.columns(800, 800))
        assertEquals(5, KidsGridLayout.columns(1200, 800))
        assertEquals(7, KidsGridLayout.columns(1600, 1200))
    }

    @Test fun `thumbnails keep their wide aspect ratio at different widths`() {
        listOf(Triple(320, 320, 1), Triple(640, 320, 3), Triple(852, 393, 3), Triple(1200, 800, 5)).forEach { (width, smallest, columns) ->
            val inset = KidsGridLayout.extraSideInset(width, smallest, columns)
            val imageWidth = (width - 32 - 2 * inset - (columns - 1) * 12) / columns - 16
            val height = KidsGridLayout.thumbnailHeight(width, columns, inset)
            assertTrue(kotlin.math.abs(height * 16 - imageWidth * 9) < 16)
        }
        assertTrue(KidsGridLayout.extraSideInset(852, 393, 3) > 0)
        assertEquals(0, KidsGridLayout.extraSideInset(393, 393, 1))
        assertEquals(0, KidsGridLayout.extraSideInset(1200, 800, 5))
    }
}
