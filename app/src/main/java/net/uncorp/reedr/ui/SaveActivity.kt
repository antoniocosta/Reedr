package net.uncorp.reedr.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/** Receives shared links and http(s) links opened with Reedr and hands them to [MainActivity], which saves and opens them. */
class SaveActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        open(intent)
        // a windowNoDisplay activity must finish before it would be shown
        finish()
    }

    // shares that arrive while this instance is still finishing are delivered here instead of to a new instance
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        open(intent)
    }

    private fun open(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SHARED, text.orEmpty()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
