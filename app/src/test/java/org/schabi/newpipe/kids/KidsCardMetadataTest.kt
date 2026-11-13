/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KidsCardMetadataTest {
    @Test fun `playlist count and cover use only visible videos including repeated entries`() {
        val hidden = VideoIdentity.youtube("dQw4w9WgXcQ")
        val allowed = VideoIdentity.youtube("e_04ZrNroTo")
        val missing = VideoIdentity.youtube("jNQXAC9IVRw")
        val source = StoredSource(SourceKind.PLAYLIST, StoredContentId(0, "list"), videos = listOf(hidden, allowed, missing, allowed))
        val profile = ChildProfile("a", "Maya", sessionLimitMinutes = 30, remainingMillis = 1_000, sources = listOf(source), hiddenVideos = setOf(hidden))
        val catalog = listOf(
            ParentSearchItem(ParentContentKind.VIDEO, hidden, "Hidden", "https://youtu.be/dQw4w9WgXcQ", "https://example.org/hidden.jpg"),
            ParentSearchItem(ParentContentKind.VIDEO, allowed, "Allowed", "https://youtu.be/e_04ZrNroTo", "https://example.org/allowed.jpg")
        ).associateBy { it.id }
        val result = KidsCardMetadata.playlistPreview(source, profile.approvedLibrary(), catalog)
        assertEquals(2, result.visibleCount)
        assertEquals("https://example.org/allowed.jpg", result.thumbnailUrl)
        assertNull(KidsCardMetadata.playlistPreview(source.copy(videos = emptyList()), profile.approvedLibrary(), catalog).visibleCount)
        assertNull(KidsCardMetadata.playlistPreview(source.copy(enabled = false), profile.approvedLibrary(), catalog).thumbnailUrl)
    }

    @Test fun `formats a known duration without inventing missing values`() {
        assertEquals("0:59", KidsCardMetadata.duration(59))
        assertEquals("9:59", KidsCardMetadata.duration(599))
        assertEquals("1:00:01", KidsCardMetadata.duration(3_601))
        assertNull(KidsCardMetadata.duration(null))
        assertNull(KidsCardMetadata.duration(-1))
        assertNull(KidsCardMetadata.duration(0))
    }

    @Test fun `playlist preview hides identified shorts when requested`() {
        val regular = VideoIdentity.youtube("e_04ZrNroTo")
        val short = VideoIdentity.youtube("dQw4w9WgXcQ")
        val playlist = StoredSource(SourceKind.PLAYLIST, StoredContentId(0, "list"), videos = listOf(short, regular))
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, sources = listOf(playlist))
        val catalog = listOf(
            ParentSearchItem(ParentContentKind.VIDEO, short, "Short", "https://youtu.be/${short.id}", thumbnailUrl = "short.jpg", isShortForm = true),
            ParentSearchItem(ParentContentKind.VIDEO, regular, "Regular", "https://youtu.be/${regular.id}", thumbnailUrl = "regular.jpg")
        ).associateBy { it.id }
        val preview = KidsCardMetadata.playlistPreview(playlist, profile.approvedLibrary(), catalog, blockedShorts = setOf(short))
        assertEquals(1, preview.visibleCount)
        assertEquals("regular.jpg", preview.thumbnailUrl)
    }
}
