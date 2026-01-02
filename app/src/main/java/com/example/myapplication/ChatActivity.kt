
package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.db.ChatEntity
import kotlinx.coroutines.launch
import com.example.myapplication.db.DbProvider



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

    private val languages = listOf(
        "English" to TranslateLanguage.ENGLISH,
        "Hindi" to TranslateLanguage.HINDI,
        "Telugu" to TranslateLanguage.TELUGU,
        "French" to TranslateLanguage.FRENCH,
        "Spanish" to TranslateLanguage.SPANISH
    )






    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)

        // -------- Bind Views --------
        chatContainer = findViewById(R.id.chatContainer)
        inputMessage = findViewById(R.id.inputMessage)

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

        getSharedPreferences("chat_store", MODE_PRIVATE)
            .edit()
            .clear()
            .apply()


        receiverIdentity = intent.getStringExtra("peer_identity")

        receiverIdentity = intent.getStringExtra("peer_identity")

        if (receiverIdentity == null) {
            finish()   // cannot open chat without peer
            return
        }

        loadStoredMessages()





        // -------- Language spinners --------
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages.map { it.first }
        )

        spinnerTarget.adapter = adapter

        spinnerTarget.setSelection(0)




        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {

            override fun onItemSelected(
                parent: AdapterView<*>?,
                ignoredView: View?,
                position: Int,
                id: Long
            ) {
                applyReceiverVisibility()
            }


            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }











        // -------- Send Message --------
        findViewById<Button>(R.id.btnSend).setOnClickListener {
            val text = inputMessage.text.toString().trim()

            if (receiverIdentity == null) {
                Toast.makeText(this, "Select a user first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (text.isNotEmpty()) {

                val msg = createSenderMessage(text)
                receivedMessages.add(msg)
                detectOriginalLanguage(msg)


                translate(text, TranslateLanguage.ENGLISH) { translated ->
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
                            originalText = text
                        )
                    )
                }


                inputMessage.setText("")

            }

        }

    }





    private fun loadStoredMessages() {
        val me = myIdentity
        val peer = receiverIdentity ?: return

        lifecycleScope.launch {
            val msgs = DbProvider.db.chatDao()
                .getConversation(me, peer)

            msgs.forEach {
                val msg = if (it.fromIdentity == me) {
                    createSenderMessage(it.originalText)
                } else {
                    createReceiverMessage(it.fromIdentity, it.originalText)
                }
                msg.dbId = it.id
                receivedMessages.add(msg)
                detectOriginalLanguage(msg)
            }
        }
    }


    private fun detectOriginalLanguage(msg: ChatMessage) {
        if (msg.originalLang != null) return

        LanguageIdentification.getClient()
            .identifyLanguage(msg.original)
            .addOnSuccessListener { lang ->
                msg.originalLang = lang
                applyReceiverVisibility()   // 🔥 ADD THIS
            }
    }


    private fun applyReceiverVisibility() {
        val selectedLang = languages[spinnerTarget.selectedItemPosition].second

        for (msg in receivedMessages) {

            val originalView = msg.container.getChildAt(0) as TextView
            val englishView = msg.englishView
            val targetView = msg.targetView
            val originalLang = msg.originalLang ?: continue

            // 1️⃣ ORIGINAL — always visible
            originalView.visibility = View.VISIBLE

            // 2️⃣ ENGLISH — always visible if original ≠ English
            if (originalLang != TranslateLanguage.ENGLISH) {
                englishView.visibility = View.VISIBLE

                if (msg.englishText != null) {
                    englishView.text = msg.englishText
                } else {
                    englishView.text = "Translating..."
                    translate(msg.original, TranslateLanguage.ENGLISH) { translated ->
                        msg.englishText = translated
                        englishView.text = translated
                    }
                }
            } else {
                englishView.visibility = View.GONE
            }

            // 3️⃣ TARGET TRANSLATION (spinner-based)
            if (
                targetView != null &&
                selectedLang != TranslateLanguage.ENGLISH &&
                selectedLang != originalLang
            ) {
                targetView.visibility = View.VISIBLE

                val cached = msg.targetTranslations[selectedLang]
                if (cached != null) {
                    targetView.text = cached
                } else {
                    targetView.text = "Translating..."
                    translate(msg.original, selectedLang) {
                        msg.targetTranslations[selectedLang] = it
                        targetView.text = it
                    }
                }
            } else {
                targetView?.visibility = View.GONE
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
            lifecycleScope.launch {
                DbProvider.db.chatDao().insert(
                    ChatEntity(
                        myIdentity = myIdentity,
                        peerIdentity = from,
                        fromIdentity = from,
                        originalText = original
                    )
                )
            }

            receivedMessages.add(msg)
            detectOriginalLanguage(msg)





            // English (store once)
            translate(original, TranslateLanguage.ENGLISH) { translated ->
                msg.englishText = translated
                msg.englishView.text = translated
                applyReceiverVisibility()
            }


            val selectedLang = languages[spinnerTarget.selectedItemPosition].second

            if (selectedLang != TranslateLanguage.ENGLISH) {
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
        LanguageIdentification.getClient()
            .identifyLanguage(text)
            .addOnSuccessListener { sourceLang ->

                // SAFETY: if already same language
                if (sourceLang == target) {
                    onResult(text)
                    applyReceiverVisibility()
                    return@addOnSuccessListener
                }

                val translator = Translation.getClient(
                    TranslatorOptions.Builder()
                        .setSourceLanguage(sourceLang)
                        .setTargetLanguage(target)
                        .build()
                )

                translator.downloadModelIfNeeded()
                    .addOnSuccessListener {
                        translator.translate(text)
                            .addOnSuccessListener { translated ->
                                onResult(translated)
                                applyReceiverVisibility() // 🔥 FORCE UI REFRESH
                            }
                            .addOnFailureListener {
                                applyReceiverVisibility()
                            }
                    }
                    .addOnFailureListener {
                        applyReceiverVisibility()
                    }
            }
            .addOnFailureListener {
                applyReceiverVisibility()
            }
    }








    override fun onStop() {
        super.onStop()
        unregisterReceiver(chatReceiver)
    }



}