package de.ballosv.whoami.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import de.ballosv.whoami.core.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "WhoAmI.Nearby"
private const val PREFIX = "nb:"

/** Gemeinsame Konstanten und Helfer für Nearby Connections (Bluetooth/WLAN direkt zwischen Geräten). */
object NearbyConfig {
    const val SERVICE_ID = "de.ballosv.whoami.game"
    val STRATEGY: Strategy = Strategy.P2P_STAR
    private const val PREFIX = "WAI1"

    fun encodeAd(ad: GameAd): String =
        listOf(PREFIX, ad.code, if (ad.inviting) "1" else "0", ad.hostName.replace("|", " ").take(24)).joinToString("|")

    fun decodeAd(name: String): GameAd? {
        val parts = name.split("|", limit = 4)
        if (parts.size < 4 || parts[0] != PREFIX) return null
        return GameAd(parts[1], parts[3], parts[2] == "1")
    }

    fun requiredPermissions(): Array<String> = buildList {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }.toTypedArray()

    fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun client(context: Context): ConnectionsClient = Nearby.getConnectionsClient(context.applicationContext)
}

private fun Message.toPayload(): Payload = Payload.fromBytes(Message.encode(this).toByteArray(Charsets.UTF_8))

private fun Payload.toMessage(): Message? = asBytes()?.let { bytes ->
    runCatching { Message.decode(bytes.toString(Charsets.UTF_8)) }
        .onFailure { Log.w(TAG, "Unlesbare Nachricht", it) }
        .getOrNull()
}

/**
 * Host-Seite über Nearby Connections (Bluetooth/WLAN direkt zwischen Android-Geräten).
 * Peer-IDs tragen das Präfix „nb:“, damit sie sich nicht mit denen anderer Verbindungsarten überschneiden.
 */
class NearbyHost(context: Context, private val listener: HostTransport.Listener) : HostTransport {
    private val client = NearbyConfig.client(context)
    private val connected = LinkedHashSet<String>()
    private var advertising = false

    override val peers: Set<String> get() = connected.mapTo(LinkedHashSet()) { PREFIX + it }

    private fun endpoint(peerId: String) = peerId.removePrefix(PREFIX)

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.toMessage()?.let { listener.onPeerMessage(PREFIX + endpointId, it) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                connected += endpointId
                listener.onPeerConnected(PREFIX + endpointId)
            }
        }

        override fun onDisconnected(endpointId: String) {
            connected -= endpointId
            listener.onPeerDisconnected(PREFIX + endpointId)
        }
    }

    override fun startAdvertising(ad: GameAd) {
        if (advertising) client.stopAdvertising()
        advertising = true
        client.startAdvertising(
            NearbyConfig.encodeAd(ad),
            NearbyConfig.SERVICE_ID,
            lifecycle,
            AdvertisingOptions.Builder().setStrategy(NearbyConfig.STRATEGY).build(),
        ).addOnFailureListener {
            advertising = false
            Log.w(TAG, "Advertising fehlgeschlagen", it)
            listener.onError("Spiel konnte nicht angeboten werden: ${it.message}")
        }
    }

    override fun stopAdvertising() {
        if (advertising) client.stopAdvertising()
        advertising = false
    }

    override fun send(peerId: String, message: Message) {
        client.sendPayload(endpoint(peerId), message.toPayload())
    }

    override fun broadcast(message: Message) {
        if (connected.isEmpty()) return
        client.sendPayload(connected.toList(), message.toPayload())
    }

    override fun disconnect(peerId: String) {
        client.disconnectFromEndpoint(endpoint(peerId))
        connected -= endpoint(peerId)
    }

    override fun stop() {
        stopAdvertising()
        client.stopAllEndpoints()
        connected.clear()
    }
}

/** Mitspieler-Seite über Nearby Connections: sucht Spiele in der Nähe und verbindet sich mit einem Host. */
class NearbyGuest(context: Context, private val listener: GuestTransport.Listener) : GuestTransport {
    private val client = NearbyConfig.client(context)
    private val _games = MutableStateFlow<List<FoundGame>>(emptyList())
    override val games: StateFlow<List<FoundGame>> = _games

    private var hostEndpoint: String? = null
    private var discovering = false

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            payload.toMessage()?.let(listener::onMessage)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                hostEndpoint = endpointId
                stopDiscovery()
                listener.onConnected()
            } else {
                hostEndpoint = null
                listener.onDisconnected("Verbindung abgelehnt (${resolution.status.statusCode})")
            }
        }

        override fun onDisconnected(endpointId: String) {
            if (endpointId == hostEndpoint) {
                hostEndpoint = null
                listener.onDisconnected("Verbindung zum Host verloren")
            }
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val ad = NearbyConfig.decodeAd(info.endpointName) ?: return
            _games.update { list -> list.filterNot { it.id == PREFIX + endpointId } + FoundGame(PREFIX + endpointId, ad) }
        }

        override fun onEndpointLost(endpointId: String) {
            _games.update { list -> list.filterNot { it.id == PREFIX + endpointId } }
        }
    }

    override fun startDiscovery(onError: (String) -> Unit) {
        if (discovering) return
        discovering = true
        client.startDiscovery(
            NearbyConfig.SERVICE_ID,
            discovery,
            DiscoveryOptions.Builder().setStrategy(NearbyConfig.STRATEGY).build(),
        ).addOnFailureListener {
            discovering = false
            Log.w(TAG, "Suche fehlgeschlagen", it)
            onError("Suche nach Spielen fehlgeschlagen: ${it.message}")
        }
    }

    override fun stopDiscovery() {
        if (discovering) client.stopDiscovery()
        discovering = false
    }

    override fun connect(game: FoundGame, myName: String) {
        client.requestConnection(myName.ifBlank { "Spieler" }, game.id.removePrefix(PREFIX), lifecycle)
            .addOnFailureListener {
                Log.w(TAG, "Verbindung fehlgeschlagen", it)
                listener.onDisconnected("Verbindung fehlgeschlagen: ${it.message}")
            }
    }

    override fun send(message: Message) {
        val endpoint = hostEndpoint ?: return
        client.sendPayload(endpoint, message.toPayload())
    }

    override fun stop() {
        stopDiscovery()
        client.stopAllEndpoints()
        hostEndpoint = null
    }
}
