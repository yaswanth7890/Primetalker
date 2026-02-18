package com.example.myapplication

import android.app.ActivityManager
import android.content.Context

object AppVisibility {

    var currentChatPeer: String? = null
    fun isForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val appProcesses = manager.runningAppProcesses ?: return false

        for (process in appProcesses) {
            if (process.processName == context.packageName &&
                process.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
            ) {
                return true
            }
        }
        return false
    }
}
