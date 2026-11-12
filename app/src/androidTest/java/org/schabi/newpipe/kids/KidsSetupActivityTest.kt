/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.schabi.newpipe.R

@RunWith(AndroidJUnit4::class)
class KidsSetupActivityTest {
    @Test fun setupValidatesFieldsBeforeSaving() {
        ActivityScenario.launch(KidsSetupActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val name = activity.findViewById<EditText>(R.id.kids_profile_name)
                val minutes = activity.findViewById<EditText>(R.id.kids_session_minutes)
                val pin = activity.findViewById<EditText>(R.id.kids_parent_pin)
                val confirm = activity.findViewById<EditText>(R.id.kids_parent_pin_confirm)
                val create = activity.findViewById<Button>(R.id.kids_setup_create)
                val error = activity.findViewById<TextView>(R.id.kids_setup_error)
                create.performClick()
                assertEquals(activity.getString(R.string.kids_setup_name_error), error.text.toString())
                name.setText("Test")
                minutes.setText("121")
                create.performClick()
                assertEquals(activity.getString(R.string.kids_setup_limit_error), error.text.toString())
                minutes.setText("10")
                pin.setText("12")
                create.performClick()
                assertEquals(activity.getString(R.string.kids_setup_pin_error), error.text.toString())
                pin.setText("1234")
                confirm.setText("5678")
                create.performClick()
                assertEquals(activity.getString(R.string.kids_setup_pin_mismatch), error.text.toString())
            }
        }
    }

    @Test fun validSetupPersistsProfileAndPinAcrossRotation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = KidsStorage.get(context)
        val original = storage.read()
        // Never overwrite a real configured profile when tests are run on a user's device.
        assumeTrue(original.parentPin == null && original.profiles.isEmpty())
        try {
            ActivityScenario.launch(KidsSetupActivity::class.java).use { scenario ->
                // Hold the storage lock until recreation so a fast device cannot finish setup first.
                synchronized(storage) {
                    scenario.onActivity { activity ->
                        activity.findViewById<EditText>(R.id.kids_profile_name).setText("Test child")
                        activity.findViewById<EditText>(R.id.kids_session_minutes).setText("15")
                        activity.findViewById<EditText>(R.id.kids_parent_pin).setText("1234")
                        activity.findViewById<EditText>(R.id.kids_parent_pin_confirm).setText("1234")
                        activity.findViewById<Button>(R.id.kids_setup_create).performClick()
                    }
                    scenario.recreate()
                }
                val deadline = System.currentTimeMillis() + 10_000
                while (storage.read().parentPin == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50)
                }
                val saved = storage.read()
                assertEquals("Test child", saved.profiles.single().name)
                assertEquals(15, saved.profiles.single().sessionLimitMinutes)
                assertTrue(ParentPin.verify(saved, "1234"))
            }
        } finally {
            storage.update { original }
        }
    }
}
