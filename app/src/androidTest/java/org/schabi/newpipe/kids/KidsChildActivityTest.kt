/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

@RunWith(AndroidJUnit4::class)
class KidsChildActivityTest {
    @Test fun searchFiltersApprovedChannelsAndPlaylistsForTheActiveProfile() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "First child", 2)
            parent.addProfile("1234", "b", "Second child", 2)
            val channel = StoredContentId(0, "channel-a")
            val playlist = StoredContentId(0, "playlist-a")
            val hidden = StoredContentId(0, "hidden-channel")
            val other = StoredContentId(0, "other-channel")
            storage.update { state ->
                var updated = ApprovalManager.approveSource(state, setOf("a"), SourceKind.CHANNEL, channel)
                updated = ApprovalManager.approveSource(updated, setOf("a"), SourceKind.PLAYLIST, playlist)
                updated = ApprovalManager.approveSource(updated, setOf("a"), SourceKind.CHANNEL, hidden)
                updated = ApprovalManager.setSourceEnabled(updated, setOf("a"), SourceKind.CHANNEL, hidden, false)
                updated = ApprovalManager.approveSource(updated, setOf("b"), SourceKind.CHANNEL, other)
                updated.copy(
                    selectedProfileId = "a",
                    catalog = listOf(
                        ParentSearchItem(ParentContentKind.CHANNEL, channel, "Space Learning", "https://example.org/a"),
                        ParentSearchItem(ParentContentKind.PLAYLIST, playlist, "Music Collection", "https://example.org/b"),
                        ParentSearchItem(ParentContentKind.CHANNEL, hidden, "Hidden Channel", "https://example.org/c"),
                        ParentSearchItem(ParentContentKind.CHANNEL, other, "Other Channel", "https://example.org/d")
                    )
                )
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Channel: Space Learning") != null }
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, "Playlist: Music Collection") != null)
                    assertTrue(findButton(activity.window.decorView, "Channel: Hidden Channel") == null)
                    assertTrue(findButton(activity.window.decorView, "Channel: Other Channel") == null)
                    activity.findViewById<EditText>(R.id.kids_child_search).setText("space")
                    assertTrue(findButton(activity.window.decorView, "Channel: Space Learning") != null)
                    assertTrue(findButton(activity.window.decorView, "Playlist: Music Collection") == null)
                    activity.findViewById<EditText>(R.id.kids_child_search).setText("music")
                    assertTrue(findButton(activity.window.decorView, "Channel: Space Learning") == null)
                    assertTrue(findButton(activity.window.decorView, "Playlist: Music Collection") != null)
                }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun switchingProfilesKeepsSessionsAndCanRequireAParentPin() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "First child", 2)
            parent.addProfile("1234", "b", "Second child", 2)
            storage.update { it.copy(selectedProfileId = "a") }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { hasText(it.window.decorView, "First child") }
                Thread.sleep(1_200)
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_switch_profile))).performClick() }
                tapDialogText("Second child")
                waitForStorage { storage.read().selectedProfileId == "b" }
                val firstRemaining = storage.read().profiles.first { it.id == "a" }.remainingMillis
                assertTrue(firstRemaining < 120_000)
                waitFor(scenario) { hasText(it.window.decorView, "Second child") }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_switch_profile))).performClick() }
                tapDialogText("First child")
                waitForStorage { storage.read().selectedProfileId == "a" }
                assertTrue(storage.read().profiles.first { it.id == "a" }.remainingMillis <= firstRemaining)
                scenario.moveToState(Lifecycle.State.CREATED)
                parent.setProfileSwitchLock("1234", true)
                scenario.moveToState(Lifecycle.State.RESUMED)
                Thread.sleep(500)
                waitFor(scenario) { findButton(it.window.decorView, it.getString(R.string.kids_switch_profile)) != null }
                scenario.onActivity { requireNotNull(findButton(it.window.decorView, it.getString(R.string.kids_switch_profile))).performClick() }
                tapDialogText("Second child")
                assertTrue(storage.read().selectedProfileId == "a")
                enterDialogPin("1234")
                tapDialogText("Unlock")
                waitForStorage { storage.read().selectedProfileId == "b" }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun launcherUsesChildScreenAndLegacyEntryPointsAreDisabled() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.packageManager
        val launch = requireNotNull(manager.getLaunchIntentForPackage(context.packageName))
        assertTrue(launch.component?.className == KidsChildActivity::class.java.name)
        val legacyActivity = manager.getActivityInfo(ComponentName(context.packageName, "org.schabi.newpipe.MainActivity"), PackageManager.MATCH_DISABLED_COMPONENTS)
        val router = manager.getActivityInfo(ComponentName(context.packageName, "org.schabi.newpipe.RouterActivity"), PackageManager.MATCH_DISABLED_COMPONENTS)
        val player = manager.getServiceInfo(ComponentName(context.packageName, "org.schabi.newpipe.player.PlayerService"), PackageManager.MATCH_DISABLED_COMPONENTS)
        val debugActivity = manager.getActivityInfo(ComponentName(context.packageName, "leakcanary.internal.activity.LeakActivity"), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertFalse(legacyActivity.enabled || legacyActivity.exported)
        assertFalse(router.enabled || router.exported)
        assertFalse(player.enabled || player.exported)
        assertFalse(debugActivity.exported)
    }

    @Test fun exportedComponentsAreLimitedToTheChildLauncherAndProtectedSystemReceivers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or PackageManager.MATCH_DISABLED_COMPONENTS
        val info = context.packageManager.getPackageInfo(context.packageName, flags)
        val exported = mutableMapOf<String, String?>()
        info.activities.orEmpty().filter { it.enabled && it.exported }.forEach { exported[it.name] = it.permission }
        info.services.orEmpty().filter { it.enabled && it.exported }.forEach { exported[it.name] = it.permission }
        info.receivers.orEmpty().filter { it.enabled && it.exported }.forEach { exported[it.name] = it.permission }
        info.providers.orEmpty().filter { it.enabled && it.exported }.forEach { exported[it.name] = it.readPermission ?: it.writePermission }
        val allowed = mapOf(
            KidsChildActivity::class.java.name to null,
            "androidx.work.impl.background.systemjob.SystemJobService" to "android.permission.BIND_JOB_SERVICE",
            "androidx.work.impl.diagnostics.DiagnosticsReceiver" to "android.permission.DUMP",
            "androidx.profileinstaller.ProfileInstallReceiver" to "android.permission.DUMP"
        )
        assertTrue("Unexpected exported components: $exported", exported.all { (name, permission) -> name in allowed && allowed[name] == permission })
        assertTrue(exported.containsKey(KidsChildActivity::class.java.name))
    }

    @Test fun externalLinkCannotOpenAnUnapprovedVideo() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = KidsStorage.get(context)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "First child", 2)
            parent.approveSearchItem("1234", setOf("a"), item("e_04ZrNroTo", "Allowed song"))
            storage.update { it.copy(selectedProfileId = "a") }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://youtu.be/dQw4w9WgXcQ"), context, KidsChildActivity::class.java)
            ActivityScenario.launch<KidsChildActivity>(intent).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Allowed song") != null }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<View>(R.id.kids_player_view) == null)
                }
            }
            val shared = Intent(Intent.ACTION_SEND).setType("text/plain").setClass(context, KidsChildActivity::class.java)
                .putExtra(Intent.EXTRA_TEXT, "Watch https://video.example/w/12345678-1234-1234-1234-123456789abc")
            ActivityScenario.launch<KidsChildActivity>(shared).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Allowed song") != null }
                scenario.onActivity { activity ->
                    assertTrue(activity.findViewById<View>(R.id.kids_player_view) == null)
                }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun childSearchStaysWithinProfileAndTimerPausesInBackground() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "First child", 2)
            parent.addProfile("1234", "b", "Second child", 2)
            val allowed = item("e_04ZrNroTo", "Allowed song")
            val another = item("jNQXAC9IVRw", "Another song")
            val hidden = item("dQw4w9WgXcQ", "Hidden song")
            val other = item("abcdefghijk", "Other child song")
            parent.approveSearchItem("1234", setOf("a"), allowed)
            parent.approveSearchItem("1234", setOf("a"), another)
            parent.approveSearchItem("1234", setOf("a"), hidden)
            parent.setVideoHidden("1234", setOf("a"), hidden.id, true)
            parent.approveSearchItem("1234", setOf("b"), other)
            storage.update { state ->
                state.copy(selectedProfileId = "a", profiles = state.profiles.map { if (it.id == "a") it.copy(favorites = setOf(allowed.id)) else it })
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Allowed song") != null }
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, "Hidden song") == null)
                    assertTrue(findButton(activity.window.decorView, "Other child song") == null)
                    assertTrue(findButton(activity.window.decorView, "Another song") != null)
                    requireNotNull(findButton(activity.window.decorView, activity.getString(R.string.kids_show_favorites))).performClick()
                    assertTrue(findButton(activity.window.decorView, "Allowed song") != null)
                    assertTrue(findButton(activity.window.decorView, "Another song") == null)
                    activity.findViewById<EditText>(R.id.kids_child_search).setText("other")
                    assertTrue(findButton(activity.window.decorView, "Allowed song") == null)
                    assertTrue(findButton(activity.window.decorView, "Other child song") == null)
                }
                Thread.sleep(1_300)
                scenario.moveToState(Lifecycle.State.CREATED)
                waitForStorage { storage.read().profiles.first { it.id == "a" }.remainingMillis < 120_000 }
                val paused = storage.read().profiles.first { it.id == "a" }.remainingMillis
                Thread.sleep(1_300)
                assertTrue(storage.read().profiles.first { it.id == "a" }.remainingMillis == paused)
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitForStorage { storage.read().profiles.first { it.id == "a" }.remainingMillis < paused }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun disabledProfilesAreNotShownAndExpiredProfileShowsParentMessage() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Active child", 1)
            parent.addProfile("1234", "b", "Disabled child", 1)
            parent.setProfileEnabled("1234", "b", false)
            storage.update { state ->
                state.copy(profiles = state.profiles.map { if (it.id == "a") it.copy(remainingMillis = 0) else it })
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { findButton(it.window.decorView, "Active child") != null }
                scenario.onActivity { activity ->
                    assertFalse(findButton(activity.window.decorView, "Disabled child") != null)
                    requireNotNull(findButton(activity.window.decorView, "Active child")).performClick()
                }
                waitFor(scenario) { it.findViewById<View>(R.id.kids_session_ended) != null }
                scenario.onActivity { activity ->
                    assertTrue(findButton(activity.window.decorView, activity.getString(R.string.kids_parent_title)) != null)
                }
            }
        } finally {
            storage.update { original }
        }
    }

    @Test fun expirySurvivesRecreationAndOnlyAParentCanResetIt() {
        val storage = KidsStorage.get(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = storage.read()
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            val parent = ParentSettings(storage)
            parent.setup("1234", "a", "Test child", 1)
            storage.update { state ->
                state.copy(selectedProfileId = "a", profiles = state.profiles.map { it.copy(remainingMillis = 4_000) })
            }
            ActivityScenario.launch(KidsChildActivity::class.java).use { scenario ->
                waitFor(scenario) { it.findViewById<TextView>(R.id.kids_time_remaining)?.text?.toString()?.contains("almost up") == true }
                waitFor(scenario) { it.findViewById<View>(R.id.kids_session_ended) != null }
                assertTrue(storage.read().profiles.single().remainingMillis == 0L)
                scenario.recreate()
                waitFor(scenario) { it.findViewById<View>(R.id.kids_session_ended) != null }
                scenario.moveToState(Lifecycle.State.CREATED)
                try {
                    parent.resetSession("9999", "a")
                    throw AssertionError("Incorrect PIN unexpectedly reset the session")
                } catch (_: IllegalArgumentException) {
                    assertTrue(storage.read().profiles.single().remainingMillis == 0L)
                }
                parent.resetSession("1234", "a")
                scenario.moveToState(Lifecycle.State.RESUMED)
                waitFor(scenario) { it.findViewById<EditText>(R.id.kids_child_search) != null }
                assertTrue(storage.read().profiles.single().remainingMillis > 0)
            }
        } finally {
            storage.update { original }
        }
    }

    private fun item(id: String, title: String) = ParentSearchItem(
        ParentContentKind.VIDEO,
        VideoIdentity.youtube(id),
        title,
        "https://youtu.be/$id"
    )

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

    private fun waitFor(scenario: ActivityScenario<KidsChildActivity>, condition: (KidsChildActivity) -> Boolean) {
        var satisfied = false
        waitForStorage {
            scenario.onActivity { satisfied = condition(it) }
            satisfied
        }
    }

    private fun waitForStorage(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for child screen", condition())
    }

    private fun tapDialogText(text: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = System.currentTimeMillis() + 10_000
        var tapped = false
        while (!tapped && System.currentTimeMillis() < deadline) {
            val node = automation.rootInActiveWindow?.findAccessibilityNodeInfosByText(text)?.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true }
            if (node != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                tapped = automation.injectInputEvent(down, true) && automation.injectInputEvent(up, true)
                down.recycle()
                up.recycle()
            }
            if (!tapped) Thread.sleep(50)
        }
        assertTrue("Could not tap $text", tapped)
    }

    private fun enterDialogPin(pin: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun findInput(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.className == EditText::class.java.name) return node
            for (index in 0 until node.childCount) findInput(node.getChild(index))?.let { return it }
            return null
        }
        val deadline = System.currentTimeMillis() + 10_000
        var entered = false
        while (!entered && System.currentTimeMillis() < deadline) {
            val input = findInput(automation.rootInActiveWindow)
            val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, pin) }
            entered = input?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
            if (!entered) Thread.sleep(50)
        }
        assertTrue("Could not enter the parent PIN", entered)
    }
}
