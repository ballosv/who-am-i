package de.ballosv.whoami.data

import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSettings
import de.ballosv.whoami.core.Message
import de.ballosv.whoami.platform.KeyValueStore
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Dauerhaft gespeicherte Einstellungen: Spielername, Geräte-ID und zuletzt genutzte Spieleinstellungen. */
@OptIn(ExperimentalUuidApi::class)
class Prefs {
    private val sp = KeyValueStore("whoami")

    val playerId: String
        get() = sp.getString(KEY_ID) ?: Uuid.random().toString().also { sp.putString(KEY_ID, it) }

    var playerName: String
        get() = sp.getString(KEY_NAME) ?: ""
        set(value) = sp.putString(KEY_NAME, value.trim())

    var inviteListening: Boolean
        get() = sp.getBoolean(KEY_LISTEN, false)
        set(value) = sp.putBoolean(KEY_LISTEN, value)

    /** Einzel- und Mehrspieler haben getrennte Einstellungen; Battle teilt sich die des Mehrspielers. */
    fun loadSettings(mode: GameMode): GameSettings {
        val key = settingsKey(mode)
        val raw = sp.getString(key)
        val base = raw?.let { runCatching { Message.json.decodeFromString(GameSettings.serializer(), it) }.getOrNull() }
            ?: GameSettings()
        return base.copy(mode = mode).normalized()
    }

    fun saveSettings(settings: GameSettings) {
        sp.putString(settingsKey(settings.mode), Message.json.encodeToString(GameSettings.serializer(), settings))
    }

    private fun settingsKey(mode: GameMode) = if (mode == GameMode.SINGLE) "settings_single" else "settings_multi"

    private companion object {
        const val KEY_ID = "player_id"
        const val KEY_NAME = "player_name"
        const val KEY_LISTEN = "invite_listening"
    }
}
