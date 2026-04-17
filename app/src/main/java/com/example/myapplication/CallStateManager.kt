package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.twilio.voice.Call
import com.twilio.voice.CallException
import com.twilio.voice.CallInvite
import android.app.NotificationManager
import android.app.NotificationChannel
import android.app.Notification
import android.app.PendingIntent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.twilio.video.LocalAudioTrack
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

object CallStateManager {

    private const val TAG = "CallStateManager"

    enum class State {
        IDLE,
        INCOMING,
        OUTGOING,
        CONNECTED,
        VIDEO_OUTGOING,   // 🔥 ADD
        VIDEO_CONNECTED,
        ENDED
    }

    var state: State = State.IDLE

    var currentInvite: CallInvite? = null
        private set

    var currentCall: Call? = null
        private set

    var caller: String? = null

    var videoRoom: String? = null
    var videoToken: String? = null
    var isVideoCall: Boolean = false
    var isCallEnded = false
    var localAudioTrack: LocalAudioTrack? = null
    fun setOutgoing(call: Call) {
        currentCall = call
        state = State.OUTGOING
    }
    // ---------------- INCOMING ----------------
    fun onIncomingInvite(context: Context, callInvite: CallInvite) {

        Log.d("CallStateManager", "📞 Incoming invite received")

        // 🔥 If already in call → reject
        if (state != State.IDLE) {
            Log.d("CallStateManager", "Already in call → rejecting")
            callInvite.reject(context)
            return
        }

        // ✅ Store invite
        currentInvite = callInvite
        IncomingCallHolder.invite = callInvite

// 🔥 ALWAYS USE FCM / BACKEND VALUE

        var realCaller = IncomingCallHolder.callerId

        if (realCaller.isNullOrBlank()) {

            Handler(Looper.getMainLooper()).postDelayed({

                var retryCaller = IncomingCallHolder.callerId

                if (retryCaller.isNullOrBlank()) {
                    Log.e(TAG, "❌ FCM caller missing — delaying instead of using Twilio")
                    return@postDelayed
                }

                val digits = retryCaller
                    ?.replace("client:", "")
                    ?.replace("[^0-9]".toRegex(), "")

                caller = "+$digits"

                continueIncomingFlow(context)

            }, 500)

            return
        }
// 🔥 USE REAL CALLER FROM PAYLOAD (NOT TWILIO)

        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)





        val digits = realCaller
            ?.replace("client:", "")
            ?.replace("[^0-9]".toRegex(), "")

        val formattedNumber = "+$digits"

        caller = formattedNumber

        continueIncomingFlow(context)
        return


        state = State.INCOMING


        // 🔥 SET DISPLAY NAME HERE (THIS WAS MISSING)


        val contactName = PhoneUtils.getContactName(context, caller!!)

        CallHolder.callerDisplayName =
            if (contactName != null) {
                "$contactName\n${PhoneUtils.formatInternational(caller!!)}"
            } else {
                PhoneUtils.formatInternational(caller!!)
            }
        // 🔥 SHOW INCOMING NOTIFICATION (MAIN FIX)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "incoming_call_channel"

// Create channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                enableLights(true)
                setBypassDnd(true)
            }
            nm.createNotificationChannel(ch)
        }

// Accept Intent
        val acceptIntent = Intent(context, CallNotificationService::class.java).apply {
            action = CallNotificationService.ACTION_ACCEPT
        }
        val acceptPI = PendingIntent.getService(
            context, 1001, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

// Reject Intent
        val rejectIntent = Intent(context, CallNotificationService::class.java).apply {
            action = CallNotificationService.ACTION_REJECT
        }
        val rejectPI = PendingIntent.getService(
            context, 1002, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

// Open UI
        val openIntent = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("from", caller ?: "")
        }
        val openPI = PendingIntent.getActivity(
            context, 1003, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        Log.d("CALL_DEBUG", "FCM caller = ${IncomingCallHolder.callerId}")
        Log.d("CALL_DEBUG", "Twilio from = ${callInvite.from}")
        Log.d("CALL_DEBUG", "FINAL caller = $realCaller")
// Contact name
        val displayText = CallHolder.callerDisplayName ?: "Incoming Call"

// Build notification
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.call_img)
            .setContentTitle("Incoming Call")
            .setContentText(displayText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(openPI, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPI)
            .addAction(android.R.drawable.ic_menu_call, "Accept", acceptPI)
            .build()

        nm.notify(9999, notification)

        // 🔥 START RING SERVICE
        try {
            val ringIntent = Intent(context, IncomingCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(ringIntent)
            else
                context.startService(ringIntent)
        } catch (_: Exception) {}

// 🔥 FORCE OPEN INCOMING SCREEN (THIS IS WHAT YOU ARE MISSING)

        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("from", caller ?: "") // ✅ CLEAN NUMBER
            putExtra("incoming_type", "VOICE")
        }




    }

    private fun continueIncomingFlow(context: Context) {

        state = State.INCOMING

        val contactName = PhoneUtils.getContactName(context, caller!!)

        CallHolder.callerDisplayName =
            if (contactName != null) {
                "$contactName\n${PhoneUtils.formatInternational(caller!!)}"
            } else {
                PhoneUtils.formatInternational(caller!!)
            }

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "incoming_call_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                enableVibration(true)
                enableLights(true)
                setBypassDnd(true)
            }
            nm.createNotificationChannel(ch)
        }

        val acceptIntent = Intent(context, CallScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
            )
            action = "ACTION_ACCEPT_FROM_NOTIFICATION"
            putExtra("call_mode", "PSTN_AUDIO")
            putExtra("display_name", caller)
        }

        val acceptPI = PendingIntent.getActivity(
            context,
            1001,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(context, CallNotificationService::class.java).apply {
            action = CallNotificationService.ACTION_REJECT
        }
        val rejectPI = PendingIntent.getService(
            context, 1002, rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra("from", caller ?: "")
            putExtra("incoming_type", "VOICE")
        }
        val openPI = PendingIntent.getActivity(
            context, 1003, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayText = CallHolder.callerDisplayName ?: "Incoming Call"

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.call_img)
            .setContentTitle("Incoming Call")
            .setContentText(displayText)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(openPI, true) // 🔥 THIS IS THE REAL FIX
            .setAutoCancel(false)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPI)
            .addAction(android.R.drawable.ic_menu_call, "Accept", acceptPI)
            .build()

        nm.notify(9999, notification)

        try {
            val ringIntent = Intent(context, IncomingCallService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(ringIntent)
            else
                context.startService(ringIntent)
        } catch (_: Exception) {}

        val intent = Intent(context, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("from", caller ?: "")
            putExtra("incoming_type", "VOICE")
        }
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager

        val isLocked = keyguardManager.isKeyguardLocked

        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val isAppForeground = activityManager.runningAppProcesses?.any {
            it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                    it.processName == context.packageName
        } == true

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP,
            "myapp:callwake"
        )
        wl.acquire(3000)

// 🔥 FORCE OPEN INCOMING UI (THIS WAS MISSING)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CALL_UI", "Failed to open IncomingCallActivity: ${e.message}")
        }
    }

    // ---------------- ACCEPT ----------------
    fun acceptCall(context: Context) {

        val invite = currentInvite ?: IncomingCallHolder.invite

        if (invite == null) {
            Log.e("CallStateManager", "❌ No invite anywhere")
            return
        }

// 🔥 restore if missing
        currentInvite = invite

        Log.d("CallStateManager", "✅ Accepting call")

        invite.accept(context, object : Call.Listener {

            override fun onConnected(call: Call) {
                Log.d("CallStateManager", "🎉 Call connected")

                currentCall = call
                CallHolder.activeCall = call
                currentInvite = null
                state = State.CONNECTED
// 🔥 STOP RINGING IMMEDIATELY
                try {
                    context.stopService(Intent(context, IncomingCallService::class.java))
                } catch (_: Exception) {}

                try {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(9999)
                } catch (_: Exception) {}


// 🔥 OPEN CALL SCREEN (THIS IS MISSING)
                val intent = Intent(context, CallScreenActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra("display_name", caller)
                    putExtra("call_mode", "PSTN_AUDIO")
                    putExtra("start_timer_now", true)
                }

                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    Log.e("CallStateManager", "Failed to open CallScreen: ${e.message}")
                }



                // ✅ THIS IS THE REAL FIX
                try {

                } catch (e: Exception) {
                    Log.e("CallStateManager", "UI launch failed: ${e.message}")
                }

                // ✅ START ONGOING NOTIFICATION
                CallNotificationService.startOngoing(
                    context,
                    CallHolder.callerDisplayName ?: caller ?: "Call",
                    false
                )

                // ✅ REMOVE INCOMING NOTIFICATION
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(9999)
            }

            override fun onDisconnected(call: Call, e: CallException?) {
                Log.d(TAG, "Call disconnected callback")

                endCall(context)
            }

            override fun onConnectFailure(call: Call, e: CallException) {
                Log.e("CallStateManager", "❌ Connect failed: ${e.message}")
                cleanup()
            }

            override fun onRinging(call: Call) {}
            override fun onReconnecting(call: Call, e: CallException) {}
            override fun onReconnected(call: Call) {}
        })
    }

    // ---------------- REJECT ----------------
    fun rejectCall(context: Context) {

        currentInvite?.reject(context)

        Log.d("CallStateManager", "❌ Call rejected")

        cleanup()
    }

    // ---------------- END ----------------
    fun endCall(context: Context) {
        isCallEnded = true
        Log.d(TAG, "🔥 GLOBAL END CALL")

        try { currentCall?.disconnect() } catch (_: Exception) {}
        try { currentInvite?.reject(context) } catch (_: Exception) {}

        // 🔥 NOTIFY OTHER DEVICE FIRST (VERY IMPORTANT)
        try {
            notifyEndCallToServer(context)
        } catch (e: Exception) {
            Log.e(TAG, "END notify failed: ${e.message}")
        }
// 🔥 ALWAYS notify backend (even if UI not alive)
        try {
            notifyEndCallToServer(context)
        } catch (e: Exception) {
            Log.e(TAG, "Backend END failed: ${e.message}")
        }
// 🔥 THEN RESET STATE
        cleanup()

        // 🔥 HANDLE VIDEO CALL PROPERLY
        if (isVideoCall) {

            Log.d(TAG, "🎥 Ending VIDEO call properly")

            // Notify UI to clean video resources
            context.sendBroadcast(Intent("ACTION_END_VIDEO_CALL").apply {
                `package` = context.packageName
            })
        }

        // 🔥 STOP SERVICES
        try {
            context.stopService(Intent(context, CallNotificationService::class.java))
        } catch (_: Exception) {}

        try {
            context.stopService(Intent(context, IncomingCallService::class.java))
        } catch (_: Exception) {}

        // 🔥 REMOVE ALL NOTIFICATIONS
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(9999) // incoming
            nm.cancel(9001) // ongoing
        } catch (_: Exception) {}

        // 🔥 CLOSE ALL UI
        context.sendBroadcast(Intent("ACTION_FORCE_END_CALL").apply {
            `package` = context.packageName
        })

        Log.d(TAG, "✅ CALL FULLY TERMINATED")
    }


    fun startOutgoingVideo(context: Context, room: String, token: String, peer: String) {
        if (state != State.IDLE) {
            Log.e(TAG, "❌ Cannot start video — already in call")
            return
        }
        Log.d("CallStateManager", "🎥 Starting video call")

        isVideoCall = true
        videoRoom = room
        videoToken = token
        caller = peer

        state = State.VIDEO_OUTGOING

        // 🔥 Launch UI from ONE place
        val intent = Intent(context, CallScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("call_mode", CallMode.APP_VIDEO.name)
            putExtra("display_name", peer)
            putExtra("room_name", room)
            putExtra("access_token", token)
        }
        context.startActivity(intent)

        // 🔥 Start notification SAME as audio
        CallNotificationService.startOngoing(
            context,
            peer,
            true
        )
    }


    // ---------------- CLEANUP ----------------
    private fun cleanup() {
        currentCall = null
        currentInvite = null
        state = State.IDLE
        caller = null
        IncomingCallHolder.callerId = null
        videoRoom = null
        videoToken = null
        isVideoCall = false
        isCallEnded = false
        Log.d("CallStateManager", "🔄 Reset to IDLE")
    }

    private fun notifyEndCallToServer(context: Context) {

        try {
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            val from = prefs.getString("identity", "")!!
                .replace("[^0-9]".toRegex(), "")

            // 🔥 GET REAL REMOTE IDENTITY FROM ACTIVE CALL

            val toRaw = IncomingCallHolder.callerId
                ?.replace("[^0-9]".toRegex(), "")
                ?: caller?.replace("[^0-9]".toRegex(), "")
                ?: return

            val to = toRaw
                .toString()
                .replace("client:", "")
                .replace("[^0-9]".toRegex(), "")

            val json = org.json.JSONObject().apply {
                put("fromIdentity", from)
                put("toIdentity", to)
            }

            val body = json.toString()
                .toRequestBody("application/json".toMediaType())

            val req = okhttp3.Request.Builder()
                .url("https://nodical-earlie-unyieldingly.ngrok-free.dev/end-call")
                .post(body)
                .build()

            okhttp3.OkHttpClient().newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    Log.e(TAG, "❌ END notify failed")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    Log.d(TAG, "✅ END notify sent")
                    response.close()
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "notifyEndCall error: ${e.message}")
        }

    }
}