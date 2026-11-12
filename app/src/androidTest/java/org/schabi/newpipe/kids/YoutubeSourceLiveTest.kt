/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList

/** Opt in with -e live true; this test requires network and an upstream public playlist. */
@RunWith(AndroidJUnit4::class)
class YoutubeSourceLiveTest {
    @Test fun fetchPublicPlaylist() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val playlist = StoredContentId(
            ServiceList.YouTube.serviceId,
            "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8"
        )
        val videos = ExtractorSourceFetcher().fetchAll(SourceKind.PLAYLIST, playlist)
        assertTrue(videos.isNotEmpty())
        assertTrue(videos.all { it.id == VideoIdentity.canonicalize(it.id) })
    }

    @Test fun fetchPublicChannel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val channel = StoredContentId(
            ServiceList.YouTube.serviceId,
            "https://www.youtube.com/channel/UCTwECeGqMZee77BjdoYtI2Q"
        )
        val videos = ExtractorSourceFetcher().fetchAll(SourceKind.CHANNEL, channel)
        assertTrue(videos.isNotEmpty())
        assertTrue(videos.all { it.id == VideoIdentity.canonicalize(it.id) })
    }

    @Test fun searchForVideosAsParent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val page = ParentContentSearch.firstPage("cocomelon")
        assertTrue(page.items.any { it.kind == ParentContentKind.VIDEO })
    }

    @Test fun searchForChannelsAndPlaylistsAsParent() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        fun find(query: String, kind: ParentContentKind): Boolean {
            var page = ParentContentSearch.firstPage(query)
            repeat(4) {
                if (page.items.any { it.kind == kind }) return true
                if (page.next == null) return false
                page = ParentContentSearch.nextPage(page)
            }
            return false
        }
        assertTrue("No channel was found", find("Creative Commons", ParentContentKind.CHANNEL))
        assertTrue("No playlist was found", find("Crash Course ecology playlist", ParentContentKind.PLAYLIST))
    }
}
