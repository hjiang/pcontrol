package com.pcontrol.app

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen activity shown when a limit is reached.
 * Non-dismissable — pressing back goes to the home screen.
 */
class BlockedActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val message = intent.getStringExtra("message") ?: "Time limit reached"
        val subject = intent.getStringExtra("subject") ?: ""

        setContentView(R.layout.activity_blocked)

        findViewById<TextView>(R.id.blocked_message)?.text = message
        findViewById<TextView>(R.id.blocked_subject)?.text = subject
    }

    override fun onBackPressed() {
        // Go home instead of dismissing the block
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
    }
}
