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

class ChannelPlaylistRefreshCoordinatorTest {
    private val channel = StoredContentId(0, "channel")
    private val firstId = SourceIdentity.fromUrl(0, SourceKind.PLAYLIST, "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8")
    private val secondId = SourceIdentity.fromUrl(0, SourceKind.PLAYLIST, "https://www.youtube.com/playlist?list=PLBCF2DAC6FFB574DE")
    private val a = VideoIdentity.youtube("e_04ZrNroTo")
    private val b = VideoIdentity.youtube("dQw4w9WgXcQ")
    private fun playlist(id: StoredContentId) = ParentSearchItem(ParentContentKind.PLAYLIST, id, "Playlist", id.id)
    private fun video(id: StoredContentId) = ParentSearchItem(ParentContentKind.VIDEO, id, "Video ${id.id}", "https://youtu.be/${id.id}")

    private fun storage(): KidsStorage {
        val source = StoredSource(SourceKind.CHANNEL, channel, videos = listOf(a), channelPlaylists = listOf(StoredChannelPlaylist(firstId, listOf(a))))
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 5, remainingMillis = 300_000, sources = listOf(source))
        val other = ChildProfile("b", "B", sessionLimitMinutes = 5, remainingMillis = 300_000)
        val preferences = mock(SharedPreferences::class.java)
        val editor = mock(SharedPreferences.Editor::class.java)
        var saved: String? = KidsStateCodec.encode(KidsState(profiles = listOf(profile, other)))
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

    @Test fun `discovers playlists without approving external videos and keeps a failed snapshot`() {
        val store = storage()
        val fetcher = object : ChannelPlaylistFetcher {
            override fun playlists(channel: StoredContentId) = listOf(playlist(firstId), playlist(secondId), playlist(firstId))
            override fun videos(playlist: StoredContentId): List<ParentSearchItem> {
                if (playlist == firstId) throw IllegalStateException("offline")
                return listOf(video(a), video(b))
            }
        }
        var discovered = false
        val state = ChannelPlaylistRefreshCoordinator(store, fetcher) {
            discovered = store.read().profiles.first().sources.single().channelPlaylists.map { it.id } == listOf(firstId, secondId)
        }.refresh(setOf("a"), channel)
        assertTrue(discovered)
        val profile = state.profiles.first { it.id == "a" }
        assertEquals(listOf(firstId, secondId), profile.sources.single().channelPlaylists.map { it.id })
        assertEquals(listOf(a), profile.sources.single().channelPlaylists.first().videos)
        assertEquals(listOf(a, b), profile.sources.single().channelPlaylists.last().videos)
        assertEquals(listOf(a), ChildLibrary.sources(profile).single { it.id == secondId }.videos)
        assertEquals(listOf(a), ChildLibrary.videos(state, "a").map { it.id })
        assertTrue(ChildLibrary.sources(state.profiles.first { it.id == "b" }).isEmpty())
        assertEquals(null, profile.sources.single().activePlaylistRefreshToken)
    }

    @Test fun `failed discovery preserves the last playlist snapshot`() {
        val store = storage()
        val fetcher = object : ChannelPlaylistFetcher {
            override fun playlists(channel: StoredContentId): List<ParentSearchItem> = throw IllegalStateException("offline")
            override fun videos(playlist: StoredContentId) = emptyList<ParentSearchItem>()
        }
        try {
            ChannelPlaylistRefreshCoordinator(store, fetcher).refresh(setOf("a"), channel)
            throw AssertionError("Expected a failed refresh")
        } catch (_: IllegalStateException) {
            assertEquals(listOf(StoredChannelPlaylist(firstId, listOf(a))), store.read().profiles.first().sources.single().channelPlaylists)
        }
    }

    @Test fun `stale discovery cannot replace a newer snapshot`() {
        val store = storage()
        val newer = object : ChannelPlaylistFetcher {
            override fun playlists(channel: StoredContentId) = listOf(playlist(secondId))
            override fun videos(playlist: StoredContentId) = listOf(video(a))
        }
        val older = object : ChannelPlaylistFetcher {
            override fun playlists(channel: StoredContentId): List<ParentSearchItem> {
                ChannelPlaylistRefreshCoordinator(store, newer).refresh(setOf("a"), channel)
                return listOf(playlist(firstId))
            }
            override fun videos(playlist: StoredContentId) = listOf(video(a))
        }
        try {
            ChannelPlaylistRefreshCoordinator(store, older).refresh(setOf("a"), channel)
            throw AssertionError("Expected stale refresh rejection")
        } catch (_: IllegalArgumentException) {
            assertEquals(listOf(secondId), store.read().profiles.first().sources.single().channelPlaylists.map { it.id })
        }
    }
}
