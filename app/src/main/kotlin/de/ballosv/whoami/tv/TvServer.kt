package de.ballosv.whoami.tv

import android.content.Context
import android.util.Log
import de.ballosv.whoami.content.ImageStore
import de.ballosv.whoami.core.GameSnapshot
import de.ballosv.whoami.core.Message
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Minimaler HTTP-Server für die TV-Ansicht. Der Smart-TV (Browser) öffnet die angezeigte
 * Adresse im selben WLAN; die Seite holt sich regelmäßig den Spielzustand samt Host-Uhrzeit
 * und berechnet die Kacheln genauso wie die Handys.
 */
class TvServer(
    context: Context,
    private val images: ImageStore,
    private val hostNow: () -> Long,
) {
    @Volatile
    var snapshot: GameSnapshot? = null

    private val page: ByteArray = context.assets.open("tv/index.html").use { it.readBytes() }
    private var server: ServerSocket? = null
    private val pool = Executors.newFixedThreadPool(4)

    /** Startet den Server und liefert die Adresse für den TV, oder null ohne WLAN-Adresse. */
    fun start(): String? {
        val ip = localIpv4() ?: return null
        val socket = (PORTS.firstNotNullOfOrNull { port -> runCatching { ServerSocket(port) }.getOrNull() }) ?: return null
        server = socket
        thread(name = "tv-server", isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                pool.execute { handle(client) }
            }
        }
        return "http://$ip:${socket.localPort}"
    }

    fun stop() {
        runCatching { server?.close() }
        pool.shutdownNow()
    }

    private fun handle(socket: Socket) {
        socket.use { handleRequest(it) }
    }

    private fun handleRequest(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            // Header überspringen
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            when {
                path == "/" || path.startsWith("/index") -> respond(out, 200, "text/html; charset=utf-8", page)
                path.startsWith("/state") -> {
                    val s = snapshot
                    val body = buildString {
                        append("{\"now\":").append(hostNow())
                        append(",\"s\":")
                        append(if (s == null) "null" else Message.json.encodeToString(GameSnapshot.serializer(), s))
                        append("}")
                    }
                    respond(out, 200, "application/json; charset=utf-8", body.toByteArray())
                }
                path.startsWith("/image/") -> {
                    val id = path.removePrefix("/image/").substringBefore('?')
                    val bytes = images.bytes(id)
                    if (bytes == null) respond(out, 404, "text/plain", "not found".toByteArray())
                    else respond(out, 200, "image/jpeg", bytes, cache = true)
                }
                else -> respond(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (e: Exception) {
            Log.d("WhoAmI.Tv", "Anfrage fehlgeschlagen", e)
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray, cache: Boolean = false) {
        val status = if (code == 200) "OK" else "Not Found"
        val headers = "HTTP/1.1 $code $status\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: ${if (cache) "max-age=3600" else "no-store"}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        private val PORTS = listOf(8080, 8081, 8888, 0)

        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("ap") || it.name.startsWith("swlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}
