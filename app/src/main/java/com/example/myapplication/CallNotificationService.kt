package com.example.myapplication

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.core.content.ContextCompat

class CallNotificationService : Service() {

    companion object {
        private const val TAG = "CallNotificationService"

        const val ACTION_ACCEPT = "com.example.myapplication.ACTION_ACCEPT"
        const val ACTION_REJECT = "com.example.myapplication.ACTION_REJECT"
        const val ACTION_TOGGLE_MUTE = "com.example.myapplication.ACTION_TOGGLE_MUTE"
        const val ACTION_TOGGLE_SPEAKER = "com.example.myapplication.ACTION_TOGGLE_SPEAKER"
        const val ACTION_END = "com.example.myapplication.ACTION_END"
        const val ACTION_STOP_ALL = "com.example.myapplication.ACTION_STOP_ALL"
        const val ACTION_STOP_CALL_NOTIFICATION = "com.example.myapplication.ACTION_STOP_CALL_NOTIFICATION"
        private const val ACTION_SYNC_SECONDS = "com.example.myapplication.ACTION_SYNC_SECONDS"

        private const val CHANNEL_ACTIVE = "active_call_channel"
        private const val NOTIF_ID_ONGOING = 9001

        const val INCOMING_CALL_NOTIFICATION_ID = 9999
        private var actionHandled=false
        private var videoRoom: String? = null
        private var videoToken: String? = null
        var globalSeconds: Long = 0L
        private var backupInvite: com.twilio.voice.CallInvite? = null
        const val ACTION_SYNC_UI = "com.example.myapplication.ACTION_SYNC_UI"
        const val ACTION_STORE_INVITE = "com.example.myapplication.ACTION_STORE_INVITE"
        fun startOngoing(context: Context, callerName: String, isVideo: Boolean) {

            if (
                CallStateManager.state != CallStateManager.State.CONNECTED &&
                CallStateManager.state != CallStateManager.State.VIDEO_CONNECTED &&
                CallStateManager.state != CallStateManager.State.VIDEO_OUTGOING
            ) return
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

    private var latestSeconds: Long = 0L

    fun getCurrentSeconds(): Long {
        return latestSeconds
    }

    private val handler = Handler(Looper.getMainLooper())
    private var secondsElapsed = 0L
    private var running = AtomicBoolean(false)


    // Runnable updates notification timer each second
    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            secondsElapsed++
            latestSeconds = secondsElapsed
            globalSeconds = secondsElapsed
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
        actionHandled = false
        Log.d(TAG, "Service created")
        val channelId = "call_notifications"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Call Notifications",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Call Service")
            .setContentText("Running")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        startForeground(1001, notification)
        ContextCompat.registerReceiver(
            this,
            stopReceiver,
            IntentFilter(ACTION_STOP_CALL_NOTIFICATION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val caller = intent?.getStringExtra("caller")
            ?: currentCaller
            ?: "Unknown"
        videoRoom = intent?.getStringExtra("room_name")
        videoToken = intent?.getStringExtra("access_token")
        videoRoom = intent?.getStringExtra("room_name") ?: videoRoom
        videoToken = intent?.getStringExtra("access_token") ?: videoToken
        val isVideo =
            intent?.getBooleanExtra("isVideo", false)
                ?: (intent?.getStringExtra("incoming_type") == "VIDEO")
        currentCaller = caller
        currentIsVideo = isVideo
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
            return START_NOT_STICKY
        }

        if (action == ACTION_STORE_INVITE) {
            backupInvite = intent?.getParcelableExtra("invite")
            Log.d(TAG, "Invite stored inside service backup")
            return START_NOT_STICKY
        }

        when (action) {
            "START_ONGOING" -> {
                Log.d(TAG, "START_ONGOING: $caller")
                startForegroundAndTimer(caller, isVideo)
                // this must be sticky so OS restarts the service after low-memory kill
                return START_NOT_STICKY
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
            return START_NOT_STICKY
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {

        running.set(false)
        handler.removeCallbacksAndMessages(null)

        try {
            stopForeground(true)
        } catch (_: Exception) {}

        try {
            unregisterReceiver(stopReceiver)
        } catch (_: Exception) {}
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
        globalSeconds = 0L
        latestSeconds = 0L
        running.set(true)
        handler.removeCallbacks(tick)
        handler.post(tick)

        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.mode = AudioManager.MODE_IN_COMMUNICATION

        CallHolder.isSpeakerOn = false
        CallHolder.isMuted = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

            val earpiece = am.availableCommunicationDevices.firstOrNull {
                it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            }

            if (earpiece != null) {
                am.setCommunicationDevice(earpiece)
            } else {
                am.clearCommunicationDevice()
            }

        } else {
            am.isSpeakerphoneOn = false
        }

        am.isMicrophoneMute = false

        val notif = buildOngoingNotification(caller, isVideo, secondsElapsed)
        startForeground(NOTIF_ID_ONGOING, notif)
    }

    private fun stopSelfAndCancel() {

        running.set(false)
        handler.removeCallbacks(tick)

        secondsElapsed = 0L
        latestSeconds = 0L
        globalSeconds = 0L



        CallHolder.isMuted = false
        CallHolder.isSpeakerOn = false
        CallHolder.callerDisplayName = null
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.cancel(9999)
            nm.cancel(NOTIF_ID_ONGOING)
            nm.cancel(2001)
        } catch (_: Exception) {}

        // 🔥 STOP foreground service also
        try {
            stopService(Intent(this, CallForegroundService::class.java))
        } catch (_: Exception) {}

        stopForeground(true)
        NotificationManagerCompat.from(this).cancelAll()
        stopSelf()
    }


    // ---------- Build / update ongoing notification ----------
    private fun updateOngoingNotification(caller: String, isVideo: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_ONGOING, buildOngoingNotification(caller, isVideo, secondsElapsed))
    }

    private fun buildOngoingNotification(caller: String, isVideo: Boolean, seconds: Long): Notification {
        Log.d("SPEAKER_DEBUG", "CallHolder.isSpeakerOn = ${CallHolder.isSpeakerOn}")
        val speakerState = CallHolder.isSpeakerOn
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
                putExtra("display_name", caller)
                putExtra("start_timer_now", false)

            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val icon = BitmapFactory.decodeResource(resources, R.drawable.call_img)
        val cleanNumber = caller
            .replace("client:", "")
            .replace("[^0-9]".toRegex(), "")

        val contactName = PhoneUtils.getContactName(this, cleanNumber)

        val displayName =
            if (contactName != null)
                "$contactName\n${PhoneUtils.formatInternational(cleanNumber)}"
            else
                PhoneUtils.formatInternational(cleanNumber)
        return NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(if (isVideo) R.drawable.ic_video_camera else R.drawable.call_img)
            .setLargeIcon(icon)
            .setContentTitle(displayName)
            .setContentText("$title • $timeFmt")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent)
            .addAction(
                if (CallHolder.isMuted)
                    R.drawable.ic_mic_off
                else
                    R.drawable.ic_mic_on,
                if (CallHolder.isMuted) "Unmute" else "Mute",
                muteIntent
            )
            .addAction(
                if (CallHolder.isSpeakerOn)
                    android.R.drawable.ic_lock_silent_mode_off
                else
                    android.R.drawable.ic_lock_silent_mode,
                if (CallHolder.isSpeakerOn) "Speaker Off" else "Speaker On",
                speakerIntent
            )
            .addAction(R.drawable.ic_call_end, "End", endIntent)
            .build()
    }

    // ---------- Notification action handlers ----------
    private fun toggleMuteFromNotif() {
        try {
            val call = CallStateManager.currentCall ?: return

            CallHolder.isMuted = !CallHolder.isMuted
            call.mute(CallHolder.isMuted)

// 🔥 ALSO HANDLE VIDEO
            CallStateManager.localAudioTrack?.enable(!CallHolder.isMuted)

            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isMicrophoneMute = CallHolder.isMuted

            updateOngoingNotification(currentCaller, currentIsVideo)

            Handler(Looper.getMainLooper()).post {
                val intent = Intent(ACTION_SYNC_UI)
                intent.setPackage(packageName)   // 🔥 VERY IMPORTANT
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleMuteFromNotif error: ${e.message}")
        }
    }

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            Log.d(TAG, "STOP_CALL_NOTIFICATION received")

            stopSelfAndCancel()
        }
    }

    private fun toggleSpeakerFromNotif() {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.mode = AudioManager.MODE_IN_COMMUNICATION

            CallHolder.isSpeakerOn = !CallHolder.isSpeakerOn

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {

                val speakerDevice = am.availableCommunicationDevices.firstOrNull {
                    it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }

                if (CallHolder.isSpeakerOn && speakerDevice != null) {
                    am.setCommunicationDevice(speakerDevice)
                } else {
                    val earpiece = am.availableCommunicationDevices.firstOrNull {
                        it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                    }

                    if (earpiece != null) {
                        am.setCommunicationDevice(earpiece)
                    } else {
                        am.clearCommunicationDevice()
                    }
                }

            } else {
                am.isSpeakerphoneOn = CallHolder.isSpeakerOn
            }

            updateOngoingNotification(currentCaller, currentIsVideo)

            Handler(Looper.getMainLooper()).post {
                val intent = Intent(ACTION_SYNC_UI)
                intent.setPackage(packageName)   // 🔥 VERY IMPORTANT
                sendBroadcast(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "toggleSpeakerFromNotif error: ${e.message}")
        }
    }

    private fun endCallFromNotif() {

        Log.d(TAG, "🔥 END FROM NOTIFICATION")

        try {

            // 🔥 END CALL EVERYWHERE
            CallStateManager.endCall(applicationContext)



        } catch (e: Exception) {
            Log.e(TAG, "endCallFromNotif error: ${e.message}")
        } finally {
            stopSelfAndCancel()
        }
    }

    // ---------- Handle Accept (from notification action) ----------
    private fun handleAccept() {
        startForeground(
            2002,
            NotificationCompat.Builder(this, "call_notifications")
                .setContentTitle("Connecting call")
                .setContentText("Opening call screen...")
                .setSmallIcon(R.drawable.call_img)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
        )
        if (CallHolder.isOutgoing) {
            Log.d(TAG, "Cross-call notification ignored")
            stopSelfAndCancel()
            return
        }
        if (actionHandled) return
        actionHandled = true
        try {
            stopService(Intent(this, IncomingCallService::class.java))
        } catch (_: Exception) {}
        if (!currentIsVideo) {
            val closeIntent = Intent("ACTION_CLOSE_INCOMING_UI")
            closeIntent.setPackage(packageName)
            sendBroadcast(closeIntent)
        }
// 🔥 Force stop any ringtone/vibration from activity
        try {
            val stopIntent = Intent("STOP_INCOMING_RING")
            stopIntent.setPackage(packageName)
            sendBroadcast(stopIntent)
        } catch (_: Exception) {}
        try {
            // 1️⃣ Stop ringtone service
            try {
                stopService(Intent(this, IncomingCallService::class.java))
            } catch (_: Exception) {}

            // 2️⃣ Kill incoming notification immediately
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(INCOMING_CALL_NOTIFICATION_ID)

            val invite = IncomingCallHolder.invite ?: backupInvite

            if (invite == null) {
                Log.e(TAG, "No CallInvite available")
                stopSelfAndCancel()
                return
            }



            if (currentIsVideo) {

                Log.d(TAG, "🎥 Accepting VIDEO call from notification")

                // 🔥 STOP RING FIRST
                try {
                    stopService(Intent(this, IncomingCallService::class.java))
                } catch (_: Exception) {}

                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(INCOMING_CALL_NOTIFICATION_ID)
                } catch (_: Exception) {}

                // 🔥 IMPORTANT: UPDATE STATE MANAGER
                CallStateManager.isVideoCall = true
                CallStateManager.state = CallStateManager.State.VIDEO_CONNECTED
                CallStateManager.caller = currentCaller
                CallStateManager.videoRoom = videoRoom
                CallStateManager.videoToken = videoToken

                // 🔥 OPEN CALL SCREEN (THIS WILL HANDLE CONNECT)
                val intent = Intent(this, CallScreenActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )

                    putExtra("call_mode", CallMode.APP_VIDEO.name)
                    putExtra("display_name", currentCaller)
                    putExtra("room_name", videoRoom)
                    putExtra("access_token", videoToken)
                    putExtra("start_timer_now", true)
                }
                intent.putExtra("opened_from_notification", true)
                startActivity(intent)

                stopSelf()
                return
            }

// 2️⃣ THEN ACCEPT CALL
            CallStateManager.acceptCall(applicationContext)

// 2️⃣ Start ongoing notification (already correct)
            CallNotificationService.startOngoing(
                applicationContext,
                currentCaller,
                false
            )

// ❌ DO NOT OPEN ACTIVITY HERE

            stopSelf()



            stopSelf()
            return

        } catch (e: Exception) {
            Log.e(TAG, "handleAccept error: ${e.message}")
            stopSelfAndCancel()
        }
    }

    // ---------- Handle Reject (from notification action) ----------
    private fun handleReject() {
        if (actionHandled) return
        actionHandled = true
        if (CallStateManager.isVideoCall){

            Log.d(TAG, "Video call rejected from notification")

            val openIntent = Intent(this, IncomingCallActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                )

                putExtra("incoming_type", "VIDEO")
                putExtra("from", currentCaller)
            }

            startActivity(openIntent)

            stopSelf()
            return
        }
        val closeIntent = Intent("ACTION_CLOSE_INCOMING_UI")
        closeIntent.setPackage(packageName)
        sendBroadcast(closeIntent)
        try {
            // 1️⃣ Stop ringtone
            stopService(Intent(this, IncomingCallService::class.java))

            // 2️⃣ Cancel incoming notification
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(INCOMING_CALL_NOTIFICATION_ID)

            // 3️⃣ Reject invite (important)
            val invite = IncomingCallHolder.invite ?: backupInvite
            invite?.reject(this)

            IncomingCallHolder.invite = null
            backupInvite = null

            // 4️⃣ Disconnect if somehow active
            CallHolder.activeCall?.disconnect()
            CallHolder.activeCall = null


            // 5️⃣ 🔥 SEND BACKEND END CALL (VERY IMPORTANT)
            CallHolder.callerDisplayName = currentCaller
            notifyBackendEndCall()

        } catch (e: Exception) {
            Log.e(TAG, "handleReject error: ${e.message}")
        } finally {
            stopSelfAndCancel()
        }
    }



    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)

        Log.d(TAG, "App removed from recent. Keeping call alive.")

        if (CallHolder.activeCall != null) {

            // Restart service immediately
            val restartIntent = Intent(applicationContext, CallNotificationService::class.java)
            restartIntent.action = "START_ONGOING"
            restartIntent.putExtra("caller", currentCaller)
            restartIntent.putExtra("isVideo", currentIsVideo)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent)
            } else {
                startService(restartIntent)
            }
        }
    }



    // ---------- optional backend notification to tell remote side to end call ----------
    private fun notifyBackendEndCall() {

        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            // My identity (digits only)
            val fromDigits = prefs.getString("identity", "")
                ?.replace("[^0-9]".toRegex(), "")
                ?: ""

            // 🔥 Get REAL remote identity from active call first
            val remoteIdentityRaw = CallStateManager.caller ?: ""

            val toDigits = remoteIdentityRaw
                .replace("client:", "")
                .replace("[^0-9]".toRegex(), "")

            if (toDigits.isBlank()) {
                Log.e(TAG, "notifyBackendEndCall: toDigits empty — aborting")
                return
            }

            val payload = JSONObject().apply {
                put("fromIdentity", fromDigits)
                put("toIdentity", toDigits)
            }

            val body = payload.toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("https://nodical-earlie-unyieldingly.ngrok-free.dev/end-call")
                .post(body)
                .build()

            client.newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w(TAG, "END_CALL notify failed: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    Log.d(TAG, "END_CALL notified: ${response.code}")
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "notifyBackendEndCall error: ${e.message}")
        }
    }
}