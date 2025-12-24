package com.example.myapplication

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate

open class BaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply dark mode before super.onCreate
        applyDarkMode()
        super.onCreate(savedInstanceState)
        applyLanguage()
    }

    private fun applyDarkMode() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val darkMode = prefs.getBoolean("dark_mode", false)

        AppCompatDelegate.setDefaultNightMode(
            if (darkMode) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    private fun applyLanguage() {
        val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        val langCode = prefs.getString("preferred_language", "en") ?: "en"
        LangManager.setLanguage(this, langCode)
    }
}
