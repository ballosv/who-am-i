package de.ballosv.whoami.content

import de.ballosv.whoami.core.Celebrity
import de.ballosv.whoami.core.Freshness
import de.ballosv.whoami.core.Question
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lädt Porträts für die Promi-Runden:
 * 1. Wikidata-ID der Person über die deutsche Wikipedia ermitteln,
 * 2. auf Wikimedia Commons Fotos suchen, die die Person zeigen (bevorzugt noch nicht gezeigte),
 * 3. per Gesichtserkennung nur Einzelporträts nehmen und auf das Gesicht zuschneiden,
 * 4. notfalls das Artikelbild der Wikipedia verwenden.
 */
object CelebrityImages {
    private const val WIKI_API = "https://de.wikipedia.org/w/api.php"
    private const val MAX_TRIES_PER_PERSON = 4
    private const val PARALLEL = 4
    private val json = Json { ignoreUnknownKeys = true }

    private data class Article(val qid: String?, val thumbUrl: String?, val imageName: String?)
    private data class Portrait(val jpeg: ByteArray, val credit: String, val file: String)

    /**
     * Wählt [count] Personen aus [pool] (bereits nach Kategorien gefiltert), legt die Bilder in
     * [store] ab und liefert die Fragen. Nie oder lange nicht gezeigte Personen haben Vorrang
     * ([history]), ebenso pro Person noch nicht gezeigte Fotos ([photos]).
     */
    suspend fun loadQuestions(
        pool: List<Celebrity>,
        count: Int,
        store: ImageStore,
        history: CelebrityHistory,
        photos: PhotoHistory,
        progress: (found: Int, total: Int) -> Unit,
    ): List<Question> = withContext(Dispatchers.IO) {
        if (pool.isEmpty()) throw IOException("Zu den gewählten Kategorien gibt es keine Personen.")
        // Bekanntere Personen kommen häufiger dran, aber jede irgendwann.
        val candidates = Freshness.order(pool, history.recent(), weight = { it.popularity.toDouble().coerceAtLeast(1.0) }) { it.wikiTitle }
            .toMutableList()
        val result = mutableListOf<Question>()
        val used = mutableListOf<String>()
        // Ohne Bild übersprungene Personen ebenfalls merken, damit sie nicht jedes Mal zuerst probiert werden.
        val skipped = mutableListOf<String>()
        var lastError: Exception? = null
        val limit = Semaphore(PARALLEL)
        // Fortschritt laufend melden, sobald ein Foto gefunden ist (nicht erst am Ende einer Gruppe)
        val found = AtomicInteger(0)
        progress(0, count)

        while (result.size < count && candidates.isNotEmpty()) {
            val missing = count - result.size
            val batch = List(minOf(candidates.size, maxOf(4, missing + missing / 2 + 1))) { candidates.removeAt(0) }
            val articles = try {
                articles(batch.map { it.wikiTitle })
            } catch (e: IOException) {
                lastError = e
                break
            }
            val portraits = coroutineScope {
                batch.map { celebrity ->
                    async {
                        limit.withPermit {
                            celebrity to try {
                                portraitFor(celebrity, articles[celebrity.wikiTitle], photos)?.also {
                                    progress(minOf(found.incrementAndGet(), count), count)
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                // Ein fehlerhaftes Foto oder eine Netzstörung betrifft nur diese Person.
                                lastError = e
                                null
                            }
                        }
                    }
                }.awaitAll()
            }
            for ((celebrity, portrait) in portraits) {
                if (result.size >= count) break
                if (portrait == null) {
                    skipped += celebrity.wikiTitle
                    continue
                }
                val id = "c" + result.size + "_" + celebrity.wikiTitle.hashCode().toUInt().toString(16)
                store.put(id, portrait.jpeg)
                result += Question(id, celebrity.firstName, celebrity.lastName, credit = portrait.credit)
                used += celebrity.wikiTitle
                photos.record(celebrity.wikiTitle, portrait.file)
            }
        }
        if (result.isEmpty()) {
            throw IOException("Promi-Bilder konnten nicht geladen werden. Besteht eine Internetverbindung?", lastError)
        }
        found.set(result.size)
        history.record(skipped + used)
        result.shuffled()
    }

    private fun portraitFor(celebrity: Celebrity, article: Article?, photos: PhotoHistory): Portrait? {
        val qid = celebrity.qid ?: article?.qid
        if (qid != null) {
            val seen = photos.shown(celebrity.wikiTitle)
            val candidates = runCatching { CommonsPhotos.depicting(qid) }.getOrDefault(emptyList())
                .shuffled()
                .sortedBy { it.file in seen }
            for (photo in candidates.take(MAX_TRIES_PER_PERSON)) {
                val bitmap = Http.get(photo.url)?.let(Images::decodeSampled) ?: continue
                when (val face = FaceCropper.portrait(bitmap)) {
                    is FaceCropper.Result.Portrait -> return Portrait(face.jpeg, photo.credit, photo.file)
                    FaceCropper.Result.NoSingleFace -> continue
                    // Ohne Gesichtserkennung (Modell lädt noch) das Foto wie früher zuschneiden
                    FaceCropper.Result.Unavailable -> return Portrait(Images.toSquareJpeg(bitmap), photo.credit, photo.file)
                }
            }
        }
        // Rückfall: Artikelbild der Wikipedia (auch Gemälde, daher notfalls ohne Gesichtserkennung zuschneiden)
        val thumb = article?.thumbUrl ?: return null
        val bitmap = Http.get(thumb)?.let(Images::decodeSampled) ?: return null
        val jpeg = (FaceCropper.portrait(bitmap) as? FaceCropper.Result.Portrait)?.jpeg ?: Images.toSquareJpeg(bitmap)
        val credit = article.imageName?.let { runCatching { CommonsPhotos.creditFor(it) }.getOrNull() } ?: "Bild: Wikipedia"
        return Portrait(jpeg, credit, article.imageName ?: thumb)
    }

    /** Artikeltitel -> Wikidata-ID und Artikelbild (Normalisierung und Weiterleitungen aufgelöst). */
    private fun articles(titles: List<String>): Map<String, Article> {
        val query = "action=query&format=json&formatversion=2&redirects=1&prop=pageimages|pageprops" +
            "&ppprop=wikibase_item&piprop=thumbnail|name&pithumbsize=${CommonsPhotos.THUMB_WIDTH}&titles=" +
            URLEncoder.encode(titles.joinToString("|"), "UTF-8")
        val body = Http.get("$WIKI_API?$query")?.toString(Charsets.UTF_8) ?: throw IOException("Wikipedia nicht erreichbar")
        val root = json.parseToJsonElement(body).jsonObject["query"]?.jsonObject ?: return emptyMap()

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

        val pages = HashMap<String, Article>()
        root["pages"]?.jsonArray?.forEach {
            val page = it.jsonObject
            val qid = page["pageprops"]?.jsonObject?.str("wikibase_item")?.ifEmpty { null }
            val thumb = page["thumbnail"]?.jsonObject?.str("source")?.ifEmpty { null }
            val name = page.str("pageimage").ifEmpty { null }
            if (qid != null || thumb != null) pages[page.str("title")] = Article(qid, thumb, name)
        }
        return titles.mapNotNull { t -> pages[resolve(t)]?.let { t to it } }.toMap()
    }

    private fun JsonObject.str(key: String): String = this[key]?.jsonPrimitive?.content ?: ""
}
