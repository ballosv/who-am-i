package de.ballosv.whoami.core

/**
 * Alle Personen: die von Hand gepflegte Liste plus die aus Wikidata erzeugte
 * (`celebrities_wikidata.tsv`, siehe tools/generate_celebrities.py).
 */
val CELEBRITIES: List<Celebrity> by lazy { CelebrityData.merge(CURATED_CELEBRITIES, CelebrityData.loadGenerated()) }

object CelebrityData {
    /**
     * Stufengrenzen als Anteil der erzeugten Liste (nach Bekanntheit sortiert):
     * Stufe 1 extrem bekannt, 2 sehr bekannt, 3 bekannt, darüber hinaus zu unbekannt (Stufe 4).
     */
    private const val TIER1_SHARE = 0.08
    private const val TIER2_SHARE = 0.2
    private const val TIER3_SHARE = 0.4

    /** Bekanntheit (Seitenaufrufe/Monat) für handgepflegte Personen, die in der erzeugten Liste fehlen. */
    private const val CURATED_DEFAULT_POPULARITY = 3_000

    /**
     * Gewicht für die Auswahl je Bewertung (5 = kennt praktisch jeder, 3 = bekannt): Bekanntere kommen
     * öfter dran. Bewusst unabhängig von aktuellen Seitenaufrufen, damit Nachrichtenwellen nicht zählen.
     */
    private val RATING_POPULARITY = mapOf(5 to 10_000, 4 to 3_000, 3 to 1_000)

    /** Stufe aus der Bewertung: 5 -> extrem bekannt (1), 4 -> sehr bekannt (2), 3 -> bekannt (3), darunter 4. */
    fun tierForRating(rating: Int): Int = (6 - rating).coerceIn(1, 4)

    fun popularityForRating(rating: Int): Int = RATING_POPULARITY[rating] ?: 300

    /** Stufe für handgepflegte Personen, die in der erzeugten Liste fehlen. */
    private const val CURATED_DEFAULT_TIER = 2

    private val AREA_CODES = mapOf(
        "A" to Area.ACTING, "M" to Area.MUSIC, "T" to Area.TV, "S" to Area.SPORT, "P" to Area.POLITICS,
        "W" to Area.SCIENCE, "K" to Area.ARTS, "F" to Area.FASHION, "H" to Area.HISTORY,
    )
    private val DECADE_CODES = mapOf(
        "50" to Decade.D1950, "60" to Decade.D1960, "70" to Decade.D1970, "80" to Decade.D1980,
        "90" to Decade.D1990, "00" to Decade.D2000, "10" to Decade.D2010, "20" to Decade.D2020,
    )

    /** Die erzeugte Liste (core/data/celebrities_wikidata.tsv), beim Bauen als Quelltext eingebettet. */
    fun loadGenerated(): List<Celebrity> = parse(CelebrityTsv.text.lines())

    /**
     * Erwartet die TSV-Zeilen inkl. Kopfzeile, sortiert nach Bekanntheit (bekannteste zuerst).
     * Spalten: qid, title, first, last, areas, decades, origin, links[, views[, rating]]. Die Bewertung
     * (1–5, wie allgemein bekannt die Person in Deutschland ist) bestimmt Stufe und Gewicht; ältere Listen
     * ohne sie nutzen den Rang nach Seitenaufrufen bzw. Sprachversionen.
     */
    fun parse(lines: List<String>): List<Celebrity> {
        val rows = lines.drop(1).filter { it.isNotBlank() }
        val n = rows.size
        return rows.mapIndexedNotNull { index, line ->
            val f = line.split('\t')
            if (f.size < 8) return@mapIndexedNotNull null
            val areas = f[4].split(' ').mapNotNull { AREA_CODES[it] }.toSet()
            val decades = f[5].split(' ').mapNotNull { DECADE_CODES[it] }.toSet()
            if (areas.isEmpty() || decades.isEmpty() || f[3].isBlank()) return@mapIndexedNotNull null
            val rating = f.getOrNull(9)?.toIntOrNull()
            Celebrity(
                firstName = f[2],
                lastName = f[3],
                wikiTitle = f[1],
                areas = areas,
                decades = decades,
                origin = if (f[6] == "D") Origin.GERMAN else Origin.INTERNATIONAL,
                qid = f[0],
                popularity = rating?.let(::popularityForRating) ?: f.getOrNull(8)?.toIntOrNull() ?: f[7].toIntOrNull() ?: 0,
                tier = if (rating != null) tierForRating(rating) else when {
                    index < n * TIER1_SHARE -> 1
                    index < n * TIER2_SHARE -> 2
                    index < n * TIER3_SHARE -> 3
                    else -> 4
                },
            )
        }
    }

    /**
     * Handgepflegte Einträge haben Vorrang (genauere Kategorien); von der erzeugten Liste übernehmen
     * sie Wikidata-ID, Bekanntheit und Stufe – höchstens aber Stufe 3, denn von Hand gewählte Personen
     * sind nie „zu unbekannt“. Fehlt eine Person dort, gilt ihre Bewertung aus [curatedRatings].
     * Doppelte Personen (gleicher Artikel oder Name) entfallen.
     */
    fun merge(
        curated: List<Celebrity>,
        generated: List<Celebrity>,
        curatedRatings: Map<String, Int> = CURATED_RATINGS,
    ): List<Celebrity> {
        val byTitle = generated.associateBy { it.wikiTitle }
        val byName = generated.associateBy { it.displayName.lowercase() }
        val matched = HashSet<String>()
        val enriched = curated.map { c ->
            val g = byTitle[c.wikiTitle] ?: byName[c.displayName.lowercase()]
            if (g != null) matched += g.wikiTitle
            val rating = curatedRatings[c.wikiTitle]
            c.copy(
                qid = g?.qid,
                popularity = g?.popularity ?: rating?.let(::popularityForRating) ?: CURATED_DEFAULT_POPULARITY,
                tier = (g?.tier ?: rating?.let(::tierForRating) ?: CURATED_DEFAULT_TIER).coerceAtMost(3),
            )
        }
        val curatedNames = curated.map { it.displayName.lowercase() }.toSet()
        val curatedTitles = curated.map { it.wikiTitle }.toSet()
        return enriched + generated.filter {
            it.wikiTitle !in matched && it.wikiTitle !in curatedTitles && it.displayName.lowercase() !in curatedNames
        }
    }
}
