package com.example.test_app.ui.admin

import android.content.Context
import android.content.Intent

/**
 * Sharing of admin JSON through the system share sheet as plain text (no FileProvider is configured in the app, and no INTERNET permission
 * is used). Text over about [MAX_CHARS] is cut so the intent cannot exceed the binder limit; the share then says so in its subject.
 */
object AdminShare {
    const val MAX_CHARS = 200_000

    /** Opens the share sheet; returns false when there is nothing that can handle it. */
    fun shareText(context: Context, subject: String, text: String): Boolean {
        val cut = text.length > MAX_CHARS
        val body = if (cut) text.take(MAX_CHARS) else text
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, if (cut) "$subject (cut at $MAX_CHARS characters)" else subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        return try {
            context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
        } catch (e: Exception) {
            false
        }
    }
}
