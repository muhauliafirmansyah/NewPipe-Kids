/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.exoplayer2.ExoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.util.PeertubeHelper

@RunWith(AndroidJUnit4::class)
class KidsParentActivityTest {
    @Test fun peerTubeAccountScopeIsExplainedToTheParent() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ParentSettings(storage).setup("1234", "a", "First child", 10)
            val id = SourceIdentity.fromUrl(ServiceList.PeerTube.serviceId, SourceKind.CHANNEL, "https://video.example/accounts/example")
            storage.update { state ->
                ApprovalManager.approveSource(state, setOf("a"), SourceKind.CHANNEL, id).copy(
                    catalog = listOf(ParentSearchItem(ParentContentKind.CHANNEL, id, "Example account", id.id))
                )
            }
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, "Example account") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Example account")).performClick() }
                clickDialogItem("Open")
                waitFor(scenario) { hasText(it.window.decorView, it.getString(R.string.kids_parent_peertube_account_scope)) }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun parentCanPreviewAnApprovedVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("live") == "true")
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "test", "Test child", 10)
            val video = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("test"), ParentSearchItem(ParentContentKind.VIDEO, video, "Test song", "https://youtu.be/e_04ZrNroTo"))
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, "Test song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test song")).performClick() }
                clickDialogItem("Open")
                val deadline = System.currentTimeMillis() + 40_000
                var playing = false
                while (!playing && System.currentTimeMillis() < deadline) {
                    scenario.onActivity { activity ->
                        val field = KidsParentActivity::class.java.getDeclaredField("previewPlayer").apply { isAccessible = true }
                        val preview = field.get(activity) as? ExoPlayer
                        preview?.volume = 0f
                        playing = preview?.currentPosition?.let { it >= 2_000 } == true
                    }
                    if (!playing) Thread.sleep(100)
                }
                assertTrue("Parent preview did not play", playing)
                clickDialogItem("OK")
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun parentCanChooseAnotherPeerTubeServer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = KidsStorage.get(context)
        val original = storage.read()
        val previousServer = ServiceList.PeerTube.instance
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ParentSettings(storage).setup("1234", "a", "First child", 10)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content)) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_add_content))).performClick() }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_service, "YouTube"))).performClick() }
                clickDialogItem("PeerTube")
                waitFor(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_parent_peertube_server, ServiceList.PeerTube.baseUrl)) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_peertube_server, ServiceList.PeerTube.baseUrl))).performClick() }
                setDialogFields("https://video.blender.org")
                clickDialogItem("Save")
                waitFor(scenario) { ServiceList.PeerTube.baseUrl == "https://video.blender.org" }
            }
        } finally {
            PeertubeHelper.selectInstance(previousServer, context)
            storage.update { original }
        }
    }

    @Test fun parentCanManageProfilesAndResetTheirSession() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ParentSettings(storage).setup("1234", "a", "First child", 10)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    it.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_parent_profiles)) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_profiles))).performClick() }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "First child")).performClick() }
                clickDialogItem("Rename")
                setDialogFields("Renamed child")
                clickDialogItem("Save")
                waitFor(scenario) { storage.read().profiles.single().name == "Renamed child" }
                waitFor(scenario) { findButton(it.window.decorView, "Renamed child") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Renamed child")).performClick() }
                clickDialogItem("Disable")
                waitFor(scenario) { !storage.read().profiles.single().enabled }
                waitFor(scenario) { findButton(it.window.decorView, "Renamed child (Disabled)") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Renamed child (Disabled)")).performClick() }
                clickDialogItem("Enable")
                waitFor(scenario) { storage.read().profiles.single().enabled }
                waitFor(scenario) { findButton(it.window.decorView, "Renamed child") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Renamed child")).performClick() }
                clickDialogItem("Set session limit")
                setDialogFields("2")
                clickDialogItem("Save")
                waitFor(scenario) { storage.read().profiles.single().sessionLimitMinutes == 2 }
                storage.update { state -> state.copy(profiles = state.profiles.map { it.copy(remainingMillis = 1_000) }) }
                waitFor(scenario) { findButton(it.window.decorView, "Renamed child") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Renamed child")).performClick() }
                clickDialogItem("Reset session")
                waitFor(scenario) { storage.read().profiles.single().remainingMillis == 120_000L }
                Thread.sleep(200)
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_parent_add_profile))).performClick() }
                setDialogFields("Second child", "3")
                clickDialogItem("Save")
                waitFor(scenario) { storage.read().profiles.size == 2 }
                waitFor(scenario) { findButton(it.window.decorView, "Second child") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Second child")).performClick() }
                clickDialogItem("Remove")
                Thread.sleep(200)
                clickDialogItem("Remove")
                waitFor(scenario) { storage.read().profiles.size == 1 }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun parentCanRemoveAnOverlapAndHideAPlaylistVideo() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val storage = KidsStorage.get(instrumentation.targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "test", "Test child", 10)
            val video = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem("1234", setOf("test"), ParentSearchItem(ParentContentKind.VIDEO, video, "Test song", "https://youtu.be/e_04ZrNroTo"))
            val source = StoredContentId(0, "playlist-for-test")
            storage.update { state ->
                val approved = ApprovalManager.approveSource(state, setOf("test"), SourceKind.PLAYLIST, source)
                val started = ApprovalManager.beginSourceRefresh(approved, setOf("test"), SourceKind.PLAYLIST, source)
                val refreshed = ApprovalManager.completeSourceRefresh(started.state, started.ticket, listOf(video))
                refreshed.copy(catalog = refreshed.catalog + ParentSearchItem(ParentContentKind.PLAYLIST, source, "Test playlist", "https://youtube.com/playlist?list=test"))
            }
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    activity.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, "Test song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test song")).performClick() }
                clickDialogItem("Remove")
                waitFor(scenario) { video !in storage.read().profiles.single().approvedVideos }
                assertTrue(storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test playlist")).performClick() }
                clickDialogItem("Open")
                waitFor(scenario) { findButton(it.window.decorView, "Test song") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test song")).performClick() }
                clickDialogItem("Hide")
                waitFor(scenario) { video in storage.read().profiles.single().hiddenVideos }
                assertTrue(!storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
                waitFor(scenario) { findButton(it.window.decorView, "Test song (Hidden)") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test song (Hidden)")).performClick() }
                clickDialogItem("Show")
                waitFor(scenario) { video !in storage.read().profiles.single().hiddenVideos }
                waitFor(scenario) { findButton(it.window.decorView, "Test song (Hidden)") == null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test playlist")).performClick() }
                clickDialogItem("Hide")
                waitFor(scenario) { !storage.read().profiles.single().sources.single().enabled }
                assertTrue(!storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
                waitFor(scenario) { findButton(it.window.decorView, "Test playlist (Hidden)") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test playlist (Hidden)")).performClick() }
                clickDialogItem("Show")
                waitFor(scenario) { storage.read().profiles.single().sources.single().enabled }
                waitFor(scenario) { findButton(it.window.decorView, "Test playlist") != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, "Test playlist")).performClick() }
                clickDialogItem("Remove")
                waitFor(scenario) { storage.read().profiles.single().sources.isEmpty() }
                assertTrue(!storage.read().profiles.single().approvedLibrary().canPlay(video.toContentId()))
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun parentMustUnlockBeforeProfilesAppear() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ParentSettings(storage).setup("1234", "test", "Test child", 10)
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, "Test child") == null)
                    activity.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("9999")
                    activity.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { it.findViewById<TextView>(R.id.kids_parent_error)?.visibility == View.VISIBLE }
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, "Test child") == null)
                    activity.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    activity.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_parent_profiles)) != null }
                scenario.onActivity { activity ->
                    requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.kids_parent_profiles))).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, "Test child") != null }
                assertEquals("Test child", storage.read().profiles.single().name)
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun approvedContentIsShownFirstAfterUnlock() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "test", "Test child", 10)
            val video = VideoIdentity.youtube("e_04ZrNroTo")
            parent.approveSearchItem(
                "1234",
                setOf("test"),
                ParentSearchItem(ParentContentKind.VIDEO, video, "Test song", "https://youtu.be/e_04ZrNroTo")
            )
            ActivityScenario.launch(KidsParentActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<EditText>(R.id.kids_parent_unlock_pin).setText("1234")
                    activity.findViewById<Button>(R.id.kids_parent_unlock_button).performClick()
                }
                waitFor(scenario) { findButton(it.window.decorView, "Test song") != null }
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, activity.getString(R.string.kids_parent_add_content)) != null)
                }
            }
        } finally {
            storage.update { original }
        }
    }

    private fun findButton(view: View, text: String): Button? {
        if (view is Button && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findButton(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun hasText(view: View, text: String): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        return view is ViewGroup && (0 until view.childCount).any { hasText(view.getChildAt(it), text) }
    }

    private fun waitFor(scenario: ActivityScenario<KidsParentActivity>, condition: (KidsParentActivity) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        var satisfied = false
        while (!satisfied && System.currentTimeMillis() < deadline) {
            scenario.onActivity { satisfied = condition(it) }
            if (!satisfied) Thread.sleep(50)
        }
        assertTrue("Timed out waiting for parent screen", satisfied)
    }

    private fun clickDialogItem(text: String) {
        val deadline = System.currentTimeMillis() + 10_000
        var clicked = false
        while (!clicked && System.currentTimeMillis() < deadline) {
            clicked = TestAppWindow.clickText(text)
            if (!clicked) Thread.sleep(50)
        }
        assertTrue("Could not click dialog item $text in the test app", clicked)
    }

    private fun setDialogFields(vararg values: String) {
        fun inputs(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
            if (node == null) return emptyList()
            val current = if (node.className == EditText::class.java.name) listOf(node) else emptyList()
            return current + (0 until node.childCount).flatMap { inputs(node.getChild(it)) }
        }
        val deadline = System.currentTimeMillis() + 10_000
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            val fields = inputs(TestAppWindow.activeRoot())
            if (fields.size == values.size) {
                done = fields.zip(values).all { (field, value) ->
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
                    field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
            }
            if (!done) Thread.sleep(50)
        }
        assertTrue("Could not enter dialog fields", done)
    }
}
