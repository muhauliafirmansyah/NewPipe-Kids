/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChildLibraryTest {
    private val a = VideoIdentity.youtube("e_04ZrNroTo")
    private val b = VideoIdentity.youtube("dQw4w9WgXcQ")
    private val c = VideoIdentity.youtube("abcdefghijk")
    private fun item(id: StoredContentId, name: String) = ParentSearchItem(
        ParentContentKind.VIDEO,
        id,
        name,
        "https://www.youtube.com/watch?v=${id.id}"
    )

    @Test fun `search never returns videos outside the active profiles approved library`() {
        val first = ChildProfile("a", "A", sessionLimitMinutes = 5, remainingMillis = 1, approvedVideos = setOf(a, b), hiddenVideos = setOf(b))
        val second = ChildProfile("b", "B", sessionLimitMinutes = 5, remainingMillis = 1, approvedVideos = setOf(c))
        val state = KidsState(profiles = listOf(first, second), catalog = listOf(item(a, "Bus song"), item(b, "Bus hidden"), item(c, "Bus other")))
        assertEquals(listOf(a), ChildLibrary.videos(state, "a", "BUS").map { it.id })
        assertEquals(listOf(c), ChildLibrary.videos(state, "b", "bus").map { it.id })
        assertTrue(ChildLibrary.videos(state, "a", "other").isEmpty())
    }

    @Test fun `disabled missing or expired profiles expose no catalog`() {
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, approvedVideos = setOf(a))
        val state = KidsState(profiles = listOf(profile), catalog = listOf(item(a, "Song")))
        assertTrue(ChildLibrary.videos(state, "missing").isEmpty())
        assertTrue(ChildLibrary.videos(state.copy(profiles = listOf(profile.copy(enabled = false))), "a").isEmpty())
        assertTrue(ChildLibrary.videos(state.copy(profiles = listOf(profile.copy(remainingMillis = 0))), "a").isEmpty())
    }

    @Test fun `cached channel videos are visible only while their source is approved`() {
        val source = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "channel"), videos = listOf(a))
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, sources = listOf(source))
        val state = KidsState(profiles = listOf(profile), catalog = listOf(item(a, "Song")))
        assertEquals(listOf(a), ChildLibrary.videos(state, "a").map { it.id })
        assertTrue(ChildLibrary.videos(state.copy(profiles = listOf(profile.copy(sources = emptyList()))), "a").isEmpty())
    }

    @Test fun `videos from overlapping sources and direct approvals appear only once`() {
        val playlist = StoredSource(SourceKind.PLAYLIST, StoredContentId(0, "playlist"), videos = listOf(a, b, a, c))
        val channel = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "channel"), videos = listOf(a, b))
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, approvedVideos = setOf(a), sources = listOf(channel, playlist), hiddenVideos = setOf(c))
        val other = ChildProfile("b", "B", sessionLimitMinutes = 1, remainingMillis = 1, approvedVideos = setOf(c))
        val state = KidsState(profiles = listOf(profile, other), catalog = listOf(item(a, "A"), item(b, "B"), item(c, "C")))
        assertEquals(listOf(a, b), ChildLibrary.videos(state, "a").map { it.id })
        assertEquals(listOf(c), ChildLibrary.videos(state, "b").map { it.id })
        assertEquals(listOf(b), ChildLibrary.videos(state, "a", "b").map { it.id })
        val noPlaylist = profile.copy(sources = listOf(channel, playlist.copy(enabled = false)))
        assertEquals(listOf(a, b), ChildLibrary.videos(state.copy(profiles = listOf(noPlaylist, other)), "a").map { it.id })
    }

    @Test fun `legacy aliases in the catalog cannot create duplicate cards`() {
        val alias = StoredContentId(0, "https://youtu.be/${a.id}")
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, approvedVideos = setOf(a))
        val state = KidsState(profiles = listOf(profile), catalog = listOf(item(a, "Song"), item(a, "Old song").copy(id = alias)))
        assertEquals(listOf(a), ChildLibrary.videos(state, "a").map { it.id })
        assertTrue(ChildLibrary.videos(state.copy(profiles = listOf(profile.copy(hiddenVideos = setOf(alias)))), "a").isEmpty())
    }

    @Test fun `shorts preference filters lists and playback for only the selected profile`() {
        val channel = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "channel"), videos = listOf(a, b))
        val first = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, sources = listOf(channel), showShorts = false)
        val second = ChildProfile("b", "B", sessionLimitMinutes = 1, remainingMillis = 1, sources = listOf(channel))
        val regular = item(a, "Regular video")
        val short = item(b, "Short video").copy(isShortForm = true)
        val state = KidsState(profiles = listOf(first, second), selectedProfileId = "a", catalog = listOf(regular, short))
        assertEquals(listOf(a), ChildLibrary.videos(state, "a").map { it.id })
        assertEquals(listOf(a, b), ChildLibrary.videos(state, "b").map { it.id })
        assertTrue(!ChildPlaybackAccess.allowed(state, "a", b))
        assertTrue(ChildPlaybackAccess.allowed(state, "a", a))
        assertTrue(ChildPlaybackAccess.allowed(state.copy(selectedProfileId = "b"), "b", b))
        assertEquals(setOf(b), ChildLibrary.hiddenShorts(state, first))
    }

    @Test fun `shorts labels survive duplicate entries and later unlabeled refreshes`() {
        val short = item(a, "Short").copy(isShortForm = true)
        val plain = item(a, "Same video")
        assertTrue(KidsCatalog.merge(emptyList(), listOf(short, plain)).single().isShortForm)
        assertTrue(KidsCatalog.merge(listOf(short), listOf(plain)).single().isShortForm)
        assertTrue(KidsCatalog.merge(listOf(plain), listOf(short)).single().isShortForm)
    }

    @Test fun `channel playlists show only their channel videos until explicitly approved`() {
        val id = StoredContentId(0, "playlist")
        val channel = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "channel"), videos = listOf(a, b), channelPlaylists = listOf(StoredChannelPlaylist(id, listOf(b, c, a))))
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, approvedVideos = setOf(c), sources = listOf(channel))
        val inherited = ChildLibrary.sources(profile).single { it.kind == SourceKind.PLAYLIST }
        assertEquals(listOf(b, a), inherited.videos)
        val hidden = profile.copy(hiddenChannelPlaylists = setOf(id))
        assertTrue(ChildLibrary.sources(hidden).none { it.id == id })
        assertEquals(listOf(b, a), ChildLibrary.sources(hidden, includeHiddenChannelPlaylists = true).single { it.id == id }.videos)
        val direct = StoredSource(SourceKind.PLAYLIST, id, videos = listOf(b, c, a))
        assertEquals(listOf(direct), ChildLibrary.sources(hidden.copy(sources = listOf(channel, direct))).filter { it.kind == SourceKind.PLAYLIST })
        assertEquals(listOf(direct), ChildLibrary.sources(profile.copy(sources = listOf(channel, direct))).filter { it.kind == SourceKind.PLAYLIST })
        assertTrue(ChildLibrary.sources(profile.copy(sources = listOf(channel, direct.copy(enabled = false)))).none { it.kind == SourceKind.PLAYLIST })
        assertTrue(ChildLibrary.sources(profile.copy(sources = listOf(channel.copy(enabled = false)))).isEmpty())
    }

    @Test fun `a playlist inherited from multiple channels appears once and includes their videos`() {
        val id = StoredContentId(0, "playlist")
        val listing = StoredChannelPlaylist(id, listOf(a, b, c))
        val first = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "first"), videos = listOf(a), channelPlaylists = listOf(listing))
        val second = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "second"), videos = listOf(b), channelPlaylists = listOf(listing))
        val profile = ChildProfile("a", "A", sessionLimitMinutes = 1, remainingMillis = 1, sources = listOf(first, second))
        assertEquals(listOf(a, b), ChildLibrary.sources(profile).single { it.kind == SourceKind.PLAYLIST }.videos)
    }
}
