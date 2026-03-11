package com.example.myapplication

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.myapplication.db.DbProvider
import com.google.firebase.messaging.FirebaseMessaging
import com.twilio.voice.LogLevel
import com.twilio.voice.RegistrationException
import com.twilio.voice.RegistrationListener
import com.twilio.voice.Voice
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import androidx.work.*
import java.util.concurrent.TimeUnit
class VoiceApp : Application() {

    private val BASE_URL = "https://nodical-earlie-unyieldingly.ngrok-free.dev"


    private val registering = AtomicBoolean(false)
    private var lastIdentity: String = ""
    private var lastFCM: String = ""

    private val PREF_ACCESS_TOKEN = "twilio_access_token"

    var isTwilioReady = false


    override fun onCreate() {
        super.onCreate()

        Voice.setLogLevel(LogLevel.ALL)

        DbProvider.init(this)
        startPendingWorker()

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        var identity = prefs.getString("identity", null)

        if (identity.isNullOrBlank()) {
            Log.w("VoiceApp", "⚠️ No saved identity. Skipping Twilio auto-register")
            return
        }

        // Clean digits only
        identity = identity.replace("\\D".toRegex(), "")

        Log.d("VoiceApp", "📱 Auto-registering Twilio Voice for identity=$identity")

        Handler(Looper.getMainLooper()).postDelayed({
            registerTwilioVoice(identity!!)
        }, 1500)
    }


    private fun startPendingWorker() {

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request =
            PeriodicWorkRequestBuilder<PendingMessageWorker>(
                15,
                TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "pendingMessages",
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
    }
    companion object {
        private var currentToken: String? = null
        fun setCurrentToken(token: String) { currentToken = token }
        fun getCurrentToken(): String = currentToken ?: ""

    }

    fun registerTwilioVoice(rawIdentity: String, onDone: ((Boolean) -> Unit)? = null) {

        val identity = rawIdentity.replace("\\D".toRegex(), "")

        // BLOCK duplicate attempts
        if (!lastIdentity.isNullOrEmpty() && lastIdentity == identity) {
            Log.w("VoiceApp", "⏳ Already registered for $identity – ignoring")
            onDone?.invoke(true)
            return
        }

        if (!registering.compareAndSet(false, true)) {
            Log.w("VoiceApp", "⏳ Registration already running – ignoring")
            onDone?.invoke(false)
            return
        }

        Log.d("VoiceApp", "🔗 Registering Twilio for identity=$identity")

        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val client = OkHttpClient()

        val url = "$BASE_URL/voice-token?identity=$identity"

        client.newCall(Request.Builder().url(url).get().build())
            .enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e("VoiceApp", "❌ Fetch token failed: ${e.message}")
                    registering.set(false)
                    onDone?.invoke(false)
                }

                override fun onResponse(call: Call, response: Response) {
                    val token = JSONObject(response.body?.string().orEmpty()).optString("token")
                    if (token.isBlank()) {
                        Log.e("VoiceApp", "❌ Invalid token")
                        registering.set(false)
                        onDone?.invoke(false)
                        return
                    }

                    FirebaseMessaging.getInstance().token.addOnSuccessListener { fcm ->

                        // Prevent duplicate register with same identity + FCM
                        if (lastIdentity == identity && lastFCM == fcm) {
                            Log.d("VoiceApp", "🔁 Already registered same identity+FCM — skip")
                            registering.set(false)
                            onDone?.invoke(true)
                            return@addOnSuccessListener
                        }

                        Voice.register(
                            token,
                            Voice.RegistrationChannel.FCM,
                            fcm,
                            object : RegistrationListener {
                                override fun onRegistered(accessToken: String, fcmToken: String) {
                                    Log.d("VoiceApp", "✅ Twilio Registered OK for $identity")
                                    currentToken = accessToken   // ADD THIS 🔥


                                    lastIdentity = identity
                                    lastFCM = fcm

                                    prefs.edit()
                                        .putString(PREF_ACCESS_TOKEN, accessToken)
                                        .putString("identity", identity)
                                        .putString("twilio_identity", identity)
                                        .apply()
                                    isTwilioReady = true

                                    registering.set(false)
                                    onDone?.invoke(true)
                                }

                                override fun onError(error: RegistrationException, accessToken: String, fcmToken: String) {
                                    Log.e("VoiceApp", "❌ Twilio Registration failed: ${error.message}")
                                    registering.set(false)
                                    onDone?.invoke(false)
                                }
                            }
                        )
                    }
                }
            })
    }



}