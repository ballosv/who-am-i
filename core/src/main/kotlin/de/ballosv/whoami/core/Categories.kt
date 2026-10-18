package de.ballosv.whoami.core

import kotlinx.serialization.Serializable

/** Bereich, in dem eine Person bekannt ist. */
@Serializable
enum class Area(val label: String) {
    ACTING("Film & Schauspiel"),
    MUSIC("Musik"),
    TV("TV & Comedy"),
    SPORT("Sport"),
    POLITICS("Politik & Gesellschaft"),
    SCIENCE("Wissenschaft & Wirtschaft"),
    ARTS("Kunst & Literatur"),
    FASHION("Mode & Models"),
    HISTORY("Geschichte"),
}

/** Jahrzehnt, in dem eine Person besonders bekannt war. */
@Serializable
enum class Decade(val label: String) {
    D1950("50er & früher"),
    D1960("60er"),
    D1970("70er"),
    D1980("80er"),
    D1990("90er"),
    D2000("2000er"),
    D2010("2010er"),
    D2020("2020er"),
}

@Serializable
enum class Origin(val label: String) {
    GERMAN("Deutsche Promis"),
    INTERNATIONAL("International"),
}

/** Wie bekannt die Personen mindestens sein sollen (Stufe 1 = extrem bekannt). */
@Serializable
enum class FameLevel(val label: String, val maxTier: Int) {
    EXTREMELY_FAMOUS("Extrem bekannt", 1),
    VERY_FAMOUS("Sehr bekannt", 2),
    FAMOUS("Bekannt", 3),
}

/** Prominente Person; [wikiTitle] ist der Artikel in der deutschsprachigen Wikipedia. */
data class Celebrity(
    val firstName: String,
    val lastName: String,
    val wikiTitle: String,
    val areas: Set<Area>,
    val decades: Set<Decade>,
    val origin: Origin,
    /** Wikidata-ID, falls bekannt (sonst ermittelt die App sie über den Artikel). */
    val qid: String? = null,
    /** Gewicht nach Bekanntheit (aus der Bewertung); bekanntere Personen kommen häufiger dran. */
    val popularity: Int = 0,
    /** Bekanntheitsstufe 1–3, siehe [FameLevel]; Stufe 4 = zu unbekannt, nie im Spiel. */
    val tier: Int = 1,
) {
    val displayName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Auswahl von Kategorien. Innerhalb einer Gruppe reicht eine passende Kategorie (oder),
 * zwischen den Gruppen müssen alle passen (und). Eine leere Gruppe schränkt nicht ein.
 * Beispiel: Musik + 80er = Musiker, die in den 80ern bekannt waren.
 * Ausgeschlossene Kategorien ([excludedAreas] usw.) entfernen jede Person, die auch nur eine davon hat:
 * „ohne Sport“ nimmt also auch Sportler heraus, die zusätzlich Politiker sind.
 */
data class CelebrityFilter(
    val areas: Set<Area> = emptySet(),
    val decades: Set<Decade> = emptySet(),
    val origins: Set<Origin> = emptySet(),
    val fame: FameLevel = FameLevel.FAMOUS,
    val excludedAreas: Set<Area> = emptySet(),
    val excludedDecades: Set<Decade> = emptySet(),
    val excludedOrigins: Set<Origin> = emptySet(),
) {
    /** Keine Kategorie gewählt oder ausgeschlossen (die Bekanntheitsstufe zählt hier nicht mit). */
    val isAll: Boolean
        get() = areas.isEmpty() && decades.isEmpty() && origins.isEmpty() &&
            excludedAreas.isEmpty() && excludedDecades.isEmpty() && excludedOrigins.isEmpty()

    fun matches(c: Celebrity): Boolean =
        c.tier <= fame.maxTier &&
            (areas.isEmpty() || c.areas.any { it in areas }) &&
            (decades.isEmpty() || c.decades.any { it in decades }) &&
            (origins.isEmpty() || c.origin in origins) &&
            c.areas.none { it in excludedAreas } &&
            c.decades.none { it in excludedDecades } &&
            c.origin !in excludedOrigins

    fun apply(all: List<Celebrity>): List<Celebrity> = all.filter(::matches)

    /** Kurzbeschreibung für Anzeigen, z. B. „Musik · 80er, 90er · ohne Sport“. */
    fun describe(): String {
        val categories = if (isAll) {
            "Alle Kategorien"
        } else {
            val included = listOf(areas, decades, origins).filter { it.isNotEmpty() }.map(::joinLabels)
            val excluded = listOf(excludedAreas, excludedDecades, excludedOrigins).filter { it.isNotEmpty() }
            (included + listOfNotNull(excluded.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "ohne ", transform = ::joinLabels)))
                .joinToString(" · ")
        }
        return "$categories · ${fame.label}"
    }

    private fun joinLabels(group: Set<*>): String = group.sortedBy { (it as Enum<*>).ordinal }.joinToString(", ") { label(it!!) }

    private fun label(value: Any): String = when (value) {
        is Area -> value.label
        is Decade -> value.label
        is Origin -> value.label
        else -> value.toString()
    }
}
