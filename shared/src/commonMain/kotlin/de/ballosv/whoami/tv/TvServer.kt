package de.ballosv.whoami.tv

import de.ballosv.whoami.content.ImageStore
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.core.Message
import de.ballosv.whoami.platform.localIpv4
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.ServerSocket
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile

/**
 * Minimaler HTTP-Server für die TV-Ansicht. Der Smart-TV (Browser) öffnet die angezeigte
 * Adresse im selben WLAN; die Seite holt sich regelmäßig den Spielzustand samt Host-Uhrzeit
 * und berechnet die Kacheln genauso wie die Handys.
 */
class TvServer(
    private val images: ImageStore,
    private val hostNow: () -> Long,
) {
    @Volatile
    var snapshot: GameSnapshot? = null

    private val page: ByteArray by lazy { TvPage.html.encodeToByteArray() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selector = SelectorManager(Dispatchers.IO)
    private var server: ServerSocket? = null

    /** Startet den Server und liefert die Adresse für den TV, oder null ohne WLAN-Adresse. */
    suspend fun start(): String? {
        val ip = localIpv4() ?: return null
        val socket = PORTS.firstNotNullOfOrNull { port ->
            runCatching { aSocket(selector).tcp().bind("0.0.0.0", port) }.getOrNull()
        } ?: return null
        server = socket
        scope.launch {
            while (true) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch { handle(client) }
            }
        }
        val port = (socket.localAddress as? InetSocketAddress)?.port ?: return null
        return "http://$ip:$port"
    }

    fun stop() {
        runCatching { server?.close() }
        scope.cancel()
        selector.close()
    }

    private suspend fun handle(socket: Socket) {
        try {
            withTimeout(5_000) {
                val read = socket.openReadChannel()
                val write = socket.openWriteChannel(autoFlush = false)
                val requestLine = read.readUTF8Line() ?: return@withTimeout
                // Header überspringen
                while (true) {
                    val line = read.readUTF8Line() ?: break
                    if (line.isEmpty()) break
                }
                val path = requestLine.split(" ").getOrNull(1) ?: "/"
                val (code, type, body, cache) = when {
                    path == "/" || path.startsWith("/index") -> Response(200, "text/html; charset=utf-8", page)
                    path.startsWith("/state") -> {
                        val s = snapshot
                        val json = buildString {
                            append("{\"now\":").append(hostNow())
                            append(",\"s\":")
                            append(if (s == null) "null" else Message.json.encodeToString(GameSnapshot.serializer(), s))
                            append("}")
                        }
                        Response(200, "application/json; charset=utf-8", json.encodeToByteArray())
                    }
                    path.startsWith("/image/") -> {
                        val id = path.removePrefix("/image/").substringBefore('?')
                        val bytes = images.bytes(id)
                        if (bytes == null) Response(404, "text/plain", NOT_FOUND) else Response(200, "image/jpeg", bytes, cache = true)
                    }
                    else -> Response(404, "text/plain", NOT_FOUND)
                }
                val status = if (code == 200) "OK" else "Not Found"
                val headers = "HTTP/1.1 $code $status\r\n" +
                    "Content-Type: $type\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Cache-Control: ${if (cache) "max-age=3600" else "no-store"}\r\n" +
                    "Connection: close\r\n\r\n"
                write.writeFully(headers.encodeToByteArray())
                write.writeFully(body)
                write.flush()
            }
        } catch (_: Throwable) {
            // Abgebrochene Anfragen des Browsers sind normal.
        } finally {
            runCatching { socket.close() }
        }
    }

    private data class Response(val code: Int, val type: String, val body: ByteArray, val cache: Boolean = false)

    private companion object {
        val PORTS = listOf(8080, 8081, 8888, 0)
        val NOT_FOUND = "not found".encodeToByteArray()
    }
}
