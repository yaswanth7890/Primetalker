
package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import java.util.Locale
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.db.ChatEntity
import kotlinx.coroutines.launch
import com.example.myapplication.db.DbProvider
import com.google.mlkit.nl.languageid.LanguageIdentification



class ChatActivity : AppCompatActivity() {


    companion object {
        const val ACTION_CHAT_MESSAGE = "ACTION_CHAT_MESSAGE"
    }

    // Chat UI
    private lateinit var chatContainer: LinearLayout
    private lateinit var inputMessage: EditText

    private lateinit var spinnerTarget: Spinner
    private lateinit var myIdentity: String

    private lateinit var chatScroll: ScrollView


    private lateinit var tvMicLang: TextView




    private lateinit var speechRecognizer: android.speech.SpeechRecognizer
    private lateinit var speechIntent: Intent
    private var isListening = false


    // Receiver

    private var receiverIdentity: String? = null


    data class ChatMessage(
        val from: String,
        val original: String,
        val container: LinearLayout,
        val englishView: TextView,
        val targetView: TextView?,
        var englishText: String? = null,
        var targetTranslations: MutableMap<String, String> = mutableMapOf(),
        var originalLang: String? = null,
        var dbId: Long? = null


    )



    private val receivedMessages = mutableListOf<ChatMessage>()



    // Dock
    private lateinit var dockChats: LinearLayout
    private lateinit var dockTranslate: LinearLayout
    private lateinit var dockCalls: LinearLayout
    private lateinit var dockSettings: LinearLayout

    data class LanguageConfig(
        val label: String,          // Spinner label
        val translateCode: String,  // ML Kit Translate code
        val sttLocale: String       // Android STT locale
    )

    private val languages = listOf(
        LanguageConfig("English", "en", "en-IN"),
        LanguageConfig("Hindi", "hi", "hi-IN"),
        LanguageConfig("Telugu", "te", "te-IN"),
        LanguageConfig("Tamil", "ta", "ta-IN"),
        LanguageConfig("Kannada", "kn", "kn-IN"),
        LanguageConfig("Malayalam", "ml", "ml-IN"),
        LanguageConfig("Marathi", "mr", "mr-IN"),
        LanguageConfig("Gujarati", "gu", "gu-IN"),
        LanguageConfig("Punjabi", "pa", "pa-IN"),
        LanguageConfig("Urdu", "ur", "ur-IN"),
        LanguageConfig("Bengali", "bn", "bn-IN"),
        LanguageConfig("Spanish", "es", "es-ES"),
        LanguageConfig("French", "fr", "fr-FR"),
        LanguageConfig("German", "de", "de-DE"),
        LanguageConfig("Italian", "it", "it-IT"),
        LanguageConfig("Portuguese", "pt", "pt-PT"),
        LanguageConfig("Arabic", "ar", "ar-SA"),
        LanguageConfig("Japanese", "ja", "ja-JP"),
        LanguageConfig("Korean", "ko", "ko-KR"),
        LanguageConfig("Chinese", "zh", "zh-CN")
    )


    private fun langPrefKey(peer: String) = "chat_lang_$peer"

    private fun saveSelectedLanguage(langCode: String) {
        getSharedPreferences("app_prefs", MODE_PRIVATE)
            .edit()
            .putString(langPrefKey(receiverIdentity!!), langCode)
            .apply()
    }

    private fun loadSelectedLanguage(): String {
        return getSharedPreferences("app_prefs", MODE_PRIVATE)
            .getString(
                langPrefKey(receiverIdentity!!),
              "en"
            )!!
    }





    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // -------- Bind Views --------
        chatContainer = findViewById(R.id.chatContainer)
        inputMessage = findViewById(R.id.inputMessage)

        speechRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)

        speechIntent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        findViewById<ImageButton>(R.id.btnMic).setOnClickListener {
            if (!isListening) {
                startChatSTT()
            } else {
                stopChatSTT()
            }
        }

        tvMicLang = findViewById(R.id.tvMicLang)


        spinnerTarget = findViewById(R.id.spinnerTarget)


        // -------- Dock --------
        dockChats = findViewById(R.id.dock_chats_layout)
        dockTranslate = findViewById(R.id.dock_translate_layout)
        dockCalls = findViewById(R.id.dock_calls_layout)
        dockSettings = findViewById(R.id.dock_settings_layout)

        chatScroll = findViewById(R.id.chatScroll)



        setupDock()
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        myIdentity = prefs.getString("identity", "")?.replace("\\D".toRegex(), "") ?: ""




        receiverIdentity = intent.getStringExtra("peer_identity")
        val tvTitle = findViewById<TextView>(R.id.tvTitle)

        receiverIdentity?.let {
            tvTitle.text = formatPhoneNumber(it)
        }


        if (receiverIdentity == null) {
            finish()   // cannot open chat without peer
            return
        }

        loadStoredMessages()





        // -------- Language spinners --------
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.label }
        )


        spinnerTarget.adapter = adapter

        val savedLang = loadSelectedLanguage()
        val index = languages.indexOfFirst { it.translateCode == savedLang }
        spinnerTarget.setSelection(if (index >= 0) index else 0)






        spinnerTarget.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {

                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {


                    if (isListening) {
                        Toast.makeText(
                            this@ChatActivity,
                            "Stop microphone before changing language",
                            Toast.LENGTH_SHORT
                        ).show()

                        spinnerTarget.setSelection(
                            languages.indexOfFirst {
                                it.translateCode == loadSelectedLanguage()
                            }
                        )
                        return
                    }
                    val lang = languages[position].translateCode
                    saveSelectedLanguage(lang)

                    // 🔥 re-translate all messages for new language
                    for (msg in receivedMessages) {
                        translateMessageForDisplay(msg)
                    }
                    applyReceiverVisibility()
                    tvMicLang.text = languages[position].translateCode.uppercase()
                }


                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }


        // -------- Send Message --------
        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
        val text = inputMessage.text.toString().trim()
            if (receiverIdentity == null) {
                Toast.makeText(this, "Select a user first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (text.isNotEmpty()) {

                val msg = createSenderMessage(text)
                receivedMessages.add(msg)
                detectOriginalLanguage(msg)


                translate(text, "en") { translated ->
                    msg.englishText = translated
                    msg.englishView.text = translated
                    applyReceiverVisibility()
                }
                sendToBackend(text)

                lifecycleScope.launch {
                    DbProvider.db.chatDao().insert(
                        ChatEntity(
                            myIdentity = myIdentity,
                            peerIdentity = receiverIdentity!!,
                            fromIdentity = myIdentity,
                            originalText = text,
                            isRead = true   // sender message is always read
                        )
                    )
                }

                inputMessage.setText("")


            }
        }

        lifecycleScope.launch {
            DbProvider.db.chatDao()
                .markChatRead(myIdentity, receiverIdentity!!)
        }
    }

    private fun formatPhoneNumber(number: String): String {
        val clean = number.replace("\\D".toRegex(), "")

        return when {
            clean.startsWith("91") && clean.length == 12 ->
                "+91 ${clean.substring(2, 7)} ${clean.substring(7)}"
            clean.length == 10 ->
                "+91 ${clean.substring(0, 5)} ${clean.substring(5)}"
            else -> clean
        }
    }



    private fun startChatSTT() {

        tvMicLang.visibility = View.VISIBLE
        tvMicLang.alpha = 0f
        tvMicLang.scaleX = 0.8f
        tvMicLang.scaleY = 0.8f

        tvMicLang.text = languages[spinnerTarget.selectedItemPosition]
            .translateCode.uppercase()

        tvMicLang.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(200)
            .start()


        isListening = true

        speechRecognizer.setRecognitionListener(object :
            android.speech.RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {}

            override fun onBeginningOfSpeech() {}

            override fun onRmsChanged(rmsdB: Float) {}

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                isListening = false
                hideMicBadge()
            }


            override fun onError(error: Int) {
                isListening = false
                hideMicBadge()
            }

            override fun onResults(results: Bundle) {
                isListening = false
                hideMicBadge()
                val matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                if (!matches.isNullOrEmpty()) {

                    val spokenText = matches[0]

                    LanguageIdentification.getClient()
                        .identifyLanguage(spokenText)
                        .addOnSuccessListener { detected ->

                            val selectedLang =
                                languages[spinnerTarget.selectedItemPosition].translateCode

                            if (detected != "und" && detected != selectedLang) {
                                Toast.makeText(
                                    this@ChatActivity,
                                    "Detected language differs from selected.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }

                    inputMessage.setText(spokenText)
                    inputMessage.setSelection(spokenText.length)
                }
            }


            override fun onPartialResults(partialResults: Bundle) {
                val partial =
                    partialResults.getStringArrayList(
                        android.speech.SpeechRecognizer.RESULTS_RECOGNITION
                    )
                if (!partial.isNullOrEmpty()) {
                    inputMessage.setText(partial[0])
                    inputMessage.setSelection(inputMessage.text.length)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )

        val selected = languages[spinnerTarget.selectedItemPosition]

        speechIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE,
            selected.sttLocale
        )

        speechIntent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
            selected.sttLocale
        )




        speechRecognizer.startListening(speechIntent)
        tvMicLang.postDelayed({
            if (isListening) {
                stopChatSTT()
            }
        }, 6000) // 6 seconds timeout

    }

    private fun hideMicBadge() {
        tvMicLang.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                tvMicLang.visibility = View.GONE
            }
            .start()
    }


    private fun stopChatSTT() {
        tvMicLang.animate()
            .alpha(0f)
            .setDuration(150)
            .withEndAction {
                tvMicLang.visibility = View.GONE
            }
            .start()

        isListening = false
        speechRecognizer.stopListening()
    }

    private fun loadStoredMessages() {
        chatContainer.removeAllViews()
        receivedMessages.clear()

        val me = myIdentity
        val peer = receiverIdentity ?: return

        lifecycleScope.launch {
            val msgs = DbProvider.db.chatDao()
                .getConversation(me, peer)

            msgs.forEach { entity ->

                val msg = if (entity.fromIdentity == me) {
                    createSenderMessage(entity.originalText)
                } else {
                    createReceiverMessage(entity.fromIdentity, entity.originalText)
                }

                msg.dbId = entity.id
                receivedMessages.add(msg)

                // Detect language
                LanguageIdentification.getClient()
                    .identifyLanguage(entity.originalText)
                    .addOnSuccessListener { lang ->
                        msg.originalLang =
                            if (lang == "und" || lang.isBlank()) "en" else lang

                        // After detecting language → translate
                        translateMessageForDisplay(msg)
                    }
            }
        }
    }




    private fun detectOriginalLanguage(msg: ChatMessage) {
        LanguageIdentification.getClient()
            .identifyLanguage(msg.original)
            .addOnSuccessListener { lang ->
                msg.originalLang = if (lang == "und" || lang.isNullOrBlank()) {
                    "en"
                } else {
                    lang
                }
                applyReceiverVisibility()
            }
            .addOnFailureListener {
                msg.originalLang = "en"
                applyReceiverVisibility()
            }
    }

    private fun translateMessageForDisplay(msg: ChatMessage) {

        val selectedLang = languages[spinnerTarget.selectedItemPosition].translateCode
        val originalLang = msg.originalLang ?: "en"

        // --- ENGLISH CACHE ---
        if (originalLang != "en" && msg.englishText == null) {
            translate(msg.original, "en") { en ->
                msg.englishText = en
                msg.englishView.text = en
                applyReceiverVisibility()
            }
        }

        // --- TARGET CACHE ---
        if (selectedLang != "en" && selectedLang != originalLang &&
            !msg.targetTranslations.containsKey(selectedLang)
        ) {
            translate(msg.original, selectedLang) { translated ->
                msg.targetTranslations[selectedLang] = translated
                msg.targetView?.text = translated
                applyReceiverVisibility()
            }
        }

        applyReceiverVisibility()
    }





    private fun applyReceiverVisibility() {

        val selectedLang = languages[spinnerTarget.selectedItemPosition].translateCode

        for (msg in receivedMessages) {

            val originalView = msg.container.getChildAt(0) as TextView
            val englishView = msg.englishView
            val targetView = msg.targetView
            val originalLang = msg.originalLang ?: "en"

            originalView.visibility = View.GONE
            englishView.visibility = View.GONE
            targetView?.visibility = View.GONE

            val isSender = msg.from == myIdentity

            if (isSender) {
                originalView.visibility = View.VISIBLE

                if (originalLang != "en" && msg.englishText != null) {
                    englishView.visibility = View.VISIBLE
                    englishView.text = msg.englishText
                }

            } else {
                originalView.visibility = View.VISIBLE

                if (originalLang != "en" && msg.englishText != null) {
                    englishView.visibility = View.VISIBLE
                    englishView.text = msg.englishText
                }

                if (selectedLang != "en" &&
                    selectedLang != originalLang &&
                    msg.targetTranslations.containsKey(selectedLang)
                ) {
                    targetView?.visibility = View.VISIBLE
                    targetView?.text = msg.targetTranslations[selectedLang]
                }
            }
        }
    }







    private fun sameText(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        return a.trim().equals(b.trim(), ignoreCase = true)
    }

    // ================= Dock =================

    private fun setupDock() {
        highlightCurrentPage(dockChats)

        dockChats.setOnClickListener {
            startActivity(Intent(this, ChatListActivity::class.java))
            finish()
        }


        dockTranslate.setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        dockCalls.setOnClickListener {
            startActivity(Intent(this, CallActivity::class.java))
            finish()
        }

        dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
            finish()
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
            } catch (_: Exception) { }
        }
    }

    private fun scrollToBottom() {
        chatScroll.post {
            chatScroll.fullScroll(View.FOCUS_DOWN)
        }
    }



    private fun sendToBackend(original: String) {
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val from = prefs.getString("identity", "") ?: return
        val to = receiverIdentity ?: return

        val json = JSONObject().apply {
            put("from", from)
            put("to", to)
            put("original", original)
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://nodical-earlie-unyieldingly.ngrok-free.dev/chat/send")
            .post(body)
            .build()

        OkHttpClient().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) {}
        })
    }




    private val chatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {


            val from = intent?.getStringExtra("from")
            val original = intent?.getStringExtra("original")


            if (from == null || original == null) return

            val fromNorm = from.replace("\\D".toRegex(), "")
            if (fromNorm == myIdentity) return

            val msg = createReceiverMessage(from, original)


            receivedMessages.add(msg)
            detectOriginalLanguage(msg)





            // English (store once)
            translate(original, "en") { translated ->
                msg.englishText = translated
                msg.englishView.text = translated
                applyReceiverVisibility()
            }


            val selectedLang =
                languages[spinnerTarget.selectedItemPosition].translateCode


            if (selectedLang != "en") {
                translate(original, selectedLang) { translated ->
                    msg.targetTranslations[selectedLang] = translated
                    msg.targetView?.text = translated
                    applyReceiverVisibility()
                }
            }





        }

    }

    // ================= UI Bubble =================





    @RequiresApi(Build.VERSION_CODES.O)
    override fun onStart() {
        super.onStart()
        AppVisibility.currentChatPeer = receiverIdentity
        val filter = IntentFilter(ACTION_CHAT_MESSAGE)

        registerReceiver(
            chatReceiver,
            filter,
            Context.RECEIVER_NOT_EXPORTED
        )
    }



    private fun createSenderMessage(message: String): ChatMessage {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END
                setMargins(40, 8, 8, 8)
            }
        }

        val originalView = TextView(this).apply {
            text = message
            setTextColor(Color.WHITE)
            setPadding(24, 14, 24, 14)
            background = getDrawable(R.drawable.bg_original_sender)
        }

        val englishView = TextView(this).apply {
            text = "Translating..."
            setTextColor(Color.WHITE)
            setPadding(20, 10, 20, 10)
            background = getDrawable(R.drawable.bg_translated_sender)
        }

        container.addView(originalView)
        container.addView(englishView)
        chatContainer.addView(container)
        scrollToBottom()

        // ✅ CREATE msg FIRST
        val msg = ChatMessage(
            from = myIdentity,
            original = message,
            container = container,
            englishView = englishView,
            targetView = null
        )

        // ✅ NOW msg is available
        container.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        msg.dbId?.let {
                            DbProvider.db.chatDao().deleteMessage(it)
                        }
                        chatContainer.removeView(container)
                        receivedMessages.remove(msg)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        return msg
    }




    private fun createReceiverMessage(from: String, message: String): ChatMessage {

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START
                setMargins(8, 8, 40, 8)
            }
        }

        val originalView = TextView(this).apply {
            text = message
            setPadding(24, 14, 24, 14)
            background = getDrawable(R.drawable.bg_original_receiver)
        }

        val englishView = TextView(this).apply {
            text = "Translating..."
            setPadding(20, 10, 20, 10)
            background = getDrawable(R.drawable.bg_translated_receiver)
        }

        val targetView = TextView(this).apply {
            text = "Translating..."
            setPadding(20, 10, 20, 10)
            background = getDrawable(R.drawable.bg_translated_receiver)
        }

        container.addView(originalView)
        container.addView(englishView)
        container.addView(targetView)
        chatContainer.addView(container)
        scrollToBottom()

        // ✅ CREATE msg FIRST
        val msg = ChatMessage(
            from = from,
            original = message,
            container = container,
            englishView = englishView,
            targetView = targetView
        )

        // ✅ NOW msg exists
        container.setOnLongClickListener {
            AlertDialog.Builder(this)
                .setTitle("Delete message?")
                .setPositiveButton("Delete") { _, _ ->
                    lifecycleScope.launch {
                        msg.dbId?.let {
                            DbProvider.db.chatDao().deleteMessage(it)
                        }
                        chatContainer.removeView(container)
                        receivedMessages.remove(msg)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }

        return msg
    }

    private fun translate(
        text: String,
        target: String,
        onResult: (String) -> Unit
    ) {

        val json = JSONObject().apply {
            put("text", text)
            put("targetLang", target)
        }

        val body = json.toString()
            .toRequestBody("application/json".toMediaType())

        val req = Request.Builder()
            .url("https://nodical-earlie-unyieldingly.ngrok-free.dev/chat/translate")
            .post(body)
            .build()

        OkHttpClient().newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { onResult(text) }
            }

            override fun onResponse(call: Call, response: Response) {
                val result = response.body?.string()
                val translated =
                    JSONObject(result ?: "{}").optString("translated", text)

                runOnUiThread { onResult(translated) }
            }
        })
    }












    override fun onStop() {
        super.onStop()
        AppVisibility.currentChatPeer = null
        // 🔥 FORCE MARK READ WHEN LEAVING CHAT
        lifecycleScope.launch {
            DbProvider.db.chatDao()
                .markChatRead(myIdentity, receiverIdentity!!)
        }
        unregisterReceiver(chatReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer.destroy()
    }


}