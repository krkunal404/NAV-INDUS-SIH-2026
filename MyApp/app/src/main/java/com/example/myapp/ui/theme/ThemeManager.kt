package com.example.myapp.ui.theme

import android.content.Context

object ThemeManager {

    enum class AppTheme(val displayName: String) {
        LIGHT("Light ☀️"),
        DARK("Dark 🌙")
    }

    private const val PREFS_NAME = "nimo_theme_prefs"
    private const val KEY_THEME = "selected_theme"

    var currentTheme: AppTheme = AppTheme.LIGHT
        private set

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_THEME, AppTheme.LIGHT.name)
        currentTheme = try { AppTheme.valueOf(saved!!) } catch (e: Exception) { AppTheme.LIGHT }
    }

    fun setTheme(context: Context, theme: AppTheme) {
        currentTheme = theme
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_THEME, theme.name).apply()
    }

    fun isLight(): Boolean = currentTheme == AppTheme.LIGHT
}