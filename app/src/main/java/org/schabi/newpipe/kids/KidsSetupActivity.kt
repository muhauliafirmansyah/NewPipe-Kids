/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.schabi.newpipe.kids

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import org.schabi.newpipe.R

/** First-run parent setup. The caller is responsible for opening it before the child UI. */
class KidsSetupActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_kids_setup)
        val name = findViewById<EditText>(R.id.kids_profile_name)
        val minutes = findViewById<EditText>(R.id.kids_session_minutes)
        val pin = findViewById<EditText>(R.id.kids_parent_pin)
        val confirmation = findViewById<EditText>(R.id.kids_parent_pin_confirm)
        val error = findViewById<TextView>(R.id.kids_setup_error)
        val create = findViewById<Button>(R.id.kids_setup_create)
        val model = ViewModelProvider(this)[KidsSetupViewModel::class.java]
        model.status.observe(this) { status ->
            create.isEnabled = status != KidsSetupStatus.SAVING
            when (status) {
                KidsSetupStatus.COMPLETE -> {
                    pin.text.clear()
                    confirmation.text.clear()
                    setResult(Activity.RESULT_OK)
                    finish()
                }

                KidsSetupStatus.ERROR -> {
                    pin.text.clear()
                    confirmation.text.clear()
                    error.setText(R.string.kids_setup_save_error)
                    error.visibility = View.VISIBLE
                }

                else -> Unit
            }
        }
        create.setOnClickListener {
            val childName = name.text.toString().trim()
            val limit = minutes.text.toString().toIntOrNull()
            val code = pin.text.toString()
            val validationError = when {
                childName.isBlank() -> R.string.kids_setup_name_error
                limit == null || limit !in SessionTime.MIN_MINUTES..SessionTime.MAX_MINUTES -> R.string.kids_setup_limit_error
                code.length !in 4..12 || code.any { digit -> digit !in '0'..'9' } -> R.string.kids_setup_pin_error
                code != confirmation.text.toString() -> R.string.kids_setup_pin_mismatch
                else -> null
            }
            if (validationError != null) {
                error.setText(validationError)
                error.visibility = View.VISIBLE
                return@setOnClickListener
            }
            error.visibility = View.GONE
            model.createProfile(childName, requireNotNull(limit), code)
        }
    }
}
