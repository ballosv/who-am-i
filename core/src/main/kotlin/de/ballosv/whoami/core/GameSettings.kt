package de.ballosv.whoami.core

import kotlinx.serialization.Serializable

@Serializable
enum class GameMode {
    /** Ein Spieler, Promi-Bilder. */
    SINGLE,

    /** Mehrere Spieler, Promi-Bilder. */
    MULTIPLAYER,

    /** Mehrere Spieler, jeder bringt eigene Bilder mit. */
    BATTLE,
}

@Serializable
enum class AnswerAccuracy {
    LAST_NAME,
    FULL_NAME,
}

@Serializable
data class GameSettings(
    val mode: GameMode = GameMode.SINGLE,
    /** Kacheln pro Seite; das Raster hat gridSize * gridSize Kacheln. */
    val gridSize: Int = 5,
    /** Abstand zwischen den Kacheln in dp (auf dem TV proportional skaliert). */
    val tileGapDp: Int = 3,
    /** Anzahl Personen pro Spielrunde (im Battle-Modus: Bilder pro Spieler). */
    val personCount: Int = 5,
    val revealIntervalMs: Long = 1500,
    val accuracy: AnswerAccuracy = AnswerAccuracy.LAST_NAME,
    /** Punkte, solange noch keine Kachel aufgedeckt ist. */
    val maxPoints: Int = 1000,
    /** Abzug pro aufgedeckter Kachel. */
    val pointsStepPerTile: Int = 40,
    /** Untergrenze, unter die der mögliche Punktewert nicht fällt. */
    val minPoints: Int = 50,
    val answerTimeSeconds: Int = 10,
    /** true: falsche Antwort kostet 50 % des aktuell möglichen Punktewerts, sonst [fixedPenalty]. */
    val halfPointsPenalty: Boolean = true,
    val fixedPenalty: Int = 100,
    /** Nach der letzten Kachel den Namen Buchstabe für Buchstabe einblenden (siehe [NameReveal]). */
    val revealName: Boolean = false,
    /** Kategorien für Promi-Bilder; leer bedeutet jeweils „alle“. */
    val areas: Set<Area> = emptySet(),
    val decades: Set<Decade> = emptySet(),
    val origins: Set<Origin> = emptySet(),
    val fame: FameLevel = FameLevel.VERY_FAMOUS,
    /** Ausgeschlossene Kategorien (Personen mit einer davon kommen nicht dran). */
    val excludedAreas: Set<Area> = emptySet(),
    val excludedDecades: Set<Decade> = emptySet(),
    val excludedOrigins: Set<Origin> = emptySet(),
) {
    val tileCount: Int get() = gridSize * gridSize

    val celebrityFilter: CelebrityFilter
        get() = CelebrityFilter(areas, decades, origins, fame, excludedAreas, excludedDecades, excludedOrigins)

    /** Alle Kategorien wieder zulassen (Bekanntheit bleibt). */
    fun withAllCategories(): GameSettings = copy(
        areas = emptySet(), decades = emptySet(), origins = emptySet(),
        excludedAreas = emptySet(), excludedDecades = emptySet(), excludedOrigins = emptySet(),
    )

    fun normalized(): GameSettings = copy(
        gridSize = gridSize.coerceIn(MIN_GRID, MAX_GRID),
        tileGapDp = tileGapDp.coerceIn(0, MAX_TILE_GAP_DP),
        personCount = personCount.coerceIn(1, MAX_PERSONS),
        revealIntervalMs = revealIntervalMs.coerceIn(MIN_INTERVAL_MS, MAX_INTERVAL_MS),
        maxPoints = maxPoints.coerceIn(10, 100_000),
        pointsStepPerTile = pointsStepPerTile.coerceIn(0, maxPoints),
        minPoints = minPoints.coerceIn(0, maxPoints),
        answerTimeSeconds = answerTimeSeconds.coerceIn(3, 60),
        fixedPenalty = fixedPenalty.coerceIn(0, 100_000),
    )

    companion object {
        const val MIN_GRID = 2
        const val MAX_GRID = 10
        const val MAX_TILE_GAP_DP = 16
        const val MAX_PERSONS = 50
        const val MIN_INTERVAL_MS = 200L
        const val MAX_INTERVAL_MS = 20_000L
    }
}
