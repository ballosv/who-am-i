package de.ballosv.whoami.core

object Scoring {
    /** Punkte, die es bei [revealedTiles] aufgedeckten Kacheln für eine richtige Lösung gibt. */
    fun pointsFor(settings: GameSettings, revealedTiles: Int): Int =
        (settings.maxPoints - revealedTiles * settings.pointsStepPerTile)
            .coerceAtLeast(settings.minPoints)

    fun penaltyFor(settings: GameSettings, currentPoints: Int): Int =
        if (settings.halfPointsPenalty) (currentPoints + 1) / 2 else settings.fixedPenalty
}
