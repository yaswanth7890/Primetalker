package com.example.myapplication

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat

class IncomingCallService : Service() {

    private var ringtone: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val caller = intent?.getStringExtra("from") ?: "Unknown"
        val isVideo = intent?.getBooleanExtra("kind", false) ?: false

        startForeground(9002, buildNotification(caller, isVideo))

        startRingtone()
        startVibration()

        return START_STICKY
    }

    private fun buildNotification(caller: String, isVideo: Boolean): Notification {
        return NotificationCompat.Builder(this, "incoming_call_channel")
            .setSmallIcon(R.drawable.call_img)
            .setContentTitle(if (isVideo) "Incoming Video Call" else "Incoming Voice Call")
            .setContentText(caller)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                "incoming_call_channel",
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            )
            ch.lightColor = Color.GREEN
            ch.enableLights(true)
            ch.enableVibration(true)

            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(ch)
        }
    }

    private fun startRingtone() {
        try {
            ringtone = MediaPlayer.create(this, android.provider.Settings.System.DEFAULT_RINGTONE_URI)
            ringtone?.isLooping = true
            ringtone?.start()
        } catch (_: Exception) {}
    }

    private fun startVibration() {
        vibrator = getSystemService(Vibrator::class.java)
        val pattern = longArrayOf(0, 800, 800)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    override fun onDestroy() {
        try { ringtone?.stop(); ringtone?.release() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}