package com.example.myapplication

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import org.json.JSONObject
import java.util.*

object LangManager {
    private var translations: JSONObject? = null
    private var currentLang: String = "en"

    fun init(context: Context) {
        if (translations == null) {
            val json = context.assets.open("lang.json").bufferedReader().use { it.readText() }
            translations = JSONObject(json)
        }
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        currentLang = prefs.getString("preferred_language", "en") ?: "en"
    }

    fun setLanguage(context: Context, langCode: String): Context {
        currentLang = langCode
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString("preferred_language", langCode).apply()

        return updateLocale(context, langCode)
    }

    fun get(key: String): String {
        return try {
            translations?.getJSONObject(currentLang)?.getString(key) ?: key
        } catch (e: Exception) {
            key
        }
    }

    private fun updateLocale(context: Context, langCode: String): Context {
        val locale = Locale(langCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.createConfigurationContext(config)
        } else {
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
            context
        }
    }
}
