package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class EndCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        Log.d("EndCallReceiver", "📴 Global END_CALL broadcast received")

        // Launch CallScreenActivity only to trigger its finishCallGracefully()
        val i = Intent(context, CallScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("force_end", true)
        }
        context?.startActivity(i)
    }
}
