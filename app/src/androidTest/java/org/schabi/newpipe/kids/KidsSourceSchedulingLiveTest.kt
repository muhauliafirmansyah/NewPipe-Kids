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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.extractor.ServiceList

/** Opt in with -e live true; verifies the refresh initiated by the child launcher. */
@RunWith(AndroidJUnit4::class)
class KidsSourceSchedulingLiveTest {
    @Test fun parentModeAlsoRefreshesApprovedSources() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val id = SourceIdentity.fromUrl(ServiceList.YouTube.serviceId, SourceKind.PLAYLIST, "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8")
            val oldVideo = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.PLAYLIST, id, "Test playlist", id.id))
            storage.update { state ->
                val started = ApprovalManager.beginSourceRefresh(state, setOf("child"), SourceKind.PLAYLIST, id)
                ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(oldVideo))
            }
            ActivityScenario.launch(KidsParentActivity::class.java).use {
                val deadline = System.currentTimeMillis() + 40_000
                var refreshed = false
                while (!refreshed && System.currentTimeMillis() < deadline) {
                    val source = storage.read().profiles.single().sources.single()
                    refreshed = source.activeRefreshToken == null && source.videos.isNotEmpty() && source.videos != listOf(oldVideo)
                    if (!refreshed) Thread.sleep(100)
                }
                assertTrue("The approved playlist did not refresh in Parent mode", refreshed)
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun appOpenRefreshesTheApprovedPlaylistAndKeepsLocalHides() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "child", "Test child", 2)
            val id = SourceIdentity.fromUrl(ServiceList.YouTube.serviceId, SourceKind.PLAYLIST, "https://www.youtube.com/playlist?list=PL8dPuuaLjXtOAKed_MxxWBNaPno5h3Zs8")
            val oldVideo = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("child"), ParentSearchItem(ParentContentKind.PLAYLIST, id, "Test playlist", id.id))
            storage.update { state ->
                val started = ApprovalManager.beginSourceRefresh(state, setOf("child"), SourceKind.PLAYLIST, id)
                val cached = ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(oldVideo))
                ApprovalManager.setVideoHidden(cached, setOf("child"), oldVideo, true).copy(selectedProfileId = "child")
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                val deadline = System.currentTimeMillis() + 40_000
                var refreshed = false
                while (!refreshed && System.currentTimeMillis() < deadline) {
                    val profile = storage.read().profiles.single()
                    val source = profile.sources.single()
                    refreshed = source.activeRefreshToken == null && source.videos.isNotEmpty() && source.videos != listOf(oldVideo)
                    if (!refreshed) Thread.sleep(100)
                }
                assertTrue("The approved playlist did not refresh on app open", refreshed)
                val state = storage.read()
                assertTrue(oldVideo in state.profiles.single().hiddenVideos)
                assertTrue(!state.profiles.single().approvedLibrary().canPlay(oldVideo.toContentId()))
                val expected = ChildLibrary.videos(state, "child").first()
                val displayDeadline = System.currentTimeMillis() + 10_000
                var shown = false
                while (!shown && System.currentTimeMillis() < displayDeadline) {
                    scenario.onActivity { shown = findButton(it.window.decorView, expected.title) != null }
                    if (!shown) Thread.sleep(50)
                }
                assertTrue("Refreshed videos were not shown", shown)
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
}
