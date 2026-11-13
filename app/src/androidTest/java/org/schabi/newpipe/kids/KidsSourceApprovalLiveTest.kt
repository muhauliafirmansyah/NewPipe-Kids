/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Opt in with -e live true; approves and refreshes real public YouTube sources. */
@RunWith(AndroidJUnit4::class)
class KidsSourceApprovalLiveTest {
    @Test fun realChannelShortsCarryTheExtractorShortsLabel() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val service = ServiceList.YouTube
        val channel = ChannelInfo.getInfo(service, "https://www.youtube.com/channel/UCX6OQ3DkcsbYNE6H8uQQuVA")
        val tab = channel.tabs.firstOrNull { ChannelTabs.SHORTS in it.contentFilters }
        assertTrue("The channel has no available Shorts tab", tab != null)
        val streams = ChannelTabInfo.getInfo(service, requireNotNull(tab)).relatedItems.filterIsInstance<StreamInfoItem>()
        assertTrue("The channel returned no Shorts", streams.isNotEmpty())
        val labelled = streams.firstOrNull { it.isShortFormContent }
        assertTrue("The extractor did not label Shorts in this channel", labelled != null)
        assertTrue(ParentContentSearch.toItem(requireNotNull(labelled))?.isShortForm == true)
    }

    @Test fun realChannelPlaylistTabCanBeDiscoveredAndOpened() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val channel = SourceIdentity.fromUrl(ServiceList.YouTube.serviceId, SourceKind.CHANNEL, "https://www.youtube.com/channel/UCsXVk37bltHxD1rDPwtNM8Q")
        val fetcher = ExtractorChannelPlaylistFetcher()
        val playlists = fetcher.playlists(channel)
        assertTrue("The channel did not return any playlists", playlists.isNotEmpty())
        val readable = playlists.take(5).firstOrNull { runCatching { fetcher.videos(it.id).isNotEmpty() }.getOrDefault(false) }
        assertTrue("The channel's playlists could not be opened", readable != null)
    }

    @Test fun approvedChannelAndPlaylistPopulateTheChildLibrary() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "test", "Test child", 10)
            val service = ServiceList.YouTube
            val sources = listOf(
                Triple(ParentContentKind.PLAYLIST, SourceKind.PLAYLIST, service.playlistLHFactory.fromUrl("https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8").url),
                Triple(ParentContentKind.CHANNEL, SourceKind.CHANNEL, service.channelLHFactory.fromUrl("https://www.youtube.com/channel/UCTwECeGqMZee77BjdoYtI2Q").url)
            )
            sources.forEach { (kind, sourceKind, url) ->
                val id = StoredContentId(service.serviceId, url)
                parent.approveSearchItem("1234", setOf("test"), ParentSearchItem(kind, id, "Source", url))
                val state = SourceRefreshCoordinator(storage, ExtractorSourceFetcher()).refresh(setOf("test"), sourceKind, id)
                val source = state.profiles.single().sources.first { it.id == id }
                assertTrue(source.videos.isNotEmpty())
                val video = source.videos.first { it !in state.profiles.single().hiddenVideos }
                assertTrue(state.profiles.single().approvedLibrary().canPlay(video.toContentId()))
                assertTrue(ChildLibrary.videos(state, "test").any { it.id == video })
                parent.setVideoHidden("1234", setOf("test"), video, true)
                assertFalse(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
            }
        } finally {
            storage.update { original }
        }
    }
}
