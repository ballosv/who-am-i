package de.ballosv.whoami.net

import de.ballosv.whoami.core.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Ein in der Nähe angebotenes Spiel. */
data class GameAd(val code: String, val hostName: String, val inviting: Boolean)

/** Ein gefundenes Spiel; [id] ist nur für den Weg gültig, über den es gefunden wurde. */
data class FoundGame(val id: String, val ad: GameAd)

/**
 * Host-Seite einer Verbindungsart (Nearby Connections auf Android, lokales WLAN auf beiden Plattformen).
 * Alle Rückrufe kommen auf dem Main-Thread.
 */
interface HostTransport {
    interface Listener {
        fun onPeerConnected(peerId: String)
        fun onPeerMessage(peerId: String, message: Message)
        fun onPeerDisconnected(peerId: String)
        fun onError(text: String)
    }

    val peers: Set<String>
    fun startAdvertising(ad: GameAd)
    fun stopAdvertising()
    fun send(peerId: String, message: Message)
    fun broadcast(message: Message)
    fun disconnect(peerId: String)
    fun stop()
}

/** Mitspieler-Seite einer Verbindungsart. */
interface GuestTransport {
    interface Listener {
        fun onConnected()
        fun onMessage(message: Message)
        fun onDisconnected(reason: String)
    }

    val games: StateFlow<List<FoundGame>>
    fun startDiscovery(onError: (String) -> Unit)
    fun stopDiscovery()
    fun connect(game: FoundGame, myName: String)
    fun send(message: Message)
    fun stop()
}

/** Plattformabhängig verfügbare Verbindungsarten, bevorzugte zuerst. */
expect fun createHostTransports(listener: HostTransport.Listener): List<HostTransport>

expect fun createGuestTransports(listener: GuestTransport.Listener): List<GuestTransport>

/** Bietet das Spiel über alle Verbindungsarten gleichzeitig an (z. B. Android-Nearby und WLAN für iPhones). */
class CompositeHost(private val transports: List<HostTransport>) : HostTransport {
    override val peers: Set<String> get() = transports.flatMapTo(LinkedHashSet()) { it.peers }

    private fun owner(peerId: String) = transports.firstOrNull { peerId in it.peers }

    override fun startAdvertising(ad: GameAd) = transports.forEach { it.startAdvertising(ad) }
    override fun stopAdvertising() = transports.forEach { it.stopAdvertising() }
    override fun send(peerId: String, message: Message) {
        owner(peerId)?.send(peerId, message)
    }

    override fun broadcast(message: Message) = transports.forEach { it.broadcast(message) }
    override fun disconnect(peerId: String) {
        owner(peerId)?.disconnect(peerId)
    }

    override fun stop() = transports.forEach { it.stop() }
}

/** Sucht über alle Verbindungsarten; ein Spiel, das mehrfach gefunden wird, zählt über die bevorzugte. */
class CompositeGuest(private val transports: List<GuestTransport>) : GuestTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var active: GuestTransport? = null

    override val games: StateFlow<List<FoundGame>> =
        combine(transports.map { it.games }) { lists ->
            val seen = HashSet<String>()
            lists.flatMap { it.toList() }.filter { seen.add(it.ad.code) }
        }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    override fun startDiscovery(onError: (String) -> Unit) = transports.forEach { it.startDiscovery(onError) }
    override fun stopDiscovery() = transports.forEach { it.stopDiscovery() }

    override fun connect(game: FoundGame, myName: String) {
        val transport = transports.firstOrNull { t -> t.games.value.any { it.id == game.id } } ?: return
        active = transport
        transports.filter { it !== transport }.forEach { it.stop() }
        transport.connect(game, myName)
    }

    override fun send(message: Message) {
        active?.send(message)
    }

    override fun stop() {
        transports.forEach { it.stop() }
        scope.cancel()
    }
}
