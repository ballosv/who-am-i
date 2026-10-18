package de.ballosv.whoami.data

import android.content.Context
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSettings
import de.ballosv.whoami.core.Message
import java.util.UUID

/** Dauerhaft gespeicherte Einstellungen: Spielername, Geräte-ID und zuletzt genutzte Spieleinstellungen. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("whoami", Context.MODE_PRIVATE)

    val playerId: String
        get() = sp.getString(KEY_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_ID, it).apply()
        }

    var playerName: String
        get() = sp.getString(KEY_NAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_NAME, value.trim()).apply()

    var inviteListening: Boolean
        get() = sp.getBoolean(KEY_LISTEN, false)
        set(value) = sp.edit().putBoolean(KEY_LISTEN, value).apply()

    /** Einzel- und Mehrspieler haben getrennte Einstellungen; Battle teilt sich die des Mehrspielers. */
    fun loadSettings(mode: GameMode): GameSettings {
        val key = settingsKey(mode)
        val raw = sp.getString(key, null)
        val base = raw?.let { runCatching { Message.json.decodeFromString(GameSettings.serializer(), it) }.getOrNull() }
            ?: GameSettings()
        return base.copy(mode = mode).normalized()
    }

    fun saveSettings(settings: GameSettings) {
        sp.edit().putString(settingsKey(settings.mode), Message.json.encodeToString(GameSettings.serializer(), settings)).apply()
    }

    private fun settingsKey(mode: GameMode) = if (mode == GameMode.SINGLE) "settings_single" else "settings_multi"

    private companion object {
        const val KEY_ID = "player_id"
        const val KEY_NAME = "player_name"
        const val KEY_LISTEN = "invite_listening"
    }
}
