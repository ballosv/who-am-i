package de.ballosv.whoami.content

import de.ballosv.whoami.core.Celebrity
import de.ballosv.whoami.core.Freshness
import de.ballosv.whoami.core.Question
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Lädt Porträts von Prominenten über die Wikipedia-API (Artikelbild der deutschen Wikipedia). */
object WikipediaImages {
    private const val API = "https://de.wikipedia.org/w/api.php"
    private const val USER_AGENT = "WhoAmI-Android/0.1 (https://github.com/ballosv/who-am-i)"
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Wählt [count] Personen mit Bild aus [pool] aus (bereits nach Kategorien gefiltert), legt die Bilder in [store] ab und liefert die
     * passenden Fragen. Personen, die noch nie oder am längsten nicht dran waren, haben
     * Vorrang (siehe [CelebrityHistory]). [progress] erhält Werte von 0 bis 1.
     */
    suspend fun loadQuestions(
        pool: List<Celebrity>,
        count: Int,
        store: ImageStore,
        history: CelebrityHistory,
        progress: (Float) -> Unit,
    ): List<Question> =
        withContext(Dispatchers.IO) {
            if (pool.isEmpty()) throw IOException("Zu den gewählten Kategorien gibt es keine Personen.")
            val candidates = Freshness.order(pool, history.recent()) { it.wikiTitle }.toMutableList()
            val used = mutableListOf<String>()
            // Ohne Bild übersprungene Personen ebenfalls merken, damit sie nicht jedes Mal zuerst probiert werden.
            val skipped = mutableListOf<String>()
            val result = mutableListOf<Question>()
            var lastError: Exception? = null
            while (result.size < count && candidates.isNotEmpty()) {
                val batch = List(minOf(candidates.size, maxOf(8, (count - result.size) * 2))) { candidates.removeAt(0) }
                val urls = try {
                    thumbnailUrls(batch.map { it.wikiTitle })
                } catch (e: IOException) {
                    lastError = e
                    break
                }
                for (celebrity in batch) {
                    if (result.size >= count) break
                    val url = urls[celebrity.wikiTitle]
                    if (url == null) {
                        skipped += celebrity.wikiTitle
                        continue
                    }
                    val bytes = try {
                        download(url)?.let { Images.fromBytes(it) }
                    } catch (e: IOException) {
                        lastError = e
                        null
                    } ?: continue
                    val id = "c" + result.size + "_" + celebrity.wikiTitle.hashCode().toUInt().toString(16)
                    store.put(id, bytes)
                    result += Question(id, celebrity.firstName, celebrity.lastName)
                    used += celebrity.wikiTitle
                    progress(result.size.toFloat() / count)
                }
            }
            if (result.isEmpty()) {
                throw IOException("Promi-Bilder konnten nicht geladen werden. Besteht eine Internetverbindung?", lastError)
            }
            history.record(skipped + used)
            result.shuffled()
        }

    /** Artikeltitel -> URL eines etwa 900 px großen Vorschaubilds. */
    private fun thumbnailUrls(titles: List<String>): Map<String, String> {
        val query = "action=query&format=json&formatversion=2&redirects=1&prop=pageimages&piprop=thumbnail" +
            "&pithumbsize=${Images.TARGET_SIZE}&titles=" + URLEncoder.encode(titles.joinToString("|"), "UTF-8")
        val body = download("$API?$query")?.toString(Charsets.UTF_8) ?: return emptyMap()
        val root = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject ?: return emptyMap()

        // Angefragter Titel -> tatsächlicher Seitentitel (Normalisierung und Weiterleitungen auflösen)
        val mapping = HashMap<String, String>()
        fun collect(key: String) = root[key]?.jsonArray?.forEach {
            val o = it.jsonObject
            mapping[o.str("from")] = o.str("to")
        }
        collect("normalized")
        collect("redirects")
        fun resolve(title: String): String {
            var t = title
            repeat(3) { t = mapping[t] ?: return t }
            return t
        }

        val thumbs = HashMap<String, String>()
        root["pages"]?.jsonArray?.forEach {
            val page = it.jsonObject
            val src = page["thumbnail"]?.jsonObject?.str("source") ?: return@forEach
            if (src.isNotEmpty()) thumbs[page.str("title")] = src
        }
        return titles.mapNotNull { t -> thumbs[resolve(t)]?.let { t to it } }.toMap()
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""

    private fun download(url: String): ByteArray? {
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
