package de.ballosv.whoami.core

/**
 * Alle Personen: die von Hand gepflegte Liste plus die aus Wikidata erzeugte
 * (`celebrities_wikidata.tsv`, siehe tools/generate_celebrities.py).
 */
val CELEBRITIES: List<Celebrity> by lazy { CelebrityData.merge(CURATED_CELEBRITIES, CelebrityData.loadGenerated()) }

object CelebrityData {
    private const val RESOURCE = "celebrities_wikidata.tsv"

    /** Anteil der erzeugten Personen in Stufe 1 bzw. bis Stufe 2 (nach Bekanntheit sortiert). */
    private const val TIER1_SHARE = 0.2
    private const val TIER2_SHARE = 0.55

    /** Bekanntheit für handgepflegte Personen, die in der erzeugten Liste fehlen. */
    private const val CURATED_DEFAULT_POPULARITY = 120

    private val AREA_CODES = mapOf(
        "A" to Area.ACTING, "M" to Area.MUSIC, "T" to Area.TV, "S" to Area.SPORT, "P" to Area.POLITICS,
        "W" to Area.SCIENCE, "K" to Area.ARTS, "F" to Area.FASHION, "H" to Area.HISTORY,
    )
    private val DECADE_CODES = mapOf(
        "50" to Decade.D1950, "60" to Decade.D1960, "70" to Decade.D1970, "80" to Decade.D1980,
        "90" to Decade.D1990, "00" to Decade.D2000, "10" to Decade.D2010, "20" to Decade.D2020,
    )

    fun loadGenerated(): List<Celebrity> {
        val stream = CelebrityData::class.java.getResourceAsStream(RESOURCE) ?: return emptyList()
        val lines = stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        return parse(lines)
    }

    /** Erwartet die TSV-Zeilen inkl. Kopfzeile, sortiert nach Bekanntheit (bekannteste zuerst). */
    fun parse(lines: List<String>): List<Celebrity> {
        val rows = lines.drop(1).filter { it.isNotBlank() }
        val n = rows.size
        return rows.mapIndexedNotNull { index, line ->
            val f = line.split('\t')
            if (f.size < 8) return@mapIndexedNotNull null
            val areas = f[4].split(' ').mapNotNull { AREA_CODES[it] }.toSet()
            val decades = f[5].split(' ').mapNotNull { DECADE_CODES[it] }.toSet()
            if (areas.isEmpty() || decades.isEmpty() || f[3].isBlank()) return@mapIndexedNotNull null
            Celebrity(
                firstName = f[2],
                lastName = f[3],
                wikiTitle = f[1],
                areas = areas,
                decades = decades,
                origin = if (f[6] == "D") Origin.GERMAN else Origin.INTERNATIONAL,
                qid = f[0],
                popularity = f[7].toIntOrNull() ?: 0,
                tier = when {
                    index < n * TIER1_SHARE -> 1
                    index < n * TIER2_SHARE -> 2
                    else -> 3
                },
            )
        }
    }

    /**
     * Handgepflegte Einträge haben Vorrang (genauere Kategorien, Stufe 1); von der erzeugten Liste
     * übernehmen sie Wikidata-ID und Bekanntheit. Doppelte Personen (gleicher Artikel oder Name) entfallen.
     */
    fun merge(curated: List<Celebrity>, generated: List<Celebrity>): List<Celebrity> {
        val byTitle = generated.associateBy { it.wikiTitle }
        val byName = generated.associateBy { it.displayName.lowercase() }
        val matched = HashSet<String>()
        val enriched = curated.map { c ->
            val g = byTitle[c.wikiTitle] ?: byName[c.displayName.lowercase()]
            if (g != null) matched += g.wikiTitle
            c.copy(qid = g?.qid, popularity = g?.popularity ?: CURATED_DEFAULT_POPULARITY, tier = 1)
        }
        val curatedNames = curated.map { it.displayName.lowercase() }.toSet()
        val curatedTitles = curated.map { it.wikiTitle }.toSet()
        return enriched + generated.filter {
            it.wikiTitle !in matched && it.wikiTitle !in curatedTitles && it.displayName.lowercase() !in curatedNames
        }
    }
}
