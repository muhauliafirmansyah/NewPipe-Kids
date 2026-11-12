/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KidsStorageInstrumentedTest {
    @Test
    fun largeChannelSnapshotKeepsHiddenVideosOutOfSearch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "kids-scale-${UUID.randomUUID()}.db"
        val storage = KidsStorage.database(context, name)
        try {
            val videos = (0 until 3_000).map { index -> VideoIdentity.youtube(index.toString().padStart(11, '0')) }
            val hidden = videos.filterIndexed { index, _ -> index % 10 == 0 }.toSet()
            val source = StoredSource(SourceKind.CHANNEL, StoredContentId(0, "test-channel"), videos = videos)
            val profile = ChildProfile("a", "Test child", sessionLimitMinutes = 2, remainingMillis = 120_000, sources = listOf(source), hiddenVideos = hidden)
            val catalog = videos.mapIndexed { index, video -> ParentSearchItem(ParentContentKind.VIDEO, video, "Video $index", "https://youtu.be/${video.id}") }
            storage.update { KidsState(profiles = listOf(profile), catalog = catalog, selectedProfileId = "a") }
            val saved = storage.read()
            val visible = ChildLibrary.videos(saved, "a")
            assertEquals(2_700, visible.size)
            assertTrue(visible.none { it.id in hidden })
            assertTrue(ChildLibrary.videos(saved, "a", "Video 2999").single().id == videos[2999])
        } finally {
            storage.closeForTests()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun migratesPreferencesToTransactionalDatabaseAndReopens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val name = "kids-test-$suffix.db"
        val preferences = context.getSharedPreferences("kids-test-$suffix", Context.MODE_PRIVATE)
        val initial = ProfileManager.add(KidsState(), "a", "A", 2)
        assertTrue(preferences.edit().putString("state", KidsStateCodec.encode(initial)).commit())
        val first = KidsStorage.database(context, name, preferences)
        try {
            assertEquals(initial, first.read())
            assertFalse(preferences.contains("state"))
            val updated = first.update { ProfileManager.rename(it, "a", "New name") }
            try {
                first.update { throw IllegalStateException("cancelled") }
                throw AssertionError("Expected update to fail")
            } catch (_: IllegalStateException) {
                assertEquals(updated, first.read())
            }
            first.closeForTests()
            val reopened = KidsStorage.database(context, name)
            try {
                assertEquals(updated, reopened.read())
            } finally {
                reopened.closeForTests()
            }
        } finally {
            first.closeForTests()
            preferences.edit().clear().commit()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun savesAndReadsProfileUsingAndroidPreferences() {
        // Use a separate test-only preferences file in the target app's accessible storage.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = KidsStorage(
            context.getSharedPreferences("kids_storage_instrumentation", Context.MODE_PRIVATE)
        )
        val original = storage.read()
        try {
            val video = StoredContentId(0, "e_04ZrNroTo")
            val profile = ChildProfile(
                id = "instrumentation-profile",
                name = "Test",
                sessionLimitMinutes = 5,
                remainingMillis = 120_000,
                approvedVideos = setOf(video)
            )
            val expected = KidsState(profiles = listOf(profile))
            storage.update { expected }
            assertEquals(expected, storage.read())
            assertTrue(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
            storage.update { state ->
                state.copy(profiles = state.profiles.map { it.copy(hiddenVideos = setOf(video)) })
            }
            assertFalse(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
        } finally {
            storage.update { original }
        }
    }
}
