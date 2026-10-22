package de.ballosv.whoami.net

import kotlinx.cinterop.ObjCSignatureOverride
import platform.Foundation.NSNetService
import platform.Foundation.NSNetServiceBrowser
import platform.Foundation.NSNetServiceBrowserDelegateProtocol
import platform.darwin.NSObject

/** iOS: Mehrspieler über das lokale WLAN (Nearby Connections gibt es nur auf Android). */
actual fun createHostTransports(listener: HostTransport.Listener): List<HostTransport> = listOf(LanHost(listener))

actual fun createGuestTransports(listener: GuestTransport.Listener): List<GuestTransport> = listOf(LanGuest(listener))

private const val DOMAIN = "local."
private const val TYPE = LanConfig.SERVICE_TYPE + "."

actual class LanAdvertiser actual constructor() {
    private var service: NSNetService? = null

    actual fun start(serviceName: String, port: Int) {
        stop()
        service = NSNetService(domain = DOMAIN, type = TYPE, name = serviceName, port = port).also { it.publish() }
    }

    actual fun stop() {
        service?.stop()
        service = null
    }
}

private class BrowserDelegate(
    private val onFound: (String) -> Unit,
    private val onLost: (String) -> Unit,
    private val onError: (String) -> Unit,
) : NSObject(), NSNetServiceBrowserDelegateProtocol {
    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didFindService: NSNetService, moreComing: Boolean) {
        onFound(didFindService.name)
    }

    @ObjCSignatureOverride
    override fun netServiceBrowser(browser: NSNetServiceBrowser, didRemoveService: NSNetService, moreComing: Boolean) {
        onLost(didRemoveService.name)
    }

    override fun netServiceBrowser(browser: NSNetServiceBrowser, didNotSearch: Map<Any?, *>) {
        onError("Suche im WLAN nicht möglich – ist „Lokales Netzwerk“ für die App erlaubt?")
    }
}

actual class LanBrowser actual constructor() {
    private var browser: NSNetServiceBrowser? = null
    private var delegate: BrowserDelegate? = null

    actual fun start(onFound: (String) -> Unit, onLost: (String) -> Unit, onError: (String) -> Unit) {
        stop()
        val d = BrowserDelegate(onFound, onLost, onError)
        delegate = d
        browser = NSNetServiceBrowser().also {
            it.delegate = d
            it.searchForServicesOfType(TYPE, inDomain = DOMAIN)
        }
    }

    actual fun stop() {
        browser?.stop()
        browser = null
        delegate = null
    }
}
