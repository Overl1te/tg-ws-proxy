package com.flowseal.tgwsproxyandroid

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

enum class AppThemeMode(
    val storageValue: String,
    val nightMode: Int,
) {
    SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES),
    ;

    companion object {
        fun fromStorage(value: String?): AppThemeMode =
            entries.firstOrNull { it.storageValue == value } ?: SYSTEM
    }
}

data class UiPreferences(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val logsExpanded: Boolean = false,
)

object UiPreferencesStore {
    private const val PREFS_NAME = "tg_ws_proxy_android_ui"
    private const val KEY_THEME_MODE = "theme_mode"
    private const val KEY_LOGS_EXPANDED = "logs_expanded"

    fun load(context: Context): UiPreferences {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return UiPreferences(
            themeMode = AppThemeMode.fromStorage(prefs.getString(KEY_THEME_MODE, null)),
            logsExpanded = prefs.getBoolean(KEY_LOGS_EXPANDED, false),
        )
    }

    fun saveThemeMode(context: Context, themeMode: AppThemeMode) {
        prefs(context).edit()
            .putString(KEY_THEME_MODE, themeMode.storageValue)
            .apply()
    }

    fun saveLogsExpanded(context: Context, expanded: Boolean) {
        prefs(context).edit()
            .putBoolean(KEY_LOGS_EXPANDED, expanded)
            .apply()
    }

    fun applyThemeMode(themeMode: AppThemeMode) {
        AppCompatDelegate.setDefaultNightMode(themeMode.nightMode)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
