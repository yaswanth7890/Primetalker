package com.example.myapplication


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
    }


    private val CHANNEL_ID = "incoming_call_channel"
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
        val data = msg.data ?: return
        Log.d("FCM", "🔥 Incoming FCM data: $data")



        // Try to let Twilio parse the payload so we can obtain CallInvite object if present.
        try {
            Voice.handleMessage(this, data, object : MessageListener {
                override fun onCallInvite(callInvite: CallInvite) {
                    Log.d("Twilio", "📞 CallInvite parsed by SDK from: ${callInvite.from}")
                    // Save invite for later accept/reject
                    IncomingCallHolder.invite = callInvite

                    // ❌ DO NOT open UI here
                    Log.d("Twilio", "CallInvite received, waiting for FCM UI trigger")
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
                val from = deriveDisplayNameFromPayload(data, data["caller_display"] ?: data["caller_id"] ?: "Unknown")
                val room = data["room"] ?: ""
                showIncomingActivity(kind = "VIDEO", from = from, room = room)
            }

            "VOICE_INVITE" -> {
                // fallback path in case server sends a custom voice invite rather than Twilio format
                val from = deriveDisplayNameFromPayload(data, data["caller_display"] ?: data["caller_id"] ?: "Unknown")
                showIncomingActivity(kind = "VOICE", from = from, room = null)
            }

            "END_CALL" -> {
                Log.d("FCM", "📴 Received END_CALL push — closing any active call UI")
                val closeIntent = Intent("ACTION_FORCE_END_CALL").apply { `package` = packageName }
                sendBroadcast(closeIntent)
            }

            "CALLEE_ANSWERED" -> {
                Log.d("FCM", "✅ Received CALLEE_ANSWERED push — starting timer broadcast")
                val intent = Intent("ACTION_CALLEE_ANSWERED").apply { `package` = packageName }
                sendBroadcast(intent)
            }



            "CHAT_MESSAGE" -> {

                val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
                val myIdentity = prefs.getString("identity", "")!!
                    .replace("\\D".toRegex(), "")

                val peer = data["sender"]!!.replace("\\D".toRegex(), "")
                val text = data["original"]!!
                val isChatOpenForThisPeer = AppVisibility.currentChatPeer == peer
                CoroutineScope(Dispatchers.IO).launch {
                    DbProvider.db.chatDao().insert(
                        ChatEntity(
                            myIdentity = myIdentity,
                            peerIdentity = peer,
                            fromIdentity = peer,
                            originalText = text,
                            isRead = isChatOpenForThisPeer
                        )
                    )
                    val refresh = Intent(ChatActions.ACTION_REFRESH_CHATLIST).apply {
                        `package` = packageName
                    }
                    sendBroadcast(refresh)

                }



                // 🔔 still broadcast (for live case)
                val i = Intent(ChatActivity.ACTION_CHAT_MESSAGE).apply {
                    putExtra("from", data["sender"])
                    putExtra("original", data["original"])
                    `package` = packageName
                }
                sendBroadcast(i)

            }


            else -> {
                Log.d("FCM", "ℹ️ Ignored unsupported/other FCM type: ${data["type"]}")
            }
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


    private fun showIncomingActivity(kind: String, from: String, room: String?) {
        val isForeground = AppVisibility.isForeground(this)

        // -------------------- ALWAYS show notification --------------------
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "incoming_call_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId,
                "Incoming Calls",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }

        // PendingIntent to open IncomingCallActivity
        val openUI = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("incoming_type", kind)
            putExtra("from", from)
            room?.let { putExtra("room", it) }
        }

        val openPendingIntent = PendingIntent.getActivity(
            this, 2001, openUI,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val rejectPI = PendingIntent.getBroadcast(
            this, 2002,
            Intent(CallNotificationService.ACTION_REJECT).apply { `package` = packageName },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val acceptPI = PendingIntent.getBroadcast(
            this, 2003,
            Intent(CallNotificationService.ACTION_ACCEPT).apply { `package` = packageName },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )



        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.call_img)
            .setContentTitle(if (kind == "VIDEO") "Incoming Video Call" else "Incoming Voice Call")
            .setContentText(from)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setAutoCancel(false)
            .setOngoing(true)
            .setFullScreenIntent(openPendingIntent, false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Reject", rejectPI)
            .addAction(android.R.drawable.ic_menu_call, "Answer", acceptPI)
            .setContentIntent(openPendingIntent)   // <--- clicking anywhere opens UI
            .build()

        nm.notify(9999, notification)

        // -------------------- OPEN UI ONLY IF APP IS FOREGROUND --------------------
        if (isForeground) {
            startActivity(openUI)
        }
    }





}