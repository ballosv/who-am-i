package de.ballosv.whoami.session

import de.ballosv.whoami.content.ImageStore
import de.ballosv.whoami.core.GameSnapshot
import kotlinx.coroutines.flow.StateFlow

sealed interface SessionStatus {
    data object Searching : SessionStatus
    data object Connecting : SessionStatus
    data object Connected : SessionStatus
    data class Ended(val reason: String) : SessionStatus
}

/** Ein eigenes Bild für den Battle-Modus. */
class BattleEntry(val id: String, val jpeg: ByteArray, val firstName: String, val lastName: String)

/** Gemeinsame Sicht der Oberfläche auf ein laufendes Spiel – egal ob Host oder Gast. */
interface GameSession {
    val myId: String
    val isHost: Boolean
    val snapshot: StateFlow<GameSnapshot?>
    val status: StateFlow<SessionStatus>
    val images: ImageStore
    val battleEntries: StateFlow<List<BattleEntry>>

    /** Kurzer Hinweis für die Oberfläche (z. B. Fehler), wird mit [consumeNotice] quittiert. */
    val notice: StateFlow<String?>

    /** Aktuelle Zeit auf der (synchronisierten) Host-Uhr. */
    fun hostNow(): Long

    fun setName(name: String)
    fun setReady(ready: Boolean)
    fun setBattleEntries(entries: List<BattleEntry>)
    fun buzz()
    fun answer(text: String)
    fun consumeNotice()
    fun close()
}
