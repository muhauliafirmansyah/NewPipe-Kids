/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Test
import org.schabi.newpipe.extractor.Page

class ExtractorSourceFetcherTest {
    private fun item(id: String): ParentSearchItem = ParentSearchItem(
        ParentContentKind.VIDEO,
        VideoIdentity.youtube(id),
        "Video",
        "https://www.youtube.com/watch?v=$id"
    )

    @Test fun `collects every page in order including repeated playlist items`() {
        val a = item("e_04ZrNroTo")
        val b = item("dQw4w9WgXcQ")
        val results = collectSourcePages(SourcePage(listOf(a), Page("next"))) {
            SourcePage(listOf(b, a), null)
        }
        assertEquals(listOf(a, b, a), results)
    }

    @Test(expected = IllegalStateException::class)
    fun `an incomplete page fails instead of returning a partial snapshot`() {
        val a = item("e_04ZrNroTo")
        collectSourcePages(SourcePage(listOf(a), Page("next"))) { throw IllegalStateException("network") }
    }

    @Test(expected = IllegalStateException::class)
    fun `pagination loops fail rather than returning incomplete results`() {
        val page = Page("loop")
        collectSourcePages(SourcePage(emptyList(), page)) { SourcePage(emptyList(), page) }
    }
}
