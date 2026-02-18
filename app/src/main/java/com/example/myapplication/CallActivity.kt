package com.example.myapplication

import android.app.Activity
import android.content.Intent
import android.os.Build
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
import android.view.MotionEvent



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

    private val voiceListener = object : VoiceCall.Listener {

        override fun onRinging(call: VoiceCall) {
            Log.d("CallActivity", "📞 Ringing")
        }

        override fun onConnected(call: VoiceCall) {
            Log.d("CallActivity", "✅ Caller connected")
            CallHolder.activeCall = call
        }

        override fun onDisconnected(call: VoiceCall, error: CallException?) {
            Log.d("CallActivity", "📴 Caller disconnected: ${error?.message}")
            CallHolder.activeCall = null
        }

        override fun onConnectFailure(call: VoiceCall, error: CallException) {
            Log.e("CallActivity", "❌ Call failed: ${error.message}")
            CallHolder.activeCall = null
            runOnUiThread {
                Toast.makeText(this@CallActivity, "Call failed", Toast.LENGTH_SHORT).show()
            }
        }

        override fun onReconnecting(call: VoiceCall, error: CallException) {
            Log.w("CallActivity", "🔄 Reconnecting")
        }

        override fun onReconnected(call: VoiceCall) {
            Log.d("CallActivity", "🔁 Reconnected")
        }
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





        val handle = findViewById<View>(R.id.dragHandle)

        handle.setOnTouchListener(object : View.OnTouchListener {
            private var startY = 0f

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {

                    MotionEvent.ACTION_DOWN -> {
                        startY = event.rawY
                        return true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        dialerPanel.alpha = 0.95f

                        val delta = event.rawY - startY
                        if (delta > 0) {
                            dialerPanel.translationY = delta
                        }
                        return true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val delta = event.rawY - startY

                        if (delta > dialerPanel.height / 4) {
                            // CLOSE
                            dialerPanel.animate()
                                .translationY(dialerPanel.height.toFloat())
                                .setDuration(200)
                                .withEndAction {
                                    dialerPanel.visibility = View.GONE
                                    dialerPanel.translationY = 0f
                                    fabDialer.setImageResource(android.R.drawable.ic_menu_call)
                                }
                                .start()
                        } else {
                            // SNAP BACK
                            dialerPanel.animate()
                                .translationY(0f)
                                .setDuration(150)
                                .start()
                        }
                        return true
                    }
                }
                return false
            }
        })



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

        dockChats.setOnClickListener { startActivity(Intent(this, ChatListActivity::class.java)) }
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

    private fun toggleDialer() {
        if (dialerPanel.visibility == View.VISIBLE) {
            dialerPanel.visibility = View.GONE
            fabDialer.setImageResource(android.R.drawable.ic_menu_call)
        } else {
            dialerPanel.visibility = View.VISIBLE
            fabDialer.setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
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



            val i = Intent(this, CallScreenActivity::class.java).apply {
                putExtra("call_mode", CallMode.PSTN_AUDIO.name)
                putExtra("display_name", e164Number)
            }
            startActivity(i)
        }
    }




    private fun startSmartVoiceCall(targetNumber: String) {

        // 0️⃣ Get caller identity FIRST
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val fromDigits = prefs.getString("identity", "")!!
            .replace("[^0-9]".toRegex(), "")



        // 1️⃣ Get Voice token
        httpGet("$BACKEND_BASE_URL/voice-token?identity=$fromDigits") { ok, json, err ->
            if (!ok) return@httpGet

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(Intent(this, CallForegroundService::class.java))
            } else {
                startService(Intent(this, CallForegroundService::class.java))
            }


            val token = json.optString("token")

            // 2️⃣ CONNECT CALLER VIA VOICE SDK
            val options = ConnectOptions.Builder(token)
                .params(mapOf(
                    "To" to targetNumber.replace("[^0-9]".toRegex(), "")
                ))
                .build()

            CallHolder.activeCall = Voice.connect(
                this,
                options,
                voiceListener
            )

            // 3️⃣ Ask backend to call callee
            httpPost(
                "$BACKEND_BASE_URL/call-user",
                JSONObject().apply {
                    put("fromIdentity", fromDigits)
                    put("toIdentity", targetNumber.replace("[^0-9]".toRegex(), ""))
                }
            ) { _, _, _ -> }

            // 4️⃣ NOW open UI
            startActivity(
                Intent(this, CallScreenActivity::class.java).apply {
                    putExtra("call_mode", "PSTN_AUDIO")
                    putExtra("display_name", targetNumber)
                    putExtra("start_timer_now", false)
                }
            )
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