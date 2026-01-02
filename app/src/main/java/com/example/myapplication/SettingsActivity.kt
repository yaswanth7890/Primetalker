package com.example.myapplication

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView

class SettingsActivity : AppCompatActivity() {

    private lateinit var spinnerLang: Spinner
    private lateinit var switchNotifications: Switch
    private lateinit var switchDarkMode: Switch
    private lateinit var imgProfile: ImageView
    private lateinit var edtProfileName: EditText
    private lateinit var valueName: TextView
    private lateinit var launcher: ActivityResultLauncher<String>

    private lateinit var dockChats: LinearLayout
    private lateinit var dockTranslate: LinearLayout
    private lateinit var dockCalls: LinearLayout
    private lateinit var dockSettings: LinearLayout

    private val languages = listOf("English", "Hindi", "Telugu", "Tamil", "Kannada")
    private val languageMap = mapOf(
        "English" to "en",
        "Hindi" to "hi",
        "Telugu" to "te",
        "Tamil" to "ta",
        "Kannada" to "kn"
    )

    private val prefs by lazy { getSharedPreferences("app_prefs", MODE_PRIVATE) }

    private var preferredLanguage: String
        get() = prefs.getString("preferred_language", "en") ?: "en"
        set(v) = prefs.edit().putString("preferred_language", v).apply()

    private var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notifications_enabled", true)
        set(v) = prefs.edit().putBoolean("notifications_enabled", v).apply()

    private var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(v) = prefs.edit().putBoolean("dark_mode", v).apply()

    private var profileUri: String?
        get() = prefs.getString("profile_uri", null)
        set(v) = prefs.edit().putString("profile_uri", v).apply()

    private var profileName: String
        get() = prefs.getString("profile_name", "") ?: ""
        set(v) = prefs.edit().putString("profile_name", v).apply()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LangManager.init(this)
        setContentView(R.layout.activity_settings)

        spinnerLang = findViewById(R.id.spinnerPreferredLang)
        switchNotifications = findViewById(R.id.switchNotifications)
        switchDarkMode = findViewById(R.id.switchDarkMode)
        imgProfile = findViewById(R.id.imgProfile)
        edtProfileName = findViewById(R.id.edtProfileName)
        valueName = findViewById(R.id.tvName)

        dockChats = findViewById(R.id.dock_chats_layout)
        dockTranslate = findViewById(R.id.dock_translate_layout)
        dockCalls = findViewById(R.id.dock_calls_layout)
        dockSettings = findViewById(R.id.dock_settings_layout)

        // Bottom dock
        highlightCurrentPage(dockSettings)
        dockChats.setOnClickListener { startActivity(Intent(this, ChatActivity::class.java)) }
        dockCalls.setOnClickListener { startActivity(Intent(this, CallActivity::class.java)) }
        dockTranslate.setOnClickListener { startActivity(Intent(this, MainActivity::class.java)) }

        // Language spinner
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, languages) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent) as TextView
                view.setTextColor(if (darkMode) Color.WHITE else Color.BLACK)
                view.setBackgroundColor(if (darkMode) Color.parseColor("#121212") else Color.WHITE)
                return view
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getDropDownView(position, convertView, parent) as TextView
                view.setTextColor(if (darkMode) Color.WHITE else Color.BLACK)
                view.setBackgroundColor(if (darkMode) Color.parseColor("#1E1E1E") else Color.WHITE)
                return view
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerLang.adapter = adapter
        spinnerLang.setSelection(languages.indexOfFirst { languageMap[it] == preferredLanguage }.coerceAtLeast(0))
        spinnerLang.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            var firstCall = true
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (firstCall) { firstCall = false; return }
                val selectedCode = languageMap[languages[position]] ?: "en"
                if (selectedCode != preferredLanguage) {
                    LangManager.setLanguage(this@SettingsActivity, selectedCode)
                    preferredLanguage = selectedCode
                    applyTranslations()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Switches
        switchNotifications.isChecked = notificationsEnabled
        switchNotifications.setOnCheckedChangeListener { _, isChecked -> notificationsEnabled = isChecked }

        switchDarkMode.isChecked = darkMode
        applyDarkMode(darkMode) // apply immediately
        switchDarkMode.setOnCheckedChangeListener { _, isChecked ->
            if (darkMode != isChecked) {
                darkMode = isChecked
                applyDarkMode(isChecked) // apply UI colors without recreate
            }
        }

        // Profile image
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let {
                imgProfile.setImageURI(it)
                profileUri = it.toString()
            }
        }
        imgProfile.setOnClickListener { launcher.launch("image/*") }
        profileUri?.let { imgProfile.setImageURI(Uri.parse(it)) }

        // Profile name
        edtProfileName.setText(profileName)
        valueName.text = profileName
        edtProfileName.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                profileName = edtProfileName.text.toString()
                valueName.text = profileName
            }
        }

        // ✅ Display the logged-in phone number from SharedPreferences
        val userPhone = prefs.getString("user_phone", "N/A")
        findViewById<TextView>(R.id.valuePhone).text = userPhone

        // Logout
        findViewById<Button>(R.id.btnLogout).setOnClickListener {
            prefs.edit().clear().apply()
            startActivity(Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        applyTranslations()
    }

    private fun applyTranslations() {
        findViewById<TextView>(R.id.tvSettingsTitle).text = LangManager.get("settings")
        findViewById<TextView>(R.id.labelName).text = LangManager.get("name")
        findViewById<TextView>(R.id.labelPhone).text = LangManager.get("phone")
        findViewById<TextView>(R.id.labelLang).text = LangManager.get("preferred_language")
        findViewById<TextView>(R.id.labelNotifications).text = LangManager.get("notifications")
        findViewById<TextView>(R.id.labelDarkMode).text = LangManager.get("dark_mode")
        findViewById<Button>(R.id.btnLogout).text = LangManager.get("logout")
        findViewById<TextView>(R.id.tvCreditsTitle).text = LangManager.get("credits")
        findViewById<TextView>(R.id.tvBalance).text = LangManager.get("current_balance")
        findViewById<TextView>(R.id.tvAccountHeader).text = LangManager.get("account_details")
        findViewById<TextView>(R.id.tvPrefHeader).text = LangManager.get("preferences")
    }

    private fun applyDarkMode(dark: Boolean) {
        val bgColor = if (dark) Color.parseColor("#121212") else Color.parseColor("#F8FAFC")
        val cardColor = if (dark) Color.parseColor("#1E1E1E") else Color.WHITE
        val textPrimary = if (dark) Color.WHITE else Color.parseColor("#0F172A")
        val textSecondary = if (dark) Color.LTGRAY else Color.parseColor("#64748B")
        val accentColor = Color.parseColor("#6366F1")

        findViewById<ScrollView>(R.id.scrollRoot).setBackgroundColor(bgColor)
        findViewById<LinearLayout>(R.id.container).setBackgroundColor(bgColor)

        listOf(R.id.cardCredits, R.id.cardAccount, R.id.cardPrefs).forEach { id ->
            findViewById<CardView>(id).setCardBackgroundColor(cardColor)
        }

        val textViews = listOf(
            R.id.tvSettingsTitle, R.id.tvName, R.id.tvEmail, R.id.tvCreditsTitle, R.id.tvBalance,
            R.id.tvAccountHeader, R.id.labelName, R.id.labelPhone, R.id.valuePhone,
            R.id.labelLang, R.id.tvPrefHeader, R.id.labelNotifications, R.id.labelDarkMode
        )
        textViews.forEach { id ->
            findViewById<TextView>(id)?.setTextColor(
                when (id) {
                    R.id.tvBalance, R.id.valuePhone -> accentColor
                    else -> textPrimary
                }
            )
        }

        edtProfileName.setTextColor(textPrimary)
        edtProfileName.setHintTextColor(textSecondary)
        findViewById<Button>(R.id.btnLogout).setTextColor(Color.parseColor("#F87171"))
    }

    private fun highlightCurrentPage(current: LinearLayout) {
        val docks = listOf(dockChats, dockTranslate, dockCalls, dockSettings)
        docks.forEach { dock ->
            try {
                val img = dock.getChildAt(0) as ImageView
                val text = dock.getChildAt(1) as TextView
                if (dock == current) {
                    img.setColorFilter(Color.parseColor("#FFA500"))
                    text.setTextColor(Color.parseColor("#FFA500"))
                } else {
                    img.setColorFilter(Color.WHITE)
                    text.setTextColor(Color.WHITE)
                }
            } catch (_: Exception) {}
        }
    }
}