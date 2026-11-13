/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

@RunWith(AndroidJUnit4::class)
class KidsCombinedLibraryTest {
    @Test fun combinedVideosAndChannelPlaylistsUpdateWithoutDuplicatesOrUnapprovedVideos() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Test child", 30)
            parent.addProfile("1234", "b", "Other child", 30)
            val shared = video("e_04ZrNroTo", "Shared video")
            val own = video("jNQXAC9IVRw", "Channel video")
            val external = video("aqz-KE-bpKQ", "Other channel approved")
            val unapproved = video("9bZkp7q19f0", "Other channel unapproved")
            val hidden = video("dQw4w9WgXcQ", "Hidden video")
            val added = video("abcdefghijk", "New channel video")
            val channelId = StoredContentId(0, "fixture-channel")
            val playlistId = StoredContentId(0, "fixture-channel-playlist")
            val explicitId = StoredContentId(0, "fixture-explicit-playlist")
            val channel = StoredSource(
                SourceKind.CHANNEL,
                channelId,
                videos = listOf(shared.id, own.id, hidden.id),
                channelPlaylists = listOf(StoredChannelPlaylist(playlistId, listOf(own.id, external.id, unapproved.id, hidden.id, shared.id)))
            )
            val explicit = StoredSource(SourceKind.PLAYLIST, explicitId, videos = listOf(shared.id))
            storage.update { state ->
                state.copy(
                    selectedProfileId = "a",
                    profiles = state.profiles.map { profile ->
                        if (profile.id == "a") profile.copy(approvedVideos = setOf(shared.id, external.id), hiddenVideos = setOf(hidden.id), sources = listOf(channel, explicit)) else profile
                    },
                    catalog = listOf(shared, own, external, unapproved, hidden) + listOf(
                        ParentSearchItem(ParentContentKind.CHANNEL, channelId, "Test channel", "https://example.org/channel"),
                        ParentSearchItem(ParentContentKind.PLAYLIST, playlistId, "Channel playlist", "https://example.org/channel-playlist"),
                        ParentSearchItem(ParentContentKind.PLAYLIST, explicitId, "Explicit playlist", "https://example.org/explicit-playlist")
                    )
                )
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, shared.title) != null }
                // The invalid fixture URLs make the automatic network refresh finish without altering snapshots.
                waitForStorage { storage.read().profiles.first().sources.all { it.activeRefreshToken != null } }
                waitForStorage { storage.read().profiles.first().sources.first { it.id == channelId }.activePlaylistRefreshToken != null }
                scenario.onActivity { activity ->
                    requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.kids_category_videos))).performClick()
                    assertEquals(1, countTag(activity.window.decorView, "kids-video-card-${shared.id.id}"))
                    assertNotNull(findButton(activity.window.decorView, own.title))
                    assertNotNull(findButton(activity.window.decorView, external.title))
                    assertFalse(findButton(activity.window.decorView, hidden.title) != null)
                    assertFalse(findButton(activity.window.decorView, unapproved.title) != null)
                }
                storage.update { state ->
                    state.copy(
                        catalog = state.catalog + added,
                        profiles = state.profiles.map { profile ->
                            if (profile.id != "a") {
                                profile
                            } else {
                                profile.copy(
                                    sources = profile.sources.map { source ->
                                        if (source.id != channelId) {
                                            source
                                        } else {
                                            source.copy(
                                                videos = source.videos + added.id,
                                                channelPlaylists = source.channelPlaylists.map { it.copy(videos = it.videos + added.id) }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    )
                }
                waitFor(scenario) { findButton(it.window.decorView, added.title) != null }
                scenario.onActivity { activity ->
                    assertEquals(1, countTag(activity.window.decorView, "kids-video-card-${shared.id.id}"))
                    requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.kids_category_playlists))).performClick()
                    assertEquals(1, countTag(activity.window.decorView, "kids-source-${playlistId.id}"))
                    requireNotNull(activity.window.decorView.findViewWithTag<View>("kids-playlist-thumbnail-${playlistId.id}")).performClick()
                    assertNotNull(findButton(activity.window.decorView, own.title))
                    assertNotNull(findButton(activity.window.decorView, added.title))
                    assertFalse(findButton(activity.window.decorView, external.title) != null)
                    assertFalse(findButton(activity.window.decorView, unapproved.title) != null)
                    assertFalse(findButton(activity.window.decorView, hidden.title) != null)
                }
                storage.update { state ->
                    val approved = ApprovalManager.approveSource(state, setOf("a"), SourceKind.PLAYLIST, playlistId)
                    val started = ApprovalManager.beginSourceRefresh(approved, setOf("a"), SourceKind.PLAYLIST, playlistId)
                    ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(own.id, external.id, unapproved.id, hidden.id, shared.id))
                }
                waitFor(scenario) { findButton(it.window.decorView, unapproved.title) != null }
                scenario.onActivity { activity ->
                    assertNotNull(findButton(activity.window.decorView, external.title))
                    assertFalse(findButton(activity.window.decorView, hidden.title) != null)
                    activity.onBackPressedDispatcher.onBackPressed()
                    assertEquals(1, countTag(activity.window.decorView, "kids-source-${playlistId.id}"))
                    activity.findViewById<Button>(R.id.kids_nav_search).performClick()
                    activity.findViewById<EditText>(R.id.kids_child_search).setText("shared")
                    assertEquals(1, countTag(activity.window.decorView, "kids-video-card-${shared.id.id}"))
                }
            }
        } finally {
            storage.update { original }
        }
    }

    private fun video(id: String, title: String) = ParentSearchItem(ParentContentKind.VIDEO, VideoIdentity.youtube(id), title, "https://youtu.be/$id")

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findButton(view.getChildAt(index), text)?.let { return it }
        return null
    }

    private fun countTag(view: View, tag: String): Int = (if (view.tag == tag) 1 else 0) +
        if (view is ViewGroup) (0 until view.childCount).sumOf { countTag(view.getChildAt(it), tag) } else 0

    private fun waitFor(scenario: ActivityScenario<KidsChildActivity>, condition: (KidsChildActivity) -> Boolean) {
        waitForStorage {
            var found = false
            scenario.onActivity { found = condition(it) }
            found
        }
    }

    private fun waitForStorage(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for the child library", condition())
    }
}
