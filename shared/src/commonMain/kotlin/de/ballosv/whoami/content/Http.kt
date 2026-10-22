package de.ballosv.whoami.content

import de.ballosv.whoami.platform.platformName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile

/** Einfache HTTP-Abrufe mit der von Wikimedia verlangten User-Agent-Kennung. */
object Http {
    private val userAgent = "WhoAmI-$platformName/0.7 (https://github.com/ballosv/who-am-i)"

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 20_000
            }
            expectSuccess = false
        }
    }

    /** Letzter Fehlschlag („HTTP 403 thumb.wikimedia.org“), damit die Fehlermeldung die Ursache nennen kann. */
    @Volatile
    var lastFailure: String? = null

    /** Wie [fetch], aber bei Überlastung (HTTP 429/503) nach kurzer Pause ein zweites Mal. */
    suspend fun get(url: String): ByteArray? {
        val first = fetch(url)
        if (first != null || lastFailure?.let { it.startsWith("HTTP 429") || it.startsWith("HTTP 503") } != true) return first
        delay(1_000)
        return fetch(url)
    }

    private suspend fun fetch(url: String): ByteArray? {
        val host = url.substringAfter("://").substringBefore('/')
        return try {
            val response = client.get(url) { header("User-Agent", userAgent) }
            if (response.status != HttpStatusCode.OK) {
                lastFailure = "HTTP ${response.status.value} $host"
                null
            } else {
                response.bodyAsBytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailure = "${e::class.simpleName} $host"
            throw IOException(e.message ?: "Netzwerkfehler", e)
        }
    }

    /**
     * Lädt ein Bild. Wikimedia liefert Vorschaubilder inzwischen über thumb.wikimedia.org aus;
     * klappt das nicht, wird derselbe Pfad über den bisherigen Host upload.wikimedia.org versucht.
     */
    suspend fun image(url: String): ByteArray? {
        val first = runCatching { get(url) }
        first.getOrNull()?.let { return it }
        val fallback = url.replaceFirst("://thumb.wikimedia.org/", "://upload.wikimedia.org/")
        if (fallback != url) runCatching { get(fallback) }.getOrNull()?.let { return it }
        first.exceptionOrNull()?.let { throw it }
        return null
    }
}

/** Netzwerk- bzw. Ladefehler (gemeinsam für Android und iOS). */
class IOException(message: String, cause: Throwable? = null) : Exception(message, cause)
