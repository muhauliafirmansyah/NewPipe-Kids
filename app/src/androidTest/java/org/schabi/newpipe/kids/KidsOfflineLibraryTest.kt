/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList

@RunWith(AndroidJUnit4::class)
class KidsOfflineLibraryTest {
    @Test fun failedRefreshPreservesCachedBrowsingAndPlaybackPolicy() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val service = ServiceList.PeerTube.serviceId
            val source = SourceIdentity.fromUrl(service, SourceKind.CHANNEL, "https://offline.invalid/video-channels/example")
            val video = VideoIdentity.fromUrl(service, "https://offline.invalid/w/12345678-1234-1234-1234-123456789abc")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.CHANNEL, source, "Cached channel", source.id))
            storage.update { state ->
                val started = ApprovalManager.beginSourceRefresh(state, setOf("child"), SourceKind.CHANNEL, source)
                val cached = ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(video))
                cached.copy(
                    selectedProfileId = "child",
                    catalog = cached.catalog + ParentSearchItem(ParentContentKind.VIDEO, video, "Cached video", video.id)
                )
            }
            val failed = ApprovedSourceRefresher(storage, ExtractorSourceFetcher()).refreshAll()
            assertEquals(listOf(video), failed.profiles.single().sources.single().videos)
            assertTrue(ChildLibrary.videos(failed, "child").any { it.id == video })
            assertTrue(ChildPlaybackAccess.allowed(failed, "child", video))
            parent.setVideoHidden("1234", setOf("child"), video, true)
            val hidden = storage.read()
            assertTrue(ChildLibrary.videos(hidden, "child").none { it.id == video })
            assertFalse(ChildPlaybackAccess.allowed(hidden, "child", video))
            parent.setVideoHidden("1234", setOf("child"), video, false)
            parent.removeSource("1234", setOf("child"), SourceKind.CHANNEL, source)
            val removed = storage.read()
            assertTrue(ChildLibrary.videos(removed, "child").none { it.id == video })
            assertFalse(ChildPlaybackAccess.allowed(removed, "child", video))
        } finally {
            storage.update { original }
        }
    }

    @Test fun failedRefreshKeepsCachedVideosButNotHiddenOrRemovedOnes() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val service = ServiceList.PeerTube.serviceId
            val source = SourceIdentity.fromUrl(service, SourceKind.CHANNEL, "https://offline.invalid/video-channels/example")
            val video = VideoIdentity.fromUrl(service, "https://offline.invalid/w/12345678-1234-1234-1234-123456789abc")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.CHANNEL, source, "Cached channel", source.id))
            storage.update { state ->
                val started = ApprovalManager.beginSourceRefresh(state, setOf("child"), SourceKind.CHANNEL, source)
                val cached = ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(video))
                cached.copy(
                    selectedProfileId = "child",
                    catalog = cached.catalog + ParentSearchItem(ParentContentKind.VIDEO, video, "Cached video", video.id)
                )
            }
            val afterFailure = ApprovedSourceRefresher(storage, ExtractorSourceFetcher()).refreshAll()
            assertEquals(listOf(video), afterFailure.profiles.single().sources.single().videos)
            assertTrue(afterFailure.profiles.single().approvedLibrary().canPlay(video.toContentId()))
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Cached video") != null }
                scenario.moveToState(Lifecycle.State.CREATED)
                parent.setVideoHidden("1234", setOf("child"), video, true)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario) { findButton(it.window.decorView, "Cached video") == null }
                assertFalse(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
                scenario.moveToState(Lifecycle.State.CREATED)
                parent.setVideoHidden("1234", setOf("child"), video, false)
                parent.removeSource("1234", setOf("child"), SourceKind.CHANNEL, source)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario) { findButton(it.window.decorView, "Cached video") == null }
                assertFalse(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
            }
        } finally {
            storage.update { original }
        }
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findButton(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun waitFor(scenario: ActivityScenario<KidsChildActivity>, condition: (KidsChildActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        var satisfied = false
        while (!satisfied && System.currentTimeMillis() < deadline) {
            scenario.onActivity { satisfied = condition(it) }
            if (!satisfied) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for the offline library", satisfied)
    }
}
