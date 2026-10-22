package de.ballosv.whoami.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.ballosv.whoami.platform.AndroidPlatform

/** Android: Nearby (auch ohne WLAN, nur zwischen Android-Geräten) und WLAN (auch mit iPhones). */
actual fun createHostTransports(listener: HostTransport.Listener): List<HostTransport> =
    listOf(NearbyHost(AndroidPlatform.context, listener), LanHost(listener))

actual fun createGuestTransports(listener: GuestTransport.Listener): List<GuestTransport> =
    listOf(NearbyGuest(AndroidPlatform.context, listener), LanGuest(listener))

private const val TAG = "WhoAmI.Lan"
private val main = Handler(Looper.getMainLooper())

private fun nsd(): NsdManager = AndroidPlatform.context.getSystemService(Context.NSD_SERVICE) as NsdManager

actual class LanAdvertiser actual constructor() {
    private var registration: NsdManager.RegistrationListener? = null

    actual fun start(serviceName: String, port: Int) {
        stop()
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            serviceType = LanConfig.SERVICE_TYPE
            this.port = port
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.w(TAG, "Anmelden im WLAN fehlgeschlagen: $errorCode")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
        }
        registration = listener
        runCatching { nsd().registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "Anmelden im WLAN fehlgeschlagen", it) }
    }

    actual fun stop() {
        val listener = registration ?: return
        registration = null
        runCatching { nsd().unregisterService(listener) }
    }
}

actual class LanBrowser actual constructor() {
    private var discovery: NsdManager.DiscoveryListener? = null

    actual fun start(onFound: (String) -> Unit, onLost: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { onFound(info.serviceName) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { onLost(info.serviceName) }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Suche im WLAN fehlgeschlagen: $errorCode")
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }
        discovery = listener
        runCatching { nsd().discoverServices(LanConfig.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "Suche im WLAN fehlgeschlagen", it) }
    }

    actual fun stop() {
        val listener = discovery ?: return
        discovery = null
        runCatching { nsd().stopServiceDiscovery(listener) }
    }
}
