package com.swordfish.lemuroid.app.tv.romm

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** The hidden set-up for Johnny Music: the folder's address (already filled in) and its password. */
object JohnnyMusicSetup {
    fun show(activity: Activity) {
        val saved = JohnnyMusic.config(activity)
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val address =
            EditText(activity).apply {
                setText(saved?.url ?: JohnnyMusic.DEFAULT_URL)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setSingleLine()
            }
        val password =
            EditText(activity).apply {
                hint = "Password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine()
            }
        val layout =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(TextView(activity).apply { text = "Folder address" })
                addView(address)
                addView(TextView(activity).apply { text = "Password" })
                addView(password)
            }
        val builder =
            AlertDialog.Builder(activity)
                .setTitle("Johnny Music")
                .setMessage(
                    if (saved != null) {
                        "Johnny Music is on. It's in the game menu while you play."
                    } else {
                        "Enter the password to add Johnny Music to the game menu."
                    },
                )
                .setView(layout)
                .setPositiveButton(if (saved != null) "Save" else "Turn on") { _, _ ->
                    val url = address.text.toString()
                    val pw = password.text.toString()
                    val app = activity.applicationContext
                    Thread {
                        val result = runCatching { JohnnyMusic.unlock(app, url, pw) }
                        activity.runOnUiThread {
                            Toast.makeText(
                                app,
                                result.fold(
                                    { "Johnny Music is on. Open the game menu while playing to use it." },
                                    { "Couldn't turn on Johnny Music: ${it.message ?: "no answer from the server"}." },
                                ),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }.start()
                }
                .setNegativeButton("Cancel", null)
        if (saved != null) {
            builder.setNeutralButton("Turn off") { _, _ ->
                JohnnyMusic.lock(activity)
                Toast.makeText(activity, "Johnny Music is off and hidden again.", Toast.LENGTH_LONG).show()
            }
        }
        builder.show()
        password.requestFocus()
    }
}
