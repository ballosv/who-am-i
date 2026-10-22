package de.ballosv.whoami.net

import de.ballosv.whoami.core.Message
import de.ballosv.whoami.platform.localIpv4
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readInt
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Mehrspieler über das lokale WLAN (bzw. den Hotspot eines Handys): funktioniert zwischen
 * Android und iPhone. Gefunden wird das Spiel per Bonjour/NSD; der Dienstname enthält schon
 * Adresse und Port, deshalb muss nichts weiter aufgelöst werden.
 *
 * Nachrichten werden als Länge (4 Byte) + JSON (UTF-8) über TCP übertragen.
 */
object LanConfig {
    const val SERVICE_TYPE = "_whoami._tcp"
    private const val PREFIX = "WAI1"
    private const val MAX_NAME_BYTES = 63
    const val MAX_FRAME = 1 shl 20

    data class Endpoint(val ad: GameAd, val host: String, val port: Int)

    fun encode(ad: GameAd, host: String, port: Int): String {
        val head = listOf(PREFIX, ad.code, if (ad.inviting) "1" else "0", host, port.toString()).joinToString("|") + "|"
        var name = ad.hostName.replace("|", " ")
        // Bonjour-Namen dürfen höchstens 63 Byte lang sein.
        while ((head + name).encodeToByteArray().size > MAX_NAME_BYTES && name.isNotEmpty()) name = name.dropLast(1)
        return head + name
    }

    fun decode(serviceName: String): Endpoint? {
        val parts = serviceName.split("|", limit = 6)
        if (parts.size < 6 || parts[0] != PREFIX) return null
        val port = parts[4].toIntOrNull() ?: return null
        return Endpoint(GameAd(parts[1], parts[5], parts[2] == "1"), parts[3], port)
    }
}

/** Meldet ein Spiel im lokalen Netz an (NSD auf Android, Bonjour auf iOS). */
expect class LanAdvertiser() {
    fun start(serviceName: String, port: Int)
    fun stop()
}

/** Sucht Spiele im lokalen Netz; liefert Dienstnamen. Rückrufe auf dem Main-Thread. */
expect class LanBrowser() {
    fun start(onFound: (String) -> Unit, onLost: (String) -> Unit, onError: (String) -> Unit)
    fun stop()
}

/** Eine TCP-Verbindung mit Nachrichtenrahmen; Senden über eine Warteschlange, damit nichts durcheinandergerät. */
internal class LanConnection(
    private val socket: Socket,
    private val scope: CoroutineScope,
    private val onMessage: (Message) -> Unit,
    private val onClosed: () -> Unit,
) {
    private val outbox = Channel<ByteArray>(Channel.UNLIMITED)
    private var job: Job? = null

    fun start() {
        val read = socket.openReadChannel()
        val write = socket.openWriteChannel(autoFlush = false)
        job = scope.launch {
            launch(Dispatchers.IO) {
                try {
                    for (bytes in outbox) {
                        write.writeInt(bytes.size)
                        write.writeFully(bytes)
                        write.flush()
                    }
                } catch (_: Throwable) {
                    // Verbindung weg: Lesen bemerkt das ebenfalls
                }
            }
            try {
                while (true) {
                    val text = withContext(Dispatchers.IO) { readFrame(read) } ?: break
                    runCatching { Message.decode(text) }.getOrNull()?.let(onMessage)
                }
            } catch (_: Throwable) {
            }
            close()
            onClosed()
        }
    }

    private suspend fun readFrame(read: ByteReadChannel): String? {
        val size = read.readInt()
        if (size <= 0 || size > LanConfig.MAX_FRAME) return null
        val bytes = ByteArray(size)
        read.readFully(bytes)
        return bytes.decodeToString()
    }

    fun send(message: Message) {
        outbox.trySend(Message.encode(message).encodeToByteArray())
    }

    fun close() {
        outbox.close()
        runCatching { socket.close() }
    }
}

/** Host im lokalen Netz. */
class LanHost(private val listener: HostTransport.Listener) : HostTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val selector = SelectorManager(Dispatchers.IO)
    private val advertiser = LanAdvertiser()
    private val connections = LinkedHashMap<String, LanConnection>()
    private var server: ServerSocket? = null
    private var counter = 0
    private var acceptJob: Job? = null

    override val peers: Set<String> get() = connections.keys

    private suspend fun ensureServer(): ServerSocket? {
        server?.let { return it }
        val bound = PORTS.firstNotNullOfOrNull { port ->
            runCatching { aSocket(selector).tcp().bind("0.0.0.0", port) }.getOrNull()
        } ?: return null
        server = bound
        acceptJob = scope.launch {
            while (true) {
                val socket = runCatching { bound.accept() }.getOrNull() ?: break
                val id = "lan:" + counter++
                val connection = LanConnection(
                    socket, scope,
                    onMessage = { listener.onPeerMessage(id, it) },
                    onClosed = {
                        if (connections.remove(id) != null) listener.onPeerDisconnected(id)
                    },
                )
                connections[id] = connection
                connection.start()
                listener.onPeerConnected(id)
            }
        }
        return bound
    }

    override fun startAdvertising(ad: GameAd) {
        scope.launch {
            val ip = localIpv4() ?: return@launch // ohne WLAN nur Nearby (Android)
            val socket = ensureServer() ?: return@launch listener.onError("WLAN-Spiel konnte nicht angeboten werden")
            val port = (socket.localAddress as? InetSocketAddress)?.port ?: return@launch
            advertiser.stop()
            advertiser.start(LanConfig.encode(ad, ip, port), port)
        }
    }

    override fun stopAdvertising() = advertiser.stop()

    override fun send(peerId: String, message: Message) {
        connections[peerId]?.send(message)
    }

    override fun broadcast(message: Message) = connections.values.forEach { it.send(message) }

    override fun disconnect(peerId: String) {
        connections.remove(peerId)?.close()
    }

    override fun stop() {
        advertiser.stop()
        connections.values.forEach { it.close() }
        connections.clear()
        runCatching { server?.close() }
        server = null
        scope.cancel()
        selector.close()
    }

    private companion object {
        val PORTS = listOf(47_321, 47_322, 47_323, 0)
    }
}

/** Mitspieler im lokalen Netz. */
class LanGuest(private val listener: GuestTransport.Listener) : GuestTransport {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val selector = SelectorManager(Dispatchers.IO)
    private val browser = LanBrowser()
    private val endpoints = HashMap<String, LanConfig.Endpoint>()
    private val _games = MutableStateFlow<List<FoundGame>>(emptyList())
    override val games: StateFlow<List<FoundGame>> = _games
    private var connection: LanConnection? = null
    private var discovering = false

    override fun startDiscovery(onError: (String) -> Unit) {
        if (discovering) return
        discovering = true
        browser.start(
            onFound = { name ->
                val endpoint = LanConfig.decode(name) ?: return@start
                val id = "lan:$name"
                endpoints[id] = endpoint
                _games.update { list -> list.filterNot { it.id == id } + FoundGame(id, endpoint.ad) }
            },
            onLost = { name ->
                val id = "lan:$name"
                endpoints.remove(id)
                _games.update { list -> list.filterNot { it.id == id } }
            },
            onError = { discovering = false; onError(it) },
        )
    }

    override fun stopDiscovery() {
        if (discovering) browser.stop()
        discovering = false
    }

    override fun connect(game: FoundGame, myName: String) {
        val endpoint = endpoints[game.id] ?: return listener.onDisconnected("Spiel nicht mehr gefunden")
        stopDiscovery()
        scope.launch {
            val socket = try {
                aSocket(selector).tcp().connect(endpoint.host, endpoint.port)
            } catch (e: Throwable) {
                listener.onDisconnected("Verbindung fehlgeschlagen: ${e.message ?: "WLAN prüfen"}")
                return@launch
            }
            val c = LanConnection(
                socket, scope,
                onMessage = listener::onMessage,
                onClosed = { if (connection != null) listener.onDisconnected("Verbindung zum Host verloren") },
            )
            connection = c
            c.start()
            listener.onConnected()
        }
    }

    override fun send(message: Message) {
        connection?.send(message)
    }

    override fun stop() {
        stopDiscovery()
        val c = connection
        connection = null
        c?.close()
        scope.cancel()
        selector.close()
    }
}
