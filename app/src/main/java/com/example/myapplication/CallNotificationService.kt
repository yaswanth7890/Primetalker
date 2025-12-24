package com.example.myapplication

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.twilio.voice.Call
import com.twilio.voice.CallException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.RemoteViews

class CallNotificationService : Service() {

    companion object {
        private const val TAG = "CallNotificationService"

        const val ACTION_ACCEPT = "com.example.myapplication.ACTION_ACCEPT"
        const val ACTION_REJECT = "com.example.myapplication.ACTION_REJECT"
        const val ACTION_TOGGLE_MUTE = "com.example.myapplication.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.myapplication.ACTION_TOGGLE_SPEAKER"
        const val ACTION_END = "com.example.myapplication.ACTION_END"
        const val ACTION_STOP_ALL = "com.example.myapplication.ACTION_STOP_ALL"

        private const val ACTION_SYNC_SECONDS = "com.example.myapplication.ACTION_SYNC_SECONDS"

        private const val CHANNEL_ACTIVE = "active_call_channel"
        private const val NOTIF_ID_ONGOING = 9001

        const val INCOMING_CALL_NOTIFICATION_ID = 9999


        fun startOngoing(context: Context, callerName: String, isVideo: Boolean) {
            val i = Intent(context, CallNotificationService::class.java).apply {
                putExtra("caller", callerName)
                putExtra("isVideo", isVideo)
                action = "START_ONGOING"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, CallNotificationService::class.java))
                val nm = NotificationManagerCompat.from(context)
                nm.cancel(NOTIF_ID_ONGOING)
                nm.cancel(9999) // <-- incoming notif also cancelled
            } catch (_: Exception) {}
        }
    }


    private val handler = Handler(Looper.getMainLooper())
    private var secondsElapsed = 0L
    private var running = AtomicBoolean(false)
    private var muted = false

    // Runnable updates notification timer each second
    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            secondsElapsed++
            updateOngoingNotification(currentCaller, currentIsVideo)
            handler.postDelayed(this, 1000)
        }
    }

    private var currentCaller = "Ongoing Call"
    private var currentIsVideo = false

    private val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val caller = intent?.getStringExtra("caller") ?: "Unknown"
        val isVideo = intent?.getBooleanExtra("isVideo", false) ?: false

        // SYNC: update service timer from the activity (keeps UI and notif in sync)
        if (action == ACTION_SYNC_SECONDS) {
            val elapsedFromActivity = intent?.getLongExtra("elapsed", 0L) ?: 0L
            secondsElapsed = elapsedFromActivity
            // start ticking if not already running
            if (!running.get()) {
                running.set(true)
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
            // ensure notif reflects current value
            updateOngoingNotification(caller, isVideo)
            return START_STICKY
        }

        when (action) {
            "START_ONGOING" -> {
                Log.d(TAG, "START_ONGOING: $caller")
                startForegroundAndTimer(caller, isVideo)
                // this must be sticky so OS restarts the service after low-memory kill
                return START_STICKY
            }

            ACTION_ACCEPT -> {
                Log.d(TAG, "ACTION_ACCEPT")
                handleAccept()
                return START_NOT_STICKY
            }

            ACTION_REJECT -> {
                Log.d(TAG, "ACTION_REJECT")
                handleReject()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_MUTE -> {
                toggleMuteFromNotif()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_SPEAKER -> {
                toggleSpeakerFromNotif()
                return START_NOT_STICKY
            }

            ACTION_END -> {
                endCallFromNotif()
                return START_NOT_STICKY
            }

            ACTION_STOP_ALL -> {
                stopSelfAndCancel()
                return START_NOT_STICKY
            }

        }

        // fallback: if caller extras present, treat as start ongoing
        if (intent?.hasExtra("caller") == true) {
            startForegroundAndTimer(caller, isVideo)
            return START_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running.set(false)
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Notification channels ----------
    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val chActive = NotificationChannel(
                CHANNEL_ACTIVE,
                "Active Call",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing call"
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            }
            nm.createNotificationChannel(chActive)
        }
    }

    // ---------- Start foreground + timer for ongoing call ----------
    private fun startForegroundAndTimer(caller: String, isVideo: Boolean) {
        currentCaller = caller
        currentIsVideo = isVideo

        secondsElapsed = 0L
        running.set(true)
        handler.removeCallbacks(tick)
        handler.post(tick)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = false
        am.isMicrophoneMute = false

        val notif = buildOngoingNotification(caller, isVideo, secondsElapsed)
        startForeground(NOTIF_ID_ONGOING, notif)
    }

    private fun stopSelfAndCancel() {
        running.set(false)
        handler.removeCallbacks(tick)

        val nm = getSystemService(NotificationManager::class.java)
        try {
            nm.cancel(9999)           // remove incoming call notif
            nm.cancel(NOTIF_ID_ONGOING)
        } catch (_: Exception) {}

        stopForeground(true)
        stopSelf()
    }


    // ---------- Build / update ongoing notification ----------
    private fun updateOngoingNotification(caller: String, isVideo: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_ONGOING, buildOngoingNotification(caller, isVideo, secondsElapsed))
    }

    private fun buildOngoingNotification(caller: String, isVideo: Boolean, seconds: Long): Notification {
        // Actions
        val muteIntent = PendingIntent.getService(
            this, 200,
            Intent(this, CallNotificationService::class.java).apply { action = ACTION_TOGGLE_MUTE },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val speakerIntent = PendingIntent.getService(
            this, 201,
            Intent(this, CallNotificationService::class.java).apply { action = ACTION_TOGGLE_SPEAKER },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val endIntent = PendingIntent.getService(
            this, 202,
            Intent(this, CallNotificationService::class.java).apply { action = ACTION_END },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val timeFmt = String.format("%02d:%02d", seconds / 60, seconds % 60)
        val title = if (isVideo) "Video Call" else "Voice Call"

        val openIntent = PendingIntent.getActivity(
            this, 300,
            Intent(this, CallScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("call_mode", if (isVideo) CallMode.APP_VIDEO.name else CallMode.PSTN_AUDIO.name)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val icon = BitmapFactory.decodeResource(resources, R.drawable.call_img)

        return NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(if (isVideo) R.drawable.ic_video_camera else R.drawable.call_img)
            .setLargeIcon(icon)
            .setContentTitle("$title with $caller")
            .setContentText("Duration: $timeFmt")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic_on, if (muted) "Unmute" else "Mute", muteIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Speaker", speakerIntent)
            .addAction(R.drawable.ic_call_end, "End", endIntent)
            .build()
    }

    // ---------- Notification action handlers ----------
    private fun toggleMuteFromNotif() {
        try {
            val call = CallHolder.activeCall
            if (call != null) {
                muted = !muted
                call.mute(muted)
                Log.d(TAG, if (muted) "Muted from notif" else "Unmuted from notif")
                updateOngoingNotification(currentCaller, currentIsVideo)
            } else {
                Log.w(TAG, "toggleMute: no active call")
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleMuteFromNotif error: ${e.message}")
        }
    }

    private fun toggleSpeakerFromNotif() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val newState = !am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            am.isSpeakerphoneOn = newState
            Log.d(TAG, if (newState) "Speaker ON from notif" else "Speaker OFF from notif")
        } catch (e: Exception) {
            Log.e(TAG, "toggleSpeakerFromNotif error: ${e.message}")
        }
    }

    private fun endCallFromNotif() {
        try {
            CallHolder.activeCall?.disconnect()
            CallHolder.activeCall = null
            notifyBackendEndCall()
        } catch (e: Exception) {
            Log.e(TAG, "endCallFromNotif error: ${e.message}")
        } finally {
            stopSelfAndCancel()
        }
    }

    // ---------- Handle Accept (from notification action) ----------
    private fun handleAccept() {
        // 🔥 Ensure any ringtone/vibration from IncomingCallService is stopped
        try {
            val stopIntent = Intent(this, IncomingCallService::class.java)
            stopService(stopIntent)
        } catch (_: Exception) {}

        try {
            // 💥 Kill incoming call notification immediately
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(9999)
            // If Twilio CallInvite exists, accept directly
            val invite: com.twilio.voice.CallInvite? = try { IncomingCallHolder.invite } catch (_: Exception) { null }

            if (invite != null) {
                // Accept right here using Twilio API
                try {
                    invite.accept(this, object : Call.Listener {
                        override fun onConnected(call: Call) {
                            Log.d(TAG, "Accepted incoming invite - connected")
                            CallHolder.activeCall = call
                            IncomingCallHolder.invite = null

                            // Start ongoing UI & notif
                            startForegroundAndTimer(CallHolder.callerDisplayName ?: currentCaller, false)

                            // Launch call screen activity
                            val i = Intent(this@CallNotificationService, CallScreenActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                )
                                putExtra("call_mode", CallMode.PSTN_AUDIO.name)
                                putExtra("display_name", CallHolder.callerDisplayName ?: currentCaller)
                            }
                            startActivity(i)

                        }

                        override fun onConnectFailure(call: Call, e: CallException) {
                            Log.e(TAG, "Accept -> connect failure: ${e.message}")
                            stopSelfAndCancel()
                        }

                        override fun onDisconnected(call: Call, e: CallException?) {
                            Log.d(TAG, "Call disconnected after accept")
                            stopSelfAndCancel()
                        }

                        override fun onReconnecting(call: Call, e: CallException) {}
                        override fun onReconnected(call: Call) {}
                        override fun onRinging(call: Call) {}
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "Error accepting invite: ${e.message}")
                    stopSelfAndCancel()
                }
            } else {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(9999)

                // No invite; open IncomingCallActivity so user can accept there
                val i = Intent(this, IncomingCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("incoming_type", "VOICE")
                    putExtra("from", currentCaller)
                }
                startActivity(i)
                // Let incoming activity handle the acceptance and start ongoing notif
                stopForeground(false) // keep service alive but remove incoming fullscreen
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAccept exception: ${e.message}")
        }
    }

    // ---------- Handle Reject (from notification action) ----------
    private fun handleReject() {
        try {
            // If we have a CallInvite, reject it
            try {
                IncomingCallHolder.invite?.reject(this)
                IncomingCallHolder.invite = null
            } catch (_: Exception) {}

            // If an active call exists, disconnect
            try {
                CallHolder.activeCall?.disconnect()
                CallHolder.activeCall = null
            } catch (_: Exception) {}

            // Notify backend to end call if you use /end-call endpoint
            notifyBackendEndCall()

        } catch (e: Exception) {
            Log.e(TAG, "handleReject exception: ${e.message}")
        } finally {
            stopSelfAndCancel()
        }
    }

    // ---------- optional backend notification to tell remote side to end call ----------
    private fun notifyBackendEndCall() {
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val stored = prefs.getString("identity", "") ?: ""
            val fromDigits = stored.replace("[^0-9]".toRegex(), "")

            // try to derive 'to' from CallHolder.callerDisplayName or nothing
            val toCandidate = CallHolder.callerDisplayName ?: ""
            val toE164 = if (toCandidate.startsWith("+")) toCandidate else "+" + toCandidate.filter { it.isDigit() }
            val toDigits = toE164.replace("[^0-9]".toRegex(), "")

            val payload = JSONObject().apply {
                put("fromIdentity", fromDigits)
                put("toIdentity", toDigits)
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("https://nodical-earlie-unyieldingly.ngrok-free.dev")
                .post(body)
                .build()

            client.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w(TAG, "notifyBackendEndCall failed: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    Log.d(TAG, "notifyBackendEndCall response: ${response.code}")
                    response.close()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "notifyBackendEndCall exception: ${e.message}")
        }
    }
}
