package de.ballosv.whoami.content

import java.net.HttpURLConnection
import java.net.URL

/** Einfache HTTP-Abrufe mit der von Wikimedia verlangten User-Agent-Kennung. */
object Http {
    private const val USER_AGENT = "WhoAmI-Android/0.2 (https://github.com/ballosv/who-am-i)"

    fun get(url: String): ByteArray? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 8_000
        conn.readTimeout = 15_000
        return try {
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}
