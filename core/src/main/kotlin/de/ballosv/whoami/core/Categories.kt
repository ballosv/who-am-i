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

/** Prominente Person; [wikiTitle] ist der Artikel in der deutschsprachigen Wikipedia. */
data class Celebrity(
    val firstName: String,
    val lastName: String,
    val wikiTitle: String,
    val areas: Set<Area>,
    val decades: Set<Decade>,
    val origin: Origin,
)

/**
 * Auswahl von Kategorien. Innerhalb einer Gruppe reicht eine passende Kategorie (oder),
 * zwischen den Gruppen müssen alle passen (und). Eine leere Gruppe schränkt nicht ein.
 * Beispiel: Musik + 80er = Musiker, die in den 80ern bekannt waren.
 */
data class CelebrityFilter(
    val areas: Set<Area> = emptySet(),
    val decades: Set<Decade> = emptySet(),
    val origins: Set<Origin> = emptySet(),
) {
    val isAll: Boolean get() = areas.isEmpty() && decades.isEmpty() && origins.isEmpty()

    fun matches(c: Celebrity): Boolean =
        (areas.isEmpty() || c.areas.any { it in areas }) &&
            (decades.isEmpty() || c.decades.any { it in decades }) &&
            (origins.isEmpty() || c.origin in origins)

    fun apply(all: List<Celebrity>): List<Celebrity> = if (isAll) all else all.filter(::matches)

    /** Kurzbeschreibung für Anzeigen, z. B. „Musik · 80er, 90er“. */
    fun describe(): String = if (isAll) {
        "Alle Kategorien"
    } else {
        listOf(areas, decades, origins)
            .filter { it.isNotEmpty() }
            .joinToString(" · ") { group -> group.sortedBy { (it as Enum<*>).ordinal }.joinToString(", ") { label(it) } }
    }

    private fun label(value: Any): String = when (value) {
        is Area -> value.label
        is Decade -> value.label
        is Origin -> value.label
        else -> value.toString()
    }
}
