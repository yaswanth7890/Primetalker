package com.example.myapplication

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import java.io.IOException
import java.util.*
import android.util.Log


class MainActivity : AppCompatActivity() {

    private lateinit var spinnerSource: Spinner
    private lateinit var spinnerTarget: Spinner
    private lateinit var inputEditText: EditText
    private lateinit var outputTextView: TextView
    private lateinit var btnTranslate: Button
    private lateinit var btnSwap: ImageView
    private lateinit var btnOpenCall: Button
    private var tts: TextToSpeech? = null
    private var translator: Translator? = null
    private var currentTTSLocale: Locale? = null

    // Dock icons
    private lateinit var dockChats: LinearLayout
    private lateinit var dockTranslate: LinearLayout
    private lateinit var dockCalls: LinearLayout
    private lateinit var dockSettings: LinearLayout

    private val languageItems = listOf(
        "English" to TranslateLanguage.ENGLISH,
        "Hindi" to TranslateLanguage.HINDI,
        "Telugu" to TranslateLanguage.TELUGU,
        "French" to TranslateLanguage.FRENCH,
        "Spanish" to TranslateLanguage.SPANISH,
        "German" to TranslateLanguage.GERMAN,
        "Chinese (Simplified)" to TranslateLanguage.CHINESE,
        "Japanese" to TranslateLanguage.JAPANESE,
        "Korean" to TranslateLanguage.KOREAN,
        "Arabic" to TranslateLanguage.ARABIC,
        "Russian" to TranslateLanguage.RUSSIAN
    )

    private val languageLocaleMap = mapOf(
        TranslateLanguage.ENGLISH to Locale.ENGLISH,
        TranslateLanguage.HINDI to Locale("hi"),
        TranslateLanguage.TELUGU to Locale("te"),
        TranslateLanguage.FRENCH to Locale.FRENCH,
        TranslateLanguage.SPANISH to Locale("es"),
        TranslateLanguage.GERMAN to Locale.GERMAN,
        TranslateLanguage.CHINESE to Locale.CHINESE,
        TranslateLanguage.JAPANESE to Locale.JAPANESE,
        TranslateLanguage.KOREAN to Locale.KOREAN,
        TranslateLanguage.ARABIC to Locale("ar"),
        TranslateLanguage.RUSSIAN to Locale("ru")
    )

    private val transliterationMap = mapOf(
        TranslateLanguage.HINDI to "hi-t-i0-und",
        TranslateLanguage.TELUGU to "te-t-i0-und"
    )

    private val voiceInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val list = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                val spoken = list?.firstOrNull().orEmpty()
                inputEditText.setText(spoken)
                if (spoken.isNotEmpty()) detectLanguage(spoken)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)






        // Translation UI
        spinnerSource = findViewById(R.id.spinnerSource)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        inputEditText = findViewById(R.id.inputEditText)
        outputTextView = findViewById(R.id.outputTextView)
        val btnVoice = findViewById<FloatingActionButton>(R.id.btnVoice)
        btnTranslate = findViewById(R.id.btnTranslate)
        val btnSpeakOut = findViewById<Button>(R.id.btnSpeakOut)
        btnSwap = findViewById(R.id.btnSwap)
        btnOpenCall = findViewById(R.id.btnOpenCall)

        // Dock icons
        dockChats = findViewById(R.id.dock_chats_layout)
        dockTranslate = findViewById(R.id.dock_translate_layout)
        dockCalls = findViewById(R.id.dock_calls_layout)
        dockSettings = findViewById(R.id.dock_settings_layout)

        setupDock()

        val names = languageItems.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinnerSource.adapter = adapter
        spinnerTarget.adapter = adapter
        spinnerSource.setSelection(names.indexOf("English").coerceAtLeast(0))
        spinnerTarget.setSelection(names.indexOf("Hindi").coerceAtLeast(0))

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.ENGLISH
                currentTTSLocale = Locale.ENGLISH
            }
        }

        btnVoice.setOnClickListener { startVoiceInput() }
        btnTranslate.setOnClickListener { doTranslate() }
        btnSpeakOut.setOnClickListener { speakOut() }

        btnSwap.setOnClickListener {
            val srcPos = spinnerSource.selectedItemPosition
            val tgtPos = spinnerTarget.selectedItemPosition
            spinnerSource.setSelection(tgtPos)
            spinnerTarget.setSelection(srcPos)
            val inputText = inputEditText.text.toString()
            val outputText = outputTextView.text.toString()
            inputEditText.setText(outputText)
            outputTextView.text = inputText
        }

        btnOpenCall.setOnClickListener {
            startActivity(Intent(this, CallActivity::class.java))
        }

        inputEditText.addTextChangedListener(object : TextWatcher {
            private var isUpdating = false
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (isUpdating) return
                val text = s.toString()
                if (text.isBlank()) return
                val srcCode = languageItems[spinnerSource.selectedItemPosition].second
                val translitCode = transliterationMap[srcCode]
                if (translitCode != null) {
                    transliterateText(text, translitCode) { transliterated ->
                        if (transliterated != text) {
                            isUpdating = true
                            inputEditText.setText(transliterated)
                            inputEditText.setSelection(transliterated.length)
                            isUpdating = false
                        }
                    }
                }
            }
        })
    }

    // --- Dock setup ---
    private fun setupDock() {
        highlightCurrentPage(dockTranslate)

        dockChats.setOnClickListener {
            startActivity(Intent(this, CallActivity::class.java))
        }
        dockCalls.setOnClickListener {
            startActivity(Intent(this, CallActivity::class.java))
        }
        dockSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun highlightCurrentPage(current: LinearLayout) {
        val docks = listOf(dockChats, dockTranslate, dockCalls, dockSettings)
        docks.forEach { dock ->
            val img = dock.getChildAt(0) as ImageView
            val text = dock.getChildAt(1) as TextView
            if (dock == current) {
                img.setColorFilter(resources.getColor(android.R.color.holo_orange_light))
                text.setTextColor(resources.getColor(android.R.color.holo_orange_light))
            } else {
                img.setColorFilter(resources.getColor(android.R.color.white))
                text.setTextColor(resources.getColor(android.R.color.white))
            }
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        voiceInputLauncher.launch(intent)
    }

    private fun detectLanguage(text: String) {
        val languageIdentifier = LanguageIdentification.getClient()
        languageIdentifier.identifyLanguage(text)
            .addOnSuccessListener { langCode ->
                if (langCode != "und") {
                    val index = languageItems.indexOfFirst { it.second == langCode }
                    if (index != -1) spinnerSource.setSelection(index)
                }
            }
    }

    private fun doTranslate() {
        val text = inputEditText.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Enter or speak some text", Toast.LENGTH_SHORT).show()
            return
        }
        val srcCode = languageItems[spinnerSource.selectedItemPosition].second
        val tgtCode = languageItems[spinnerTarget.selectedItemPosition].second
        runTranslation(text, srcCode, tgtCode)
    }

    private fun runTranslation(text: String, srcCode: String, tgtCode: String) {
        translator?.close()
        translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(srcCode).setTargetLanguage(tgtCode).build()
        )
        val conditions = DownloadConditions.Builder().requireWifi().build()
        translator!!.downloadModelIfNeeded(conditions)
            .addOnSuccessListener {
                translator!!.translate(text)
                    .addOnSuccessListener { translated -> outputTextView.text = translated }
                    .addOnFailureListener { e -> outputTextView.text = "Translation failed: ${e.message}" }
            }
            .addOnFailureListener { e -> outputTextView.text = "Model download failed: ${e.message}" }
    }

    private fun transliterateText(input: String, langCode: String, callback: (String) -> Unit) {
        val client = OkHttpClient()
        val url = "https://inputtools.google.com/request?itc=$langCode&num=1&cp=0&cs=1&ie=utf-8&oe=utf-8&app=demopage"
        val mediaType = "application/x-www-form-urlencoded".toMediaType()
        val body = "text=$input".toRequestBody(mediaType)
        val request = Request.Builder().url(url).post(body).build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { runOnUiThread { callback(input) } }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val res = it.body?.string()
                    try {
                        val arr = JSONArray(res)
                        val inner = arr.getJSONArray(1).getJSONArray(0).getJSONArray(1)
                        val transliterated = inner.getString(0)
                        runOnUiThread { callback(transliterated) }
                    } catch (e: Exception) { runOnUiThread { callback(input) } }
                }
            }
        })
    }

    private fun speakOut() {
        val text = outputTextView.text.toString()
        if (text.isBlank()) {
            Toast.makeText(this, "Nothing to read", Toast.LENGTH_SHORT).show()
            return
        }
        val tgtCode = languageItems[spinnerTarget.selectedItemPosition].second
        val targetLocale = languageLocaleMap[tgtCode] ?: Locale.ENGLISH
        if (currentTTSLocale != targetLocale) {
            if (tts?.isLanguageAvailable(targetLocale) == TextToSpeech.LANG_AVAILABLE) {
                tts?.language = targetLocale
                currentTTSLocale = targetLocale
            }
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts-translate")
    }

    override fun onDestroy() {
        translator?.close()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    // --- BACK BUTTON OVERRIDE ---

    override fun onBackPressed() {
        super.onBackPressed()
        // Show exit confirmation dialog
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Exit App")
        builder.setMessage("Do you want to exit?")
        builder.setPositiveButton("Yes") { _, _ ->
            finishAffinity() // Close all activities and exit app
        }
        builder.setNegativeButton("No") { dialog, _ ->
            dialog.dismiss() // Just dismiss the dialog
        }
        builder.create().show()
    }






}
