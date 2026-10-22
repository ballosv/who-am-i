package de.ballosv.whoami

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import de.ballosv.whoami.core.GameMode
import de.ballosv.whoami.core.GameSettings
import de.ballosv.whoami.data.Prefs
import de.ballosv.whoami.platform.Invites
import de.ballosv.whoami.session.GameSession
import de.ballosv.whoami.session.GuestSession
import de.ballosv.whoami.session.HostSession

sealed interface Screen {
    data object Start : Screen
    data object MultiMenu : Screen
    data class Settings(val multiplayer: Boolean) : Screen
    data class Join(val code: String?) : Screen
    data object Session : Screen
}

class AppViewModel : ViewModel() {
    val prefs = Prefs()

    var screen by mutableStateOf<Screen>(Screen.Start)
        private set

    var session by mutableStateOf<GameSession?>(null)
        private set

    var inviteListening by mutableStateOf(prefs.inviteListening)
        private set

    fun navigate(target: Screen) {
        screen = target
    }

    fun back() {
        screen = when (val current = screen) {
            is Screen.Settings -> if (current.multiplayer) Screen.MultiMenu else Screen.Start
            is Screen.Join -> {
                closeSession()
                Screen.MultiMenu
            }
            Screen.MultiMenu -> Screen.Start
            Screen.Session -> {
                closeSession()
                Screen.Start
            }
            Screen.Start -> Screen.Start
        }
    }

    fun startSingle(settings: GameSettings) {
        prefs.saveSettings(settings)
        closeSession()
        val host = HostSession(settings.copy(mode = GameMode.SINGLE), prefs.playerId, prefs.playerName.ifBlank { "Du" })
        session = host
        host.startGame()
        screen = Screen.Session
    }

    fun hostMultiplayer(settings: GameSettings) {
        prefs.saveSettings(settings)
        closeSession()
        stopListener()
        session = HostSession(settings, prefs.playerId, prefs.playerName)
        screen = Screen.Session
    }

    fun openJoin(code: String?) {
        closeSession()
        stopListener()
        val guest = GuestSession(prefs.playerId, prefs.playerName)
        session = guest
        guest.startSearching()
        screen = Screen.Join(code?.uppercase())
    }

    /** Nach erfolgreicher Verbindung wechselt der Gast in die Lobby. */
    fun joined() {
        if (screen is Screen.Join) screen = Screen.Session
    }

    fun leaveSession() {
        closeSession()
        screen = Screen.Start
    }

    fun setPlayerName(name: String) {
        prefs.playerName = name
    }

    fun changeInviteListening(enabled: Boolean) {
        inviteListening = enabled
        prefs.inviteListening = enabled
        if (enabled && session == null) Invites.startListening() else stopListener()
    }

    private fun stopListener() = Invites.stopListening()

    private fun closeSession() {
        val old = session ?: return
        session = null
        old.close()
        if (inviteListening) Invites.startListening()
    }

    override fun onCleared() {
        session?.close()
        session = null
    }
}
