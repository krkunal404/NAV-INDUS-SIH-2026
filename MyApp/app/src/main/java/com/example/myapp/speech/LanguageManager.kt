package com.example.myapp.speech

import android.content.Context

object LanguageManager {

    enum class Language(val code: String, val displayName: String) {
        ENGLISH("en", "English"),
        HINDI("hi", "Hindi")
    }

    private const val PREFS_NAME = "nimo_prefs"
    private const val KEY_LANG = "selected_language"

    var currentLanguage: Language = Language.ENGLISH
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANG, Language.ENGLISH.code)
        currentLanguage = if (saved == Language.HINDI.code) Language.HINDI else Language.ENGLISH
    }

    fun setLanguage(context: Context, language: Language) {
        currentLanguage = language
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANG, language.code).apply()
    }

    fun isHindi(): Boolean = currentLanguage == Language.HINDI
}
