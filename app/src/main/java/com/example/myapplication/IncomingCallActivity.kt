package com.example.myapplication

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.*
import android.util.Log
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.twilio.voice.Call
import com.twilio.voice.CallException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.IntentFilter



class IncomingCallActivity : AppCompatActivity() {

    private lateinit var txtCallerNumber: TextView
    private lateinit var txtCallType: TextView
    private lateinit var imgCaller: ImageView
    private lateinit var btnAcceptCall: ImageButton
    private lateinit var btnRejectCall: ImageButton

    private var ringtonePlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    private val BASE_URL = "https://nodical-earlie-unyieldingly.ngrok-free.dev"

    private val PERMISSIONS_REQUEST_CODE = 123
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.CAMERA
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_incoming_call)

        val directInvite = intent.getParcelableExtra<com.twilio.voice.CallInvite>("INCOMING_CALL_INVITE")
        if (directInvite != null) {
            Log.d("IncomingCall", "✅ Received CallInvite via Intent")
            IncomingCallHolder.invite = directInvite
        }

        // stop service ringtone if running
        try {
            val stopIntent = Intent(this, CallNotificationService::class.java).apply { action = "STOP_RING" }
            startService(stopIntent)
        } catch (_: Exception) {}


        txtCallerNumber = findViewById(R.id.txtCallerNumber)
        txtCallType = findViewById(R.id.txtCallType)
        imgCaller = findViewById(R.id.imgCaller)
        btnAcceptCall = findViewById(R.id.btnAcceptCall)
        btnRejectCall = findViewById(R.id.btnRejectCall)

        wakeUpScreen()

        checkAndRequestPermissions()
        ContextCompat.registerReceiver(
            this,
            stopRingReceiver,
            IntentFilter("STOP_INCOMING_RING"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        val type = intent.getStringExtra("incoming_type") ?: "VOICE"
        val from = intent.getStringExtra("from") ?: "Unknown"
        val room = intent.getStringExtra("room")
        val openedFromNotification =
            intent.getBooleanExtra("opened_from_notification", false)

        // ✅ Clean up caller label (remove "client:" prefixes)
        val rawNumber =
            intent.getStringExtra("from")
                ?: CallHolder.callerDisplayName
                ?: "Unknown"

        val contactName = PhoneUtils.getContactName(this, rawNumber)

        if (contactName != null) {
            txtCallerNumber.text = "$contactName\n${PhoneUtils.formatInternational(rawNumber)}"
        } else {
            txtCallerNumber.text = PhoneUtils.formatInternational(rawNumber)
        }

        txtCallType.text = if (type == "VIDEO") "Video Call" else "Audio Call"

        Log.d("TwilioDebug", "📨 IncomingCallActivity: type=$type from=$from room=$room")

        // ---------------- Reject Call ----------------
        btnRejectCall.setOnClickListener {

            CallHolder.isOutgoing = false

            try {
                IncomingCallHolder.invite?.reject(this)
                Log.d("IncomingCall", "📞 Call rejected locally")
            } catch (e: Exception) {
                Log.e("IncomingCall", "❌ Reject error: ${e.message}")
            }
            IncomingCallHolder.invite = null

            // 🔥 Notify backend so caller also ends
            CallHolder.activeCall?.disconnect()
            CallHolder.activeCall = null
            notifyEndCall(from)
            finish()
        }

        // ---------------- Accept Call ----------------
        btnAcceptCall.setOnClickListener {


            if (type == "VIDEO") {
                handleVideoAccept(from, room)
                return@setOnClickListener
            }

            handleVoiceAccept()
        }
        ContextCompat.registerReceiver(
            this,
            closeUiReceiver,
            IntentFilter("ACTION_CLOSE_INCOMING_UI"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

    }

    // ------------------- Voice Accept -------------------
    // ------------------- Voice Accept (Fixed) -------------------
    private fun handleVoiceAccept() {
        val invite = IncomingCallHolder.invite
        if (invite == null) {
            Toast.makeText(this, "No incoming call invite", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (!hasAllPermissionsGranted()) {
            checkAndRequestPermissions()
            return
        }
        // kill incoming notification
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(9999)

// stop incoming ringtone service if running
        try {
            val stop = Intent(this, IncomingCallService::class.java)
            stopService(stop)
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(Intent(this, CallForegroundService::class.java))
        } else {
            startService(Intent(this, CallForegroundService::class.java))
        }

        // ✅ Accept immediately using Twilio’s built-in bridge token
        invite.accept(this@IncomingCallActivity, object : Call.Listener {
            override fun onConnected(call: Call) {

                Log.d("IncomingCall", "✅ Connected successfully to Twilio Voice")

                // 🔥 Remove the incoming call notification
                try {
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    nm.cancel(9999)
                } catch (e: Exception) {
                    Log.e("IncomingCall", "Failed to cancel incoming notif: ${e.message}")
                }
                // ✅ Notify caller via Twilio Voice "custom param"
                try {
                    val customParams = mapOf("answered" to "true")
                    call.sendDigits("A") // harmless DTMF trigger for awareness
                    Log.d("IncomingCall", "📡 Sent call acceptance ping to caller via Twilio")
                    // ✅ Notify backend that callee answered (so caller can update UI)
                    notifyAnswerToServer()

                } catch (e: Exception) {
                    Log.w("IncomingCall", "⚠️ Failed to send acceptance ping: ${e.message}")
                }
                CallHolder.activeCall = call
                CallHolder.calleeAnswered = true
                IncomingCallHolder.invite = null

                runOnUiThread {
                    val displayName = txtCallerNumber.text.toString()
                        .replace("client:+", "+")
                        .replace("client:", "")

                    CallHolder.callerDisplayName = displayName




                    // ✅ Launch CallScreenActivity with an indicator
                    val callIntent = Intent(this@IncomingCallActivity, CallScreenActivity::class.java).apply {
                        putExtra("call_mode", "PSTN_AUDIO")
                        putExtra("display_name", displayName)
                        putExtra("start_timer_now", true)
                    }

                    val mainIntent = Intent(this@IncomingCallActivity, MainActivity::class.java)

                    val stackBuilder = android.app.TaskStackBuilder.create(this@IncomingCallActivity)
                    stackBuilder.addNextIntent(mainIntent)
                    stackBuilder.addNextIntent(callIntent)
                    stackBuilder.startActivities()

                    finish()
                }

            }

            override fun onConnectFailure(call: Call, e: CallException) {
                Log.e("IncomingCall", "❌ Connection failed: ${e.message}")
                runOnUiThread {
                    Toast.makeText(
                        this@IncomingCallActivity,
                        "Connection failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }

            override fun onDisconnected(call: Call, e: CallException?) {
                Log.d("IncomingCall", "📴 Disconnected from call")
                runOnUiThread { finish() }
            }

            override fun onReconnecting(call: Call, e: CallException) {}
            override fun onReconnected(call: Call) {}
            override fun onRinging(call: Call) {}
        })
    }


    // ------------------- Video Accept -------------------
    private fun handleVideoAccept(from: String, room: String?) {
        val openedFromNotification =
            intent.getBooleanExtra("opened_from_notification", false)
        // 💥 Kill incoming notification here also!!!
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(9999)
        } catch (_: Exception) {}
        if (room.isNullOrEmpty()) {
            Toast.makeText(this, "Missing room info for video call", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val myIdentity = prefs.getString("identity", "UNKNOWN") ?: "UNKNOWN"

        val payload = JSONObject().apply {
            put("identity", myIdentity)
            put("room", room)
        }

        httpPost("$BASE_URL/video-token", payload) { ok, json, err ->
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(this, "Token fetch failed: $err", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@httpPost
            }

            val token = json.optString("token")
            val roomName = json.optString("room")
            if (token.isNullOrEmpty() || roomName.isNullOrEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "Invalid server response", Toast.LENGTH_LONG).show()
                    finish()
                }
                return@httpPost
            }

            runOnUiThread {
                if (!openedFromNotification) {
                    val callIntent = Intent(this, CallScreenActivity::class.java).apply {
                        putExtra("start_timer_now", true)
                        putExtra("call_mode", "APP_VIDEO")
                        putExtra("display_name", from)
                        putExtra("room_name", roomName)
                        putExtra("access_token", token)
                    }

                    val mainIntent = Intent(this, MainActivity::class.java)

                    val stackBuilder = android.app.TaskStackBuilder.create(this)
                    stackBuilder.addNextIntent(mainIntent)
                    stackBuilder.addNextIntent(callIntent)
                    stackBuilder.startActivities()

                    finish()
                }
            }
        }
    }


    private val closeUiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

            finish()
        }
    }
    private val stopRingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {

        }
    }

    // ------------------- Notify END_CALL -------------------
    private fun notifyEndCall(to: String) {
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val storedTwilio = prefs.getString("identity", "") ?: ""
            val storedDisplay = prefs.getString("identity_display", "") ?: ""

// from (app user)
            val fromNorm = if (storedDisplay.startsWith("+")) storedDisplay else "+${storedTwilio}"

// to (incoming caller string 'to' param may be client: or +...); normalize:
            val toRaw = to
            val toNorm = if (toRaw.startsWith("+")) toRaw else "+${toRaw.filter { it.isDigit() }}"

            val payload = JSONObject().apply {
                put("fromIdentity", storedTwilio.replace("[^0-9]".toRegex(), "")) // digits only
                put("toIdentity", toNorm.replace("[^0-9]".toRegex(), "")) // digits only
            }


            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$BASE_URL/end-call").post(body).build()

            OkHttpClient().newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.e("IncomingCall", "❌ END_CALL notify failed: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    Log.d("IncomingCall", "✅ END_CALL sent (${response.code})")
                }
            })
        } catch (e: Exception) {
            Log.e("IncomingCall", "⚠️ END_CALL notify exception: ${e.message}")
        }
    }


    // ------------------- Notify ANSWERED -------------------
    private fun notifyAnswerToServer() {
        try {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val calleeStored = prefs.getString("identity", "") ?: ""

            // caller number shown in IncomingCallActivity UI
            val callerDisplay = txtCallerNumber.text?.toString() ?: ""

            // Normalize identities (digits only)
            val fromDigits = calleeStored.replace("[^0-9]".toRegex(), "")
            val toDigits = callerDisplay.filter { it.isDigit() }

            val payload = JSONObject().apply {
                put("fromIdentity", fromDigits)
                put("toIdentity", toDigits)   // important for caller-side push
            }

            val body = payload.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BASE_URL/callee-answered")
                .post(body)
                .build()

            OkHttpClient().newCall(req).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: IOException) {
                    Log.w("IncomingCall", "notifyAnswerToServer failed: ${e.message}")
                }

                override fun onResponse(call: okhttp3.Call, resp: okhttp3.Response) {
                    Log.d("IncomingCall", "✅ notified server callee answered (${resp.code})")
                    resp.close()
                }
            })
        } catch (e: Exception) {
            Log.e("IncomingCall", "notifyAnswerToServer exception: ${e.message}")
        }
    }





    // ------------------- HTTP Helper -------------------
    private fun httpPost(url: String, json: JSONObject, cb: (Boolean, JSONObject, String?) -> Unit) {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        OkHttpClient().newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                cb(false, JSONObject(), e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {

                response.use {
                    val raw = it.body?.string().orEmpty()
                    try {
                        val jsonResp = JSONObject(if (raw.isEmpty()) "{}" else raw)
                        cb(it.isSuccessful, jsonResp, if (it.isSuccessful) null else it.message)
                    } catch (e: Exception) {
                        cb(false, JSONObject(), e.message)
                    }
                }
            }
        })
    }

    // ------------------- Permissions & Wake -------------------
    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun hasAllPermissionsGranted(): Boolean =
        REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun wakeUpScreen() {
        window.addFlags(
            android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        )

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(
            PowerManager.FULL_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
            "myapp:IncomingCallWakeLock"
        )
        wl.acquire(10 * 1000L)

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            km.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        }
    }



    override fun onDestroy() {

        try { unregisterReceiver(stopRingReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(closeUiReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBackPressed() {

        try {
            IncomingCallHolder.invite?.reject(this)
        } catch (_: Exception) {}
        finish()
        super.onBackPressed()
    }
}
