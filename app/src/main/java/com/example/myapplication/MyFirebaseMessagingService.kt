package com.example.myapplication


import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import android.util.Base64
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.twilio.voice.CallException
import com.twilio.voice.CallInvite
import com.twilio.voice.CancelledCallInvite
import com.twilio.voice.MessageListener
import com.twilio.voice.Voice
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import android.media.AudioFormat
import android.media.AudioManager
import android.os.PowerManager
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import com.example.myapplication.db.DbProvider
import com.example.myapplication.db.ChatEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

import kotlinx.coroutines.launch



class MyFirebaseMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()

        // 🔥 REQUIRED for Room usage inside FCM
        DbProvider.init(applicationContext)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                CHAT_CHANNEL_ID,
                "Chat Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            nm.createNotificationChannel(channel)
        }
    }


    private val CHANNEL_ID = "incoming_call_channel"
    private val CHAT_CHANNEL_ID = "chat_messages_channel"
    private val CHAT_GROUP = "chat_messages_group"
    private val chatNotificationStore = mutableMapOf<String, MutableList<String>>()
    private val BASE_URL = "https://nodical-earlie-unyieldingly.ngrok-free.dev" // 🔧 change if using ngrok or deployed server

    override fun onNewToken(token: String) {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val twilioIdentity = prefs.getString("twilio_identity", null)?.replace("[^0-9]".toRegex(), "")
        val displayPhone = prefs.getString("identity_display", null)?.let {
            if (!it.startsWith("+")) "+$it" else it
        }


        if (!twilioIdentity.isNullOrBlank() && !displayPhone.isNullOrBlank()) {
            val json = JSONObject().apply {
                put("identity", twilioIdentity) // digits-only
                put("phone", displayPhone)      // +E.164
                put("fcm_token", token)
            }

            val reqBody = json.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE_URL/register")
                .post(reqBody)
                .build()

            OkHttpClient().newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("FCM", "❌ Register failed: ${e.message}")
                }

                override fun onResponse(call: Call, response: Response) {
                    Log.d("FCM", "✅ Registered for calls: ${response.code}")
                }
            })
        } else {
            Log.w("FCM", "Missing twilio_identity or identity_display; cannot register FCM for calls")
        }

    }




    override fun onMessageReceived(msg: RemoteMessage) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "myapp:FCM_WAKE"
        )
        wl.acquire(10*1000L)
        val data = msg.data ?: return
        Log.d("FCM", "🔥 Incoming FCM data: $data")

// 🔥 STORE REAL CALLER FROM FCM
        if (data["caller_id"] != null) {
            IncomingCallHolder.callerId = data["caller_id"]
        }
        // Try to let Twilio parse the payload so we can obtain CallInvite object if present.
        try {
            Voice.handleMessage(this, data, object : MessageListener {
                override fun onCallInvite(callInvite: CallInvite) {
                    Log.d("Twilio", "Invite → CallStateManager")
                    CallStateManager.onIncomingInvite(applicationContext, callInvite)
                }

                override fun onCancelledCallInvite(cancelledCallInvite: CancelledCallInvite, callException: CallException?) {
                    Log.d("Twilio", "❌ Cancelled invite from ${cancelledCallInvite.from}")
                    // remove any existing incoming notification
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(9999)

                }
            })
        } catch (e: Exception) {
            Log.w("FCM", "Voice.handleMessage failed / not Twilio payload: ${e.message}")
        }

        // Regardless of above, also support your server-defined custom payloads
        when (data["type"]) {
            "VIDEO_INVITE" -> {

                if (CallStateManager.state != CallStateManager.State.IDLE ||
                    CallStateManager.isCallEnded) {

                    Log.e("CALL_FIX", "🚫 Ignoring VIDEO_INVITE — already ended or busy")
                    return
                }

                val from = deriveDisplayNameFromPayload(
                    data,
                    data["caller_display"] ?: data["caller_id"] ?: "Unknown"
                )

                val room = data["room"] ?: ""

                showIncomingActivity(
                    kind = "VIDEO",
                    from = from,
                    room = room,
                    token = data["token"]
                )
            }



            "USER_BUSY" -> {

                Log.d("FCM","USER_BUSY received")

                try {
                    CallStateManager.endCall(applicationContext)
                } catch (_: Exception) {}

                stopService(Intent(this, IncomingCallService::class.java))

                val busyIntent = Intent("ACTION_USER_BUSY").apply {
                    `package` = packageName
                }

                sendBroadcast(busyIntent)
            }

            "END_CALL" -> {

                Log.d("FCM","🔥 END_CALL received")

                CallStateManager.endCall(applicationContext)
            }

            "CALLEE_ANSWERED" -> {
                Log.d("FCM", "✅ Received CALLEE_ANSWERED push — starting timer broadcast")
                val intent = Intent("ACTION_CALLEE_ANSWERED").apply { `package` = packageName }
                sendBroadcast(intent)
            }



            "CHAT_MESSAGE" -> {

                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val myIdentity = PhoneUtils.normalizeIdentity(
                    prefs.getString("identity", "")!!
                )

                val peer = PhoneUtils.normalizeIdentity(
                    data["sender"]!!
                )
                val encrypted = data["original"]!!
                val text = CryptoUtils.decrypt(encrypted)

                Log.d("CHAT_DEBUG", "encrypted=$encrypted")
                Log.d("CHAT_DEBUG", "decrypted=$text")
                val isChatOpenForThisPeer = AppVisibility.currentChatPeer == peer
                val messageId = data["messageId"] ?: java.util.UUID.randomUUID().toString()
                if (!isChatOpenForThisPeer) {
                    showChatNotification(peer, text)
                }
                CoroutineScope(Dispatchers.IO).launch {

                    val dao = DbProvider.db.chatDao()

                    val exists = dao.getMessageById(messageId)

                    if (exists == null) {

                        dao.insert(
                            ChatEntity(
                                messageId = messageId,
                                myIdentity = myIdentity,
                                peerIdentity = peer,
                                fromIdentity = peer,
                                originalText = text,
                                timestamp = System.currentTimeMillis(),
                                isRead = isChatOpenForThisPeer,
                                status = "DELIVERED"
                            )
                        )

                    }

                    // 🔥 If chat is open, send READ immediately
                    if (isChatOpenForThisPeer) {

                        val readIntent = Intent("ACTION_MESSAGE_READ").apply {
                            putExtra("messageId", "ALL")
                            `package` = packageName
                        }

                        sendBroadcast(readIntent)

                        notifyReadToServer(peer)
                    }

                    // 🔥 Notify sender phone that message reached device
                    val deliveredIntent = Intent("ACTION_MESSAGE_DELIVERED").apply {
                        putExtra("messageId", messageId)
                        `package` = packageName
                    }
                    sendBroadcast(deliveredIntent)
                    notifyDeliveredToServer(messageId)

                    val refresh = Intent(ChatActions.ACTION_REFRESH_CHATLIST).apply {
                        `package` = packageName
                    }
                    sendBroadcast(refresh)
                }



                // 🔔 still broadcast (for live case)
                val i = Intent(ChatActivity.ACTION_CHAT_MESSAGE).apply {
                    putExtra("from", data["sender"])
                    putExtra("original", text) // already decrypted
                    putExtra("messageId", messageId)
                    `package` = packageName
                }
                sendBroadcast(i)

            }


            "MESSAGE_DELIVERED" -> {

                val messageId = data["messageId"] ?: return

                val intent = Intent("ACTION_MESSAGE_DELIVERED").apply {
                    putExtra("messageId", messageId)
                    `package` = packageName
                }

                sendBroadcast(intent)
            }

            "MESSAGE_READ" -> {

                val messageId = data["messageId"] ?: "ALL"

                val intent = Intent("ACTION_MESSAGE_READ").apply {
                    putExtra("messageId", messageId)
                    `package` = packageName
                }

                sendBroadcast(intent)
            }

            "LIVE_CAPTION" -> {

                val i = Intent("ACTION_LIVE_CAPTION").apply {
                    putExtra("from", data["sender"])
                    putExtra("original", data["original"])
                    putExtra("translated", data["translated"])
                    putExtra("english", data["english"])   // 🔥 ADD THIS LINE
                    `package` = packageName
                }

                sendBroadcast(i)
            }



            else -> {
                Log.d("FCM", "ℹ️ Ignored unsupported/other FCM type: ${data["type"]}")
            }
        }
        wl.release()
    }


    private fun notifyDeliveredToServer(messageId: String) {

        val json = JSONObject().apply {
            put("messageId", messageId)
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$BASE_URL/chat/delivered")
            .post(body)
            .build()

        OkHttpClient().newCall(req).enqueue(object: Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {}
        })
    }

    private fun notifyBusyToServer(caller: String) {

        try {

            val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

            val myIdentity = prefs.getString("identity", "")!!
                .replace("[^0-9]".toRegex(), "")

            val callerDigits = caller
                .replace("client:", "")
                .replace("[^0-9]".toRegex(), "")

            val json = JSONObject().apply {
                put("fromIdentity", myIdentity)
                put("toIdentity", callerDigits)
            }

            val body = json.toString()
                .toRequestBody("application/json".toMediaType())

            val req = Request.Builder()
                .url("$BASE_URL/busy-call")
                .post(body)
                .build()

            OkHttpClient().newCall(req).enqueue(object: Callback {
                override fun onFailure(call: Call, e: IOException) {}
                override fun onResponse(call: Call, response: Response) {}
            })

        } catch (e: Exception) {
            Log.e("FCM","Busy notify failed: ${e.message}")
        }
    }

    private fun deriveDisplayNameFromPayload(data: Map<String,String>, fallback: String): String {
        // 1) If server supplied a caller_display field, use it (preferred)
        val callerDisplay = data["caller_display"]
        if (!callerDisplay.isNullOrBlank()) return callerDisplay

        // 2) If caller_id looks like "client:USER_4342", strip prefix
        val callerId = data["caller_id"] ?: data["caller"] ?: ""
        if (callerId.startsWith("client:")) return callerId.removePrefix("client:")

        // 3) If looks like a Twilio number / PSTN, format it (strip country if needed or return last 4)
        if (callerId.matches(Regex("^\\+?\\d{6,}\$"))) {
            return formatPhoneForDisplay(callerId)
        }

        // 4) fallback argument (passed from caller)
        if (!fallback.isNullOrBlank()) return fallback

        // 5) ultimate fallback
        return "Unknown"
    }

    private fun formatPhoneForDisplay(e164: String): String {
        // simple friendly format - last 8 or last 4 digits
        val digits = e164.filter { it.isDigit() }
        return when {
            digits.length <= 4 -> digits
            digits.length <= 8 -> digits.takeLast(4)
            else -> "+" + digits.takeLast(8)  // keep last 8 with a plus for recognizability
        }
    }


    private fun showIncomingActivity(kind: String, from: String, room: String?,token:String?) {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val isScreenLocked = keyguardManager.isKeyguardLocked
        val isForeground = AppVisibility.isForeground(this)
// ADD THIS
        if (CallStateManager.state != CallStateManager.State.IDLE) {
            Log.e("CALL_FIX", "🚫 Blocking incoming UI — not idle")
            return
        }

        CallStateManager.state = CallStateManager.State.INCOMING
        CallStateManager.isVideoCall = true
        CallStateManager.caller = from

        // -------------------- ALWAYS show notification --------------------
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
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
                setBypassDnd(true)   // 🔥 IMPORTANT
            }

            nm.createNotificationChannel(ch)
        }


        // PendingIntent to open IncomingCallActivity
        val openUI = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("incoming_type", kind)
            val cleanNumber = from
                .replace("client:", "")
                .replace("[^0-9]".toRegex(), "")

            putExtra("from", cleanNumber)
            room?.let { putExtra("room", it) }
        }

        val openPendingIntent = PendingIntent.getActivity(
            this, 2001, openUI,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectIntent = Intent(this, CallActionReceiver::class.java).apply {
            action = CallNotificationService.ACTION_REJECT
            putExtra("caller", from)
        }

        val rejectPI = PendingIntent.getBroadcast(
            this,
            2002,
            rejectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )



        val acceptIntent = Intent(this, CallNotificationService::class.java).apply {
            action = CallNotificationService.ACTION_ACCEPT

            putExtra("caller", from)
            putExtra("isVideo", kind == "VIDEO")
            putExtra("room_name", room)
            putExtra("access_token", token)
        }

        val acceptPendingIntent = PendingIntent.getService(
            this,
            1001,
            acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contactName = PhoneUtils.getContactName(this, from)

        val displayText =
            if (contactName != null)
                "$contactName\n${PhoneUtils.formatInternational(from)}"
            else
                PhoneUtils.formatInternational(from)

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.call_img)
            .setContentTitle(if (kind == "VIDEO") "Incoming Video Call" else "Incoming Voice Call")
            .setContentText(displayText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(false)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPI)
            .addAction(android.R.drawable.ic_menu_call, "Accept", acceptPendingIntent)

// ✅ ONLY when locked
        if (isScreenLocked) {
            builder.setFullScreenIntent(openPendingIntent, true)
        }

        val notification = builder.build()
        nm.notify(9999, notification)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "myapp:CALL_WAKE"
        )
        wl.acquire(3000)


// 🔥 Start ringtone service safely
        try {
            val ringIntent = Intent(this, IncomingCallService::class.java).apply {
                val cleanNumber = from
                    .replace("client:", "")
                    .replace("[^0-9]".toRegex(), "")

                putExtra("from", cleanNumber)
                putExtra("kind", kind == "VIDEO")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(ringIntent)
            } else {
                startService(ringIntent)
            }

        } catch (e: Exception) {
            Log.e("FCM", "Failed to start IncomingCallService: ${e.message}")
        }


        // 🔥 ALWAYS OPEN UI (lock screen + foreground)
        try {
            startActivity(openUI)
        } catch (e: Exception) {
            Log.e("FCM", "Failed to launch IncomingCallActivity: ${e.message}")
        }
    }



    private fun showChatNotification(peer: String, message: String) {

        val name = PhoneUtils.getDisplayName(this, peer)

        val messages = chatNotificationStore.getOrPut(peer) { mutableListOf() }

        messages.add(message)

        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra("peer_identity", peer)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            peer.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val messagingStyle = NotificationCompat.MessagingStyle(name)

        // show only last 5 messages
        messages.takeLast(5).forEach {

            val trimmed =
                if (it.length > 80)
                    it.substring(0, 80) + "…"
                else
                    it

            messagingStyle.addMessage(trimmed, System.currentTimeMillis(), name)
        }

        val notification = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.chat_img)
            .setStyle(messagingStyle)
            .setContentTitle("$name (${messages.size} messages)")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setGroup(CHAT_GROUP)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.notify(peer.hashCode(), notification)
        showChatSummaryNotification()
    }

    private fun showChatSummaryNotification() {

        val totalChats = chatNotificationStore.size

        val summary = NotificationCompat.Builder(this, CHAT_CHANNEL_ID)
            .setSmallIcon(R.drawable.chat_img)
            .setContentTitle("$totalChats chats")
            .setContentText("New messages")
            .setGroup(CHAT_GROUP)
            .setGroupSummary(true)
            .setAutoCancel(true)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        nm.notify(0, summary)
    }


    private fun notifyReadToServer(peer: String) {

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)

        val myIdentity = PhoneUtils.normalizeIdentity(
            prefs.getString("identity", "")!!
        )

        val json = JSONObject().apply {
            put("reader", myIdentity)
            put("peer", peer)
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("$BASE_URL/chat/read")
            .post(body)
            .build()

        OkHttpClient().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}

            override fun onResponse(call: Call, response: Response) {}
        })
    }

}