package de.ballosv.whoami.content

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Einfache HTTP-Abrufe mit der von Wikimedia verlangten User-Agent-Kennung. */
object Http {
    private const val USER_AGENT = "WhoAmI-Android/0.4 (https://github.com/ballosv/who-am-i)"

    /** Letzter Fehlschlag („HTTP 403 thumb.wikimedia.org“), damit die Fehlermeldung die Ursache nennen kann. */
    @Volatile
    var lastFailure: String? = null

    /** Wie [fetch], aber bei Überlastung (HTTP 429/503) nach kurzer Pause ein zweites Mal. */
    fun get(url: String): ByteArray? {
        val first = fetch(url)
        if (first != null || lastFailure?.let { it.startsWith("HTTP 429") || it.startsWith("HTTP 503") } != true) return first
        Thread.sleep(1_000)
        return fetch(url)
    }

    private fun fetch(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        return try {
            val code = conn.responseCode
            if (code != 200) {
                lastFailure = "HTTP $code ${conn.url.host}"
                null
            } else {
                conn.inputStream.use { it.readBytes() }
            }
        } catch (e: IOException) {
            lastFailure = "${e.javaClass.simpleName} ${URL(url).host}"
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Lädt ein Bild. Wikimedia liefert Vorschaubilder inzwischen über thumb.wikimedia.org aus;
     * klappt das nicht, wird derselbe Pfad über den bisherigen Host upload.wikimedia.org versucht.
     */
    fun image(url: String): ByteArray? {
        val first = runCatching { get(url) }
        first.getOrNull()?.let { return it }
        val fallback = url.replaceFirst("://thumb.wikimedia.org/", "://upload.wikimedia.org/")
        if (fallback != url) runCatching { get(fallback) }.getOrNull()?.let { return it }
        first.exceptionOrNull()?.let { throw it }
        return null
    }
}
