package com.example.myapplication

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class LoginActivity : AppCompatActivity() {

    private lateinit var etPhone: TextInputEditText
    private lateinit var etOTP: TextInputEditText
    private lateinit var tilOTP: TextInputLayout
    private lateinit var btnSendOTP: Button
    private lateinit var btnVerifyOTP: Button
    private lateinit var tvTimer: TextView

    private var countDownTimer: CountDownTimer? = null
    private val timerDuration: Long = 30_000 // 30 sec
    private val BASE_URL = "https://nodical-earlie-unyieldingly.ngrok-free.dev" // ✅ Update here when deployed

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        etPhone = findViewById(R.id.etPhone)
        etOTP = findViewById(R.id.etOTP)
        tilOTP = findViewById(R.id.tilOTP)
        btnSendOTP = findViewById(R.id.btnSendOTP)
        btnVerifyOTP = findViewById(R.id.btnVerifyOTP)
        tvTimer = findViewById(R.id.tvTimer)

        if (tryAutoLogin()) return

        tilOTP.visibility = View.GONE
        btnVerifyOTP.visibility = View.GONE
        tvTimer.visibility = View.GONE

        btnSendOTP.setOnClickListener {
            val phone = ensureE164(etPhone.text?.toString())
            if (phone == null) {
                toast("Enter phone like +91XXXXXXXXXX")
                return@setOnClickListener
            }
            sendRealOTP(phone)
        }

        btnVerifyOTP.setOnClickListener {
            val code = etOTP.text?.toString()?.trim().orEmpty()
            if (code.isEmpty()) {
                toast("Enter OTP")
                return@setOnClickListener
            }

            val phone = ensureE164(etPhone.text?.toString())
            if (phone == null) {
                toast("Enter phone like +91XXXXXXXXXX")
                return@setOnClickListener
            }

            verifyRealOTP(phone, code)
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun ensureE164(input: String?): String? {
        val cleaned = input?.trim()?.replace(" ", "")?.replace("-", "")
        return if (cleaned != null && cleaned.startsWith("+") && cleaned.length >= 8) cleaned else null
    }

    private fun generateDeviceId(): String {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun showOtpInputs() {
        tilOTP.visibility = View.VISIBLE
        btnVerifyOTP.visibility = View.VISIBLE
        btnSendOTP.isEnabled = false
    }

    private fun startTimer() {
        tvTimer.visibility = View.VISIBLE
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(timerDuration, 1000) {
            override fun onTick(ms: Long) {
                val s = ms / 1000
                tvTimer.text = "Resend OTP in 00:${if (s < 10) "0$s" else s}"
            }

            override fun onFinish() {
                btnSendOTP.isEnabled = true
                tvTimer.text = "You can resend OTP now"
            }
        }.start()
    }

    private fun openMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    override fun onDestroy() {
        countDownTimer?.cancel()
        super.onDestroy()
    }

    // ================== ✅ OTP Request ==================
    private fun sendRealOTP(phone: String) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                postJson("$BASE_URL/auth/request-otp", JSONObject().apply { put("phone", phone) })
            }
            if (result.ok) {
                toast(result.message ?: "OTP sent")
                showOtpInputs()
                startTimer()
            } else {
                toast(result.error ?: "Failed to send OTP")
            }
        }
    }

    // ================== ✅ OTP Verification ==================
    private fun verifyRealOTP(phone: String, code: String) {
        val deviceId = generateDeviceId()
        // Canonical identities
        val normalizedDisplay = if (phone.startsWith("+")) phone else "+${phone.filter { it.isDigit() }}"
        val twilioIdentity = normalizedDisplay.replace("+", "").filter { it.isDigit() }


        FirebaseMessaging.getInstance().token.addOnSuccessListener { fcmToken ->
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    postJson("$BASE_URL/auth/verify-otp", JSONObject().apply {
                        put("phone", phone)
                        put("code", code)
                        put("device_id", deviceId)
                        put("fcm_token", fcmToken)
                        put("identity", twilioIdentity)
                    })
                }

                if (result.ok) {
                    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

                    // 🧹 1️⃣ Clear everything from any previous login (stale identity)
                    prefs.edit().clear().apply()

                    // 🧩 2️⃣ Re-save the fresh, correct identity and tokens
                    val access = result.access_token ?: result.token
                    val mins = result.access_expires_in_minutes ?: 60

                    // --- NORMALIZE & SAVE IDENTITIES (do this right after login success) ---
                    val normalizedDisplay = if (phone.startsWith("+")) phone else "+${phone.filter { it.isDigit() }}"


                    prefs.edit()
                        .putString("access_token", access)
                        .putString("refresh_token", result.refresh_token)
                        .putLong("access_expiry_epoch", System.currentTimeMillis() + mins * 60_000L)
                        // identity variants:
                        .putString("identity", twilioIdentity)            // canonical digits-only identity used across app storage
                        .putString("twilio_identity", twilioIdentity)     // explicit key used by FCM / server (digits only)
                        .putString("identity_display", normalizedDisplay) // +E.164 for UI and server phone field
                        .putString("user_phone", normalizedDisplay)       // keep for VoiceApp.normalize fallback
                        .apply()

                    Log.d("LoginActivity", "Saved identities: twilio=$twilioIdentity display=$normalizedDisplay")


                    // ✅ 3️⃣ Immediately register for FCM & Twilio Voice
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val json = JSONObject().apply {
                                put("identity", twilioIdentity)   // digits-only
                                put("phone", normalizedDisplay)   // +E164
                                put("fcm_token", fcmToken)
                            }
                            postJson("$BASE_URL/register", json)


                            withContext(Dispatchers.Main) {
                                val app = applicationContext as VoiceApp
                                app.registerTwilioVoice(twilioIdentity)   // prefer digits-only

                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }

                    toast("Login success")
                    openMainActivity()
                }

            }
        }
    }


    // ================== ✅ Auto Login ==================
    private fun tryAutoLogin(): Boolean {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val access = prefs.getString("access_token", null)
        val refresh = prefs.getString("refresh_token", null)
        val exp = prefs.getLong("access_expiry_epoch", 0L)

        if (!access.isNullOrEmpty() && System.currentTimeMillis() < exp) {
            openMainActivity(); return true
        }

        if (!refresh.isNullOrEmpty()) {
            val deviceId = generateDeviceId()
            lifecycleScope.launch {
                val r = withContext(Dispatchers.IO) {
                    postJson("$BASE_URL/auth/refresh", JSONObject().apply {
                        put("device_id", deviceId)
                        put("refresh_token", refresh)
                    })
                }
                if (r.ok) {
                    val newAccess = r.access_token ?: r.token
                    val mins = r.access_expires_in_minutes ?: 60
                    prefs.edit()
                        .putString("access_token", newAccess)
                        .putString("refresh_token", r.refresh_token ?: refresh)
                        .putLong(
                            "access_expiry_epoch",
                            System.currentTimeMillis() + mins * 60_000L
                        )
                        .apply()
                    openMainActivity()
                }
            }
            return false
        }
        return false
    }

    // ================== ✅ Network Helper ==================
    data class ApiResult(
        val ok: Boolean,
        val message: String? = null,
        val error: String? = null,
        val token: String? = null,
        val access_token: String? = null,
        val access_expires_in_minutes: Int? = null,
        val refresh_token: String? = null
    )

    private fun postJson(urlStr: String, body: JSONObject): ApiResult {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 15000
                doInput = true
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }

            BufferedWriter(OutputStreamWriter(conn.outputStream, Charsets.UTF_8)).use { w ->
                w.write(body.toString())
                w.flush()
            }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val respText = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            val json = try { JSONObject(respText) } catch (_: Exception) { JSONObject() }

            ApiResult(
                ok = json.optBoolean("ok", code in 200..299),
                message = json.optString("message", null),
                error = json.optString("error", if (code in 200..299) null else "HTTP $code"),
                token = json.optString("token", null),
                access_token = json.optString("access_token", null),
                access_expires_in_minutes = json.optInt("access_expires_in_minutes", 0)
                    .let { if (it == 0) null else it },
                refresh_token = json.optString("refresh_token", null)
            )
        } catch (e: Exception) {
            ApiResult(false, error = e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }
}