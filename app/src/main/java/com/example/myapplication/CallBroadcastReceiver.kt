package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class CallBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent == null || context == null) return

        val callerNumber = intent.getStringExtra("caller_number") ?: "Unknown"
        val callType = intent.getStringExtra("call_type") ?: "Audio"

        Log.d("CallBroadcastReceiver", "Incoming call from: $callerNumber")

        val incomingIntent = Intent(context, IncomingCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("caller_number", callerNumber)
            putExtra("call_type", callType)
        }
        context.startActivity(incomingIntent)
    }
}
