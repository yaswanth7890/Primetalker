package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.twilio.voice.Call as VoiceCall
import com.twilio.voice.CallException
import com.twilio.voice.ConnectOptions
import com.twilio.voice.Voice
import okhttp3.Call as OkHttpCall
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import android.util.Log


class CallActivity : AppCompatActivity() {

    private lateinit var edtPhoneNumber: EditText
    private lateinit var spinnerCountryCode: Spinner
    private lateinit var searchBar: EditText
    private lateinit var btnAudioCall: Button
    private lateinit var btnVideoCall: Button
    private lateinit var btnPickContact: ImageView
    private lateinit var btnDeleteNumber: ImageView
    private lateinit var dialerPanel: LinearLayout
    private lateinit var fabDialer: FloatingActionButton
    private lateinit var dockChats: LinearLayout
    private lateinit var dockTranslate: LinearLayout
    private lateinit var dockCalls: LinearLayout
    private lateinit var dockSettings: LinearLayout

    private val REQUEST_CONTACT = 100
    private lateinit var recyclerAdapter: ContactsAdapter
    private val contactsList = mutableListOf<String>()
    private val countryCodes = listOf("+1", "+91", "+44", "+81", "+61", "+49")

    // TODO: set your backend base URL & current user id
    private val BACKEND_BASE_URL = "https://nodical-earlie-unyieldingly.ngrok-free.dev"
    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }
    private val CURRENT_USER_ID: String
        get() = prefs.getString("identity", "") ?: ""



    private fun normalizePhoneWithCountry(number: String): String {
        var cleaned = number.trim().replace(" ", "").replace("-", "")

        // If number already starts with + and digits → valid E.164
        if (cleaned.startsWith("+")) return cleaned

        // Get the selected country code from spinner (e.g. "+91")
        val countryCode = spinnerCountryCode.selectedItem.toString()

        // Remove leading zeros if present
        cleaned = cleaned.trimStart('0')

        // Combine country code + digits
        return countryCode + cleaned
    }





    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        edtPhoneNumber = findViewById(R.id.edtPhoneNumber)
        spinnerCountryCode = findViewById(R.id.spinnerCountryCode)
        searchBar = findViewById(R.id.searchBar)
        btnAudioCall = findViewById(R.id.btnAudioCall)
        btnVideoCall = findViewById(R.id.btnVideoCall)
        btnPickContact = findViewById(R.id.btnPickContact)
        btnDeleteNumber = findViewById(R.id.btnDeleteNumber)
        dialerPanel = findViewById(R.id.dialerPanel)
        fabDialer = findViewById(R.id.fabDialer)

        val countryAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, countryCodes)
        countryAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCountryCode.adapter = countryAdapter
        spinnerCountryCode.setSelection(countryCodes.indexOf("+91"))

        recyclerAdapter = ContactsAdapter(
            emptyList(),
            onAudioClick = { number -> startPstnCall(number) },
            onVideoClick = { number -> startAppVideoCall(number) }
        )
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerMixed)
        recyclerView.adapter = recyclerAdapter
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerAdapter.updateList(listOf("No contact found"))

        searchBar.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                val filtered = if (query.isEmpty()) {
                    if (contactsList.isEmpty()) listOf("No contact found") else contactsList
                } else {
                    val f = contactsList.filter { it.contains(query, ignoreCase = true) }
                    if (f.isEmpty()) listOf("No contact found") else f
                }
                recyclerAdapter.updateList(filtered)
            }
        })

        dockChats = findViewById(R.id.dock_chats_layout)
        dockTranslate = findViewById(R.id.dock_translate_layout)
        dockCalls = findViewById(R.id.dock_calls_layout)
        dockSettings = findViewById(R.id.dock_settings_layout)
        highlightCurrentPage(dockCalls)

        dockChats.setOnClickListener { startActivity(Intent(this, CallActivity::class.java)) }
        dockSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        dockTranslate.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        btnPickContact.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
            startActivityForResult(intent, REQUEST_CONTACT)
        }

        btnDeleteNumber.setOnClickListener {
            val text = edtPhoneNumber.text.toString()
            if (text.isNotEmpty()) edtPhoneNumber.setText(text.dropLast(1))
        }

        val dialerGrid = findViewById<GridLayout>(R.id.dialerGrid)
        for (i in 0 until dialerGrid.childCount) {
            val child = dialerGrid.getChildAt(i)
            if (child is Button) child.setOnClickListener { edtPhoneNumber.append(child.text) }
        }

        btnAudioCall.setOnClickListener {
            val fullNumber = normalizePhoneWithCountry(edtPhoneNumber.text.toString())
            if (fullNumber.isNotEmpty()) startSmartVoiceCall(fullNumber)
            else Toast.makeText(this, "Enter a phone number", Toast.LENGTH_SHORT).show()
        }


        btnVideoCall.setOnClickListener {
            val peer = edtPhoneNumber.text.toString().trim()
            if (peer.isNotEmpty()) startAppVideoCall(peer)
            else Toast.makeText(this, "Enter a user/identity", Toast.LENGTH_SHORT).show()
        }

        fabDialer.setOnClickListener {
            if (dialerPanel.visibility == View.GONE) {
                dialerPanel.visibility = View.VISIBLE
                fabDialer.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            } else {
                dialerPanel.visibility = View.GONE
                fabDialer.setImageResource(android.R.drawable.ic_menu_call)
            }
        }
    }

    private fun getFullPhoneNumber(): String {
        val number = edtPhoneNumber.text.toString().trim()
        return if (number.isNotEmpty()) normalizePhoneWithCountry(number) else ""
    }


    // ---------- Outgoing PSTN (audio) via Twilio Voice ----------
    private fun startPstnCall(e164Number: String) {
        CallHolder.isOutgoing = true   // ✅ mark early
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val idDigits = prefs.getString("identity", CURRENT_USER_ID)?.replace("[^0-9]".toRegex(), "") ?: CURRENT_USER_ID
        httpGet("$BACKEND_BASE_URL/voice-token?identity=$idDigits") { ok, json, err ->
            if (!ok) {
                runOnUiThread { Toast.makeText(this, "Voice token error: $err", Toast.LENGTH_SHORT).show() }
                return@httpGet
            }
            val token = json.optString("token")
            if (token.isNullOrEmpty()) {
                runOnUiThread { Toast.makeText(this, "Missing Voice token", Toast.LENGTH_SHORT).show() }
                return@httpGet
            }

            val options = ConnectOptions.Builder(token)
                .params(mapOf("To" to e164Number))
                .build()



            val activeCall: VoiceCall = Voice.connect(this, options, object : VoiceCall.Listener {
                override fun onConnected(call: VoiceCall) {}
                override fun onDisconnected(call: VoiceCall, error: CallException?) {}
                override fun onConnectFailure(call: VoiceCall, error: CallException) {
                    runOnUiThread { Toast.makeText(this@CallActivity, "Connect failed: ${error.message}", Toast.LENGTH_SHORT).show() }
                }
                override fun onRinging(call: VoiceCall) {}
                override fun onReconnecting(call: VoiceCall, error: CallException) {}
                override fun onReconnected(call: VoiceCall) {}
            })

            CallHolder.activeCall = activeCall

            val i = Intent(this, CallScreenActivity::class.java).apply {
                putExtra("call_mode", CallMode.PSTN_AUDIO.name)
                putExtra("display_name", e164Number)
            }
            startActivity(i)
        }
    }



    private fun startSmartVoiceCall(targetNumber: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val fromIdentity = prefs.getString("identity", null)
        if (fromIdentity.isNullOrEmpty()) {
            Toast.makeText(this, "Login expired — please re-login.", Toast.LENGTH_LONG).show()
            return
        }

        CallHolder.isOutgoing = true

        val app = applicationContext as VoiceApp

        if (!app.isTwilioReady) {
            Toast.makeText(this, "Twilio not registered yet, please wait", Toast.LENGTH_SHORT).show()
            return
        }
        // use prefs-stored identity (digits only)
        val rawFrom = prefs.getString("identity", fromIdentity) ?: fromIdentity ?: ""
        val fromDigits = rawFrom.replace("[^0-9]".toRegex(), "")

// callee: prefer digits-only identity for server; keep e164 for display where needed
        val calleeE164 = normalizePhoneWithCountry(targetNumber) // +E.164
        val toDigits = calleeE164.replace("[^0-9]".toRegex(), "")

// Build payload using digits-only identities (server expects Twilio identities)
        val payload = JSONObject().apply {
            put("fromIdentity", fromDigits)
            put("toIdentity", toDigits)
            // optionally include display phone for server logs if your backend supports it:
            put("from_display", prefs.getString("identity_display", ""))
            put("to_display", calleeE164)
        }

        Log.d("CallActivity", "Calling backend /call-user (from=$fromDigits to=$toDigits)")

        val tokenIdentity = fromDigits
        httpGet("$BACKEND_BASE_URL/voice-token?identity=$tokenIdentity"){ ok, json, err ->
            runOnUiThread {
                if (!ok) {
                    Toast.makeText(this, "Call setup failed: $err", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }



                val type = json.optString("type")
                val callee = json.optString("callee")

                if (type == "in-app" && !callee.isNullOrEmpty()) {
                    // ✅ In-app call
                    httpGet("$BACKEND_BASE_URL/voice-token?identity=$fromIdentity") { ok2, tokJson, err2 ->
                        if (!ok2) {
                            runOnUiThread {
                                Toast.makeText(this, "Token error: $err2", Toast.LENGTH_LONG).show()
                            }
                            return@httpGet
                        }
                        val token = tokJson.optString("token")
                        val options = ConnectOptions.Builder(token)
                            .params(mapOf("To" to callee))
                            .build()
                        Log.d("TwilioDebug", "🔗 Voice.connect() with token=${token.take(10)}... to=${callee} from=${fromIdentity}")

                        val activeCall = com.twilio.voice.Voice.connect(this, options, object : com.twilio.voice.Call.Listener {

                            override fun onConnected(call: com.twilio.voice.Call) {
                                Log.d("SmartCall", "✅ Connected")
                            }

                            override fun onRinging(call: com.twilio.voice.Call) {
                                Log.d("SmartCall", "📞 Ringing...")
                            }

                            override fun onDisconnected(call: com.twilio.voice.Call, error: com.twilio.voice.CallException?) {
                                Log.d("SmartCall", "📴 Disconnected")
                            }

                            override fun onConnectFailure(call: com.twilio.voice.Call, error: com.twilio.voice.CallException) {
                                runOnUiThread {
                                    Toast.makeText(this@CallActivity, "Connect failed: ${error.message}", Toast.LENGTH_LONG).show()
                                }
                            }


                            override fun onReconnecting(call: com.twilio.voice.Call, error: com.twilio.voice.CallException) {}
                            override fun onReconnected(call: com.twilio.voice.Call) {}
                        })
                        Log.d("TwilioDebug", "📥 /call-user response: ok=$ok type=$type callee=$callee")


                        CallHolder.activeCall = activeCall

                        // ✅ Open call screen ONCE
                        runOnUiThread {
                            val i = Intent(this@CallActivity, CallScreenActivity::class.java).apply {
                                putExtra("call_mode", CallMode.PSTN_AUDIO.name)
                                putExtra("display_name", callee)
                                putExtra("to_identity", callee)
                            }
                            startActivity(i)
                        }
                    }
                } else {
                    // PSTN fallback
                    startPstnCall(targetNumber)
                }
            }
        }
    }


    // ---------- Outgoing App↔App video via Twilio Video ----------
    private fun startAppVideoCall(peerIdentity: String) {
        // ✅ Always prepend country code if missing
        val selectedCode = spinnerCountryCode.selectedItem.toString()
        val normalized = normalizePhoneWithCountry(peerIdentity)


        // ✅ Get stored Twilio identity (do not use CURRENT_USER_ID)
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val fromIdentity = prefs.getString("identity", null)
        if (fromIdentity.isNullOrEmpty()) {
            runOnUiThread {
                Toast.makeText(this, "No Twilio identity found — please re-login.", Toast.LENGTH_LONG).show()
            }
            return
        }

        if (fromIdentity == null) {
            runOnUiThread {
                Toast.makeText(this, "No Twilio identity found — please re-login.", Toast.LENGTH_LONG).show()
            }
            return
        }

        val call = CallHolder.activeCall

        if (call != null) {
            val state = call.state
            Log.d("VideoCall", "🟡 ActiveCall state = $state")

            // TRUE active call → block video
            if (state == VoiceCall.State.CONNECTING ||
                state == VoiceCall.State.RINGING ||
                state == VoiceCall.State.CONNECTED ||
                state == VoiceCall.State.RECONNECTING) {

                Toast.makeText(this, "Voice call in progress — can’t start video.", Toast.LENGTH_SHORT).show()
                return
            }

            // If DISCONNECTED → clean ghost call
            if (state == VoiceCall.State.DISCONNECTED) {
                Log.d("VideoCall", "🧹 Cleaning ghost/disconnected call")
                CallHolder.activeCall = null
            }
        }



        val payload = JSONObject().apply {
            put("fromIdentity", fromIdentity)
            put("toIdentity", normalized)
        }

        Log.d("VideoCall", "🎥 Sending video invite: $fromIdentity ➡️ $normalized")

        httpPost("$BACKEND_BASE_URL/video-invite", payload) { ok, json, err ->
            if (!ok) {
                runOnUiThread {
                    Toast.makeText(this, "Video start error: $err", Toast.LENGTH_LONG).show()
                }
                return@httpPost
            }

            val token = json.optString("token")
            val room = json.optString("room")
            if (token.isNullOrEmpty() || room.isNullOrEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "Invalid server response", Toast.LENGTH_LONG).show()
                }
                return@httpPost
            }

            // ✅ Start video call UI (does not touch Voice SDK)
            val i = Intent(this, CallScreenActivity::class.java).apply {
                putExtra("call_mode", CallMode.APP_VIDEO.name)
                putExtra("display_name", normalized)
                putExtra("room_name", room)
                putExtra("access_token", token)
            }
            startActivity(i)
        }
    }





    private fun highlightCurrentPage(current: LinearLayout) {
        val docks = listOf(dockChats, dockTranslate, dockCalls, dockSettings)
        docks.forEach { dock ->
            try {
                val img = dock.getChildAt(0) as ImageView
                val text = dock.getChildAt(1) as TextView
                if (dock == current) {
                    img.setColorFilter(0xFFFFA500.toInt())
                    text.setTextColor(0xFFFFA500.toInt())
                } else {
                    img.setColorFilter(0xFFFFFFFF.toInt())
                    text.setTextColor(0xFFFFFFFF.toInt())
                }
            } catch (_: Exception) {}
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CONTACT && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            uri?.let {
                val cursor = contentResolver.query(
                    it,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                    null, null, null
                )
                cursor?.use { c ->
                    if (c.moveToFirst()) {
                        val number = c.getString(0)
                        if (number.isNotEmpty() && !contactsList.contains(number)) {
                            edtPhoneNumber.setText(number)
                            contactsList.add(number)
                        }
                    }
                }
            }
            if (contactsList.isEmpty()) contactsList.add("No contact found")
            recyclerAdapter.updateList(contactsList)
        }
    }

    // ----- HTTP helpers (labels fixed; OkHttp types explicit) -----
    private fun httpGet(url: String, cb: (ok: Boolean, json: JSONObject, err: String?) -> Unit) {
        val req = Request.Builder().url(url).get().build()
        OkHttpClient().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: OkHttpCall, e: IOException) = cb(false, JSONObject(), e.message)
            override fun onResponse(call: OkHttpCall, response: Response) {
                response.use {
                    val body = it.body?.string().orEmpty()
                    cb(it.isSuccessful, JSONObject(if (body.isEmpty()) "{}" else body), if (it.isSuccessful) null else it.message)
                }
            }
        })
    }

    private fun httpPost(
        url: String,
        json: JSONObject,
        cb: (ok: Boolean, json: JSONObject, err: String?) -> Unit
    ) {
        val body = json.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body).build()
        OkHttpClient().newCall(req).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                cb(false, JSONObject(), e.message)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    val b = it.body?.string().orEmpty()
                    try {
                        val jsonResp = JSONObject(if (b.isEmpty()) "{}" else b)
                        cb(it.isSuccessful, jsonResp, if (it.isSuccessful) null else it.message)
                    } catch (e: Exception) {
                        cb(false, JSONObject(), e.message)
                    }
                }
            }
        })
    }

    private fun isLikelyJson(contentType: String?): Boolean =
        contentType?.lowercase()?.contains("application/json") == true

    private fun tryParseJsonOrNull(raw: String): JSONObject? = try {
        JSONObject(raw)
    } catch (_: Exception) { null }

}
