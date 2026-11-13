package dev.pulsarconnect.app.ui.theme

import android.content.Context

internal const val THEME_PREFERENCES_NAME = "appearance"

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class ThemeSettings(
    val mode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
)

/** App-wide appearance choices, kept outside the sign-in session so they survive sign-out. */
class ThemeSettingsStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences(THEME_PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeSettings = ThemeSettings(
        mode = preferences.getString(KEY_MODE, null)
            ?.let { stored -> ThemeMode.entries.firstOrNull { it.name == stored } }
            ?: ThemeMode.SYSTEM,
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, true),
    )

    fun save(settings: ThemeSettings) {
        preferences.edit()
            .putString(KEY_MODE, settings.mode.name)
            .putBoolean(KEY_DYNAMIC_COLOR, settings.dynamicColor)
            .apply()
    }

    private companion object {
        const val KEY_MODE = "theme_mode"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
