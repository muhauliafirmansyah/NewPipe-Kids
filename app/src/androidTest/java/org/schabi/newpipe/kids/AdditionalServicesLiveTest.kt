/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ui.PlayerView
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.stream.StreamInfo

/** Opt in with -e live true; verifies discovery, approval, and playback on public services. */
@RunWith(AndroidJUnit4::class)
class AdditionalServicesLiveTest {
    @Test fun peerTubeSearchApprovalAndPlayback() = verifyService(ServiceList.PeerTube, "animation")

    @Test fun mediaCccSearchApprovalAndPlayback() = verifyService(ServiceList.MediaCCC, "security")

    @Test fun peerTubePlaylistApprovalRefreshesVideoMembership() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val service = ServiceList.PeerTube.serviceId
            val id = SourceIdentity.fromUrl(service, SourceKind.PLAYLIST, "https://framatube.org/w/p/c1137ec9-4335-446d-bdc7-d0138a215ede")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.PLAYLIST, id, "Test playlist", id.id))
            val state = SourceRefreshCoordinator(storage, ExtractorSourceFetcher()).refresh(setOf("child"), SourceKind.PLAYLIST, id)
            val videos = state.profiles.single().sources.single().videos
            assertTrue(videos.isNotEmpty())
            assertTrue(videos.all { state.profiles.single().approvedLibrary().canPlay(it.toContentId()) })
            assertTrue(ChildLibrary.videos(state, "child").any { it.id in videos })
        } finally {
            storage.update { original }
        }
    }

    private fun verifyService(service: StreamingService, query: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val results = ParentContentSearch.firstPage(query, service.serviceId).items.filter { it.kind == ParentContentKind.VIDEO }
            assertTrue("No video results for ${service.serviceInfo.name}", results.isNotEmpty())
            val item = results.firstOrNull { runCatching { ExtractorVideoResolver.resolve(it.id) }.isSuccess }
                ?: throw AssertionError("No playable videos for ${service.serviceInfo.name}")
            parent.approveSearchItem("1234", setOf("child"), item)
            storage.update { it.copy(selectedProfileId = "child") }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, item.title) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, item.title)).performClick() }
                waitFor(scenario, 40_000) { (it.findViewById<PlayerView>(R.id.kids_player_view)?.player?.currentPosition ?: 0) >= 1_000 }
                parent.setVideoHidden("1234", setOf("child"), item.id, true)
                waitFor(scenario) { it.findViewById<View>(R.id.kids_player_view) == null }
            }
            val info = StreamInfo.getInfo(service, item.url)
            val channel = SourceIdentity.fromUrl(service.serviceId, SourceKind.CHANNEL, requireNotNull(info.uploaderUrl))
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.CHANNEL, channel, "Test channel", channel.id))
            val refreshed = SourceRefreshCoordinator(storage, ExtractorSourceFetcher()).refresh(setOf("child"), SourceKind.CHANNEL, channel)
            assertTrue("The channel returned no videos", refreshed.profiles.single().sources.single().videos.isNotEmpty())
        } finally {
            storage.update { original }
        }
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findButton(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun waitFor(scenario: ActivityScenario<KidsChildActivity>, timeout: Long = 10_000, condition: (KidsChildActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeout
        var satisfied = false
        while (!satisfied && System.currentTimeMillis() < deadline) {
            scenario.onActivity { satisfied = condition(it) }
            if (!satisfied) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for service playback", satisfied)
    }
}
