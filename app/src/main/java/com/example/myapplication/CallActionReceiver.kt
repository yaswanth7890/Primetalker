package com.example.myapplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.app.NotificationManager
import android.telecom.Call
import android.telecom.CallException


class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "CallActionReceiver"
    }



    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val action = intent.action ?: return

        Log.d(TAG, "📩 Received action: $action")




        // 🔥 Always kill incoming call notification on any action
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(CallNotificationService.INCOMING_CALL_NOTIFICATION_ID)
        } catch (_: Exception) { }


        // Forward action to CallNotificationService
        val svcIntent = Intent(context, CallNotificationService::class.java).apply {
            this.action = action

            // Forward extras if present
            if (intent.hasExtra("caller"))
                putExtra("caller", intent.getStringExtra("caller"))

            if (intent.hasExtra("isVideo"))
                putExtra("isVideo", intent.getBooleanExtra("isVideo", false))

            if (intent.hasExtra("elapsed"))
                putExtra("elapsed", intent.getLongExtra("elapsed", 0L))
        }



        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(svcIntent)
        } else {
            context.startService(svcIntent)
        }

    }


}
