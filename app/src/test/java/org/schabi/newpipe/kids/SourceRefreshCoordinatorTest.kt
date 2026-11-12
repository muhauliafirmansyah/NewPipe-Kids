/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class SourceRefreshCoordinatorTest {
    private val source = StoredContentId(0, "playlist")
    private val oldVideo = VideoIdentity.youtube("e_04ZrNroTo")
    private val newVideo = VideoIdentity.youtube("dQw4w9WgXcQ")

    private fun item(id: StoredContentId): ParentSearchItem = ParentSearchItem(
        ParentContentKind.VIDEO,
        id,
        "Video",
        "https://www.youtube.com/watch?v=${id.id}"
    )

    private fun storage(initial: KidsState): KidsStorage {
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        var saved: String? = KidsStateCodec.encode(initial)
        var pending: String? = null
        `when`(preferences.getString(anyString(), org.mockito.ArgumentMatchers.isNull())).thenAnswer { saved }
        `when`(preferences.edit()).thenReturn(editor)
        `when`(editor.putString(anyString(), anyString())).thenAnswer {
            pending = it.getArgument(1)
            editor
        }
        `when`(editor.commit()).thenAnswer {
            saved = pending
            true
        }
        return KidsStorage(preferences)
    }

    private fun withSource(): KidsState {
        val initial = ProfileManager.add(KidsState(), "a", "A", 5)
        val approved = ApprovalManager.approveSource(initial, setOf("a"), SourceKind.PLAYLIST, source)
        val started = ApprovalManager.beginSourceRefresh(approved, setOf("a"), SourceKind.PLAYLIST, source)
        return ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(oldVideo))
    }

    @Test fun `fetch failure keeps the last successful snapshot`() {
        val store = storage(withSource())
        val refresher = SourceRefreshCoordinator(store) { _, _ -> throw IllegalStateException("network") }
        try {
            refresher.refresh(setOf("a"), SourceKind.PLAYLIST, source)
            throw AssertionError("Expected fetch failure")
        } catch (_: IllegalStateException) {
            assertEquals(listOf(oldVideo), store.read().profiles.single().sources.single().videos)
        }
    }

    @Test fun `a late refresh cannot replace a newer snapshot`() {
        val store = storage(withSource())
        val newer = SourceRefreshCoordinator(store) { _, _ -> listOf(item(newVideo)) }
        val older = SourceRefreshCoordinator(store) { _, _ ->
            newer.refresh(setOf("a"), SourceKind.PLAYLIST, source)
            listOf(item(oldVideo))
        }
        try {
            older.refresh(setOf("a"), SourceKind.PLAYLIST, source)
            throw AssertionError("Expected stale refresh to be rejected")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(newVideo), store.read().profiles.single().sources.single().videos)
        }
    }

    @Test fun `local hide is preserved while a refresh runs`() {
        val store = storage(withSource())
        val refresher = SourceRefreshCoordinator(store) { _, _ ->
            store.update { ApprovalManager.setVideoHidden(it, setOf("a"), newVideo, true) }
            listOf(item(newVideo))
        }
        val updated = refresher.refresh(setOf("a"), SourceKind.PLAYLIST, source)
        assertTrue(updated.profiles.single().hiddenVideos.contains(newVideo))
        assertTrue(updated.profiles.single().approvedLibrary().playableVideos().isEmpty())
    }

    @Test fun `refresh all groups profiles and a failed source does not block another`() {
        val broken = StoredContentId(0, "broken")
        var initial = ProfileManager.add(withSource(), "b", "B", 5)
        initial = ApprovalManager.approveSource(initial, setOf("a", "b"), SourceKind.CHANNEL, broken)
        initial = ApprovalManager.approveSource(initial, setOf("b"), SourceKind.PLAYLIST, source)
        val store = storage(initial)
        val calls = mutableListOf<StoredContentId>()
        val refreshed = ApprovedSourceRefresher(store) { _, id ->
            calls.add(id)
            if (id == broken) throw IllegalStateException("offline")
            listOf(item(newVideo))
        }.refreshAll()
        assertEquals(2, calls.size)
        assertEquals(listOf(newVideo), refreshed.profiles.first { it.id == "a" }.sources.first { it.id == source }.videos)
        assertEquals(listOf(newVideo), refreshed.profiles.first { it.id == "b" }.sources.first { it.id == source }.videos)
        assertTrue(refreshed.profiles.all { it.sources.first { source -> source.id == broken }.videos.isEmpty() })
    }
}
