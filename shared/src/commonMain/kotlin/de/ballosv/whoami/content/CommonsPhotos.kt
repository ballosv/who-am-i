package de.ballosv.whoami.content

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.http.encodeURLParameter

/**
 * Sucht auf Wikimedia Commons frei lizenzierte Fotos, die laut strukturierten Daten die
 * Person zeigen („depicts“, P180), samt Urheber und Lizenz.
 */
object CommonsPhotos {
    private const val API = "https://commons.wikimedia.org/w/api.php"

    /** Standard-Thumbnailbreite von Wikimedia (beliebige Breiten werden teils abgelehnt). */
    const val THUMB_WIDTH = 960
    private const val MIN_SIDE = 400

    /** Dateinamen, die auf etwas anderes als ein Porträtfoto hindeuten. */
    private val EXCLUDE = Regex(
        "signatur|signature|autograph|unterschrift|logo|wappen|coat of arms|grave|grab|tomb|statue|denkmal|" +
            "memorial|plaque|gedenktafel|stamp|briefmarke|map|karte|poster|cover|wax|madame tussauds|mural|graffiti",
        RegexOption.IGNORE_CASE,
    )

    private val json = Json { ignoreUnknownKeys = true }

    data class Photo(val file: String, val url: String, val credit: String)

    /** Fotos, die die Person mit der Wikidata-ID [qid] zeigen. */
    suspend fun depicting(qid: String, limit: Int = 40): List<Photo> {
        val query = "action=query&format=json&formatversion=2&generator=search&gsrnamespace=6&gsrlimit=$limit" +
            "&gsrsearch=" + enc("haswbstatement:P180=$qid filetype:bitmap") +
            "&prop=imageinfo&iiprop=url|size|mime|extmetadata&iiurlwidth=$THUMB_WIDTH" +
            "&iiextmetadatafilter=Artist|LicenseShortName"
        return pages(query).mapNotNull { page ->
            val file = page.str("title")
            if (EXCLUDE.containsMatchIn(file)) return@mapNotNull null
            val info = page["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
            val mime = info.str("mime")
            if (mime != "image/jpeg" && mime != "image/png") return@mapNotNull null
            val w = info.int("width")
            val h = info.int("height")
            if (minOf(w, h) < MIN_SIDE) return@mapNotNull null
            val url = info.str("thumburl").ifEmpty { info.str("url") }
            if (url.isEmpty()) return@mapNotNull null
            Photo(file, url, credit(info))
        }
    }

    /** Bildnachweis für eine einzelne Datei (z. B. das Artikelbild der Wikipedia). */
    suspend fun creditFor(fileName: String): String? {
        val title = if (fileName.startsWith("File:") || fileName.startsWith("Datei:")) fileName else "File:$fileName"
        val query = "action=query&format=json&formatversion=2&titles=" + enc(title) +
            "&prop=imageinfo&iiprop=extmetadata&iiextmetadatafilter=Artist|LicenseShortName"
        val info = pages(query).firstOrNull()?.get("imageinfo")?.jsonArray?.firstOrNull()?.jsonObject ?: return null
        return credit(info)
    }

    private fun credit(info: JsonObject): String {
        val meta = info["extmetadata"]?.jsonObject
        fun field(name: String) = meta?.get(name)?.jsonObject?.str("value")?.let(::stripHtml).orEmpty()
        val artist = field("Artist").take(60)
        val license = field("LicenseShortName")
        return listOf(if (artist.isNotEmpty()) "Foto: $artist" else "Foto", license, "Wikimedia Commons")
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
    }

    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()

    private suspend fun pages(query: String): List<JsonObject> {
        val body = Http.get("$API?$query")?.decodeToString() ?: return emptyList()
        val root = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject ?: return emptyList()
        return root["pages"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
    }

    private fun enc(s: String) = s.encodeURLParameter()
    private fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.content ?: ""
    private fun JsonObject.int(key: String) = this[key]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
}
