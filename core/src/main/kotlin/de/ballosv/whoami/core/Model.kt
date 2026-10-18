package de.ballosv.whoami.core

import kotlinx.serialization.Serializable

@Serializable
enum class Phase {
    LOBBY,

    /** Bilder werden geladen bzw. an alle Geräte übertragen. */
    PREPARING,
    COUNTDOWN,
    REVEALING,
    ANSWERING,
    ROUND_RESULT,
    FINISHED,
}

@Serializable
data class PlayerInfo(
    val id: String,
    val name: String,
    val score: Int = 0,
    val ready: Boolean = false,
    val connected: Boolean = true,
    val isHost: Boolean = false,
    /** Battle-Modus: Anzahl hochgeladener Bilder. */
    val imageCount: Int = 0,
)

/** Eine zu erratende Person; der Name verlässt den Host erst, wenn die Runde vorbei ist. */
@Serializable
data class Question(
    val imageId: String,
    val firstName: String,
    val lastName: String,
    /** Battle-Modus: Spieler, von dem das Bild stammt. */
    val ownerId: String? = null,
) {
    val displayName: String get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ")
}

@Serializable
enum class EventType { CORRECT, WRONG, TIMEOUT, UNSOLVED, ALL_PASSED }

@Serializable
data class GameEvent(
    val id: Long,
    val type: EventType,
    val playerId: String? = null,
    val points: Int = 0,
    val answer: String? = null,
)

/**
 * Vollständiger, vom Host verteilter Spielzustand. Alle Zeitpunkte sind Host-Zeit;
 * jedes Gerät rechnet sie über seine Uhrensynchronisation in lokale Zeit um und
 * berechnet daraus selbst, welche Kacheln gerade sichtbar sind. So laufen alle
 * Geräte synchron, unabhängig davon, wann die Nachricht angekommen ist.
 */
@Serializable
data class GameSnapshot(
    val version: Long = 0,
    val phase: Phase = Phase.LOBBY,
    val settings: GameSettings = GameSettings(),
    val players: List<PlayerInfo> = emptyList(),
    val roundIndex: Int = 0,
    val roundCount: Int = 0,
    val imageId: String? = null,
    /** Reihenfolge, in der die Kacheln aufgedeckt werden. */
    val tileOrder: List<Int> = emptyList(),
    val ownerId: String? = null,
    /** Host-Zeit, ab der die Kachel-Uhr läuft (um Pausen verschoben). */
    val revealAnchor: Long = 0,
    /** Gesetzt, solange die Kachel-Uhr pausiert. */
    val pausedAt: Long? = null,
    val countdownEndsAt: Long = 0,
    val buzzPlayerId: String? = null,
    val answerDeadline: Long = 0,
    val buzzPoints: Int = 0,
    val buzzPressedAt: Long = 0,
    /** Spieler, die in der aktuellen Runde gepasst haben. */
    val passedIds: List<String> = emptyList(),
    val lastEvent: GameEvent? = null,
    val solution: String? = null,
    val nextRoundAt: Long = 0,
    val tvMode: Boolean = false,
    val preparingProgress: Float = 0f,
    val message: String? = null,
) {
    fun revealedAt(hostTime: Long): Int {
        if (phase != Phase.REVEALING && phase != Phase.ANSWERING) {
            return if (phase == Phase.ROUND_RESULT || phase == Phase.FINISHED) settings.tileCount else 0
        }
        val elapsed = (pausedAt ?: hostTime) - revealAnchor
        if (elapsed < 0) return 0
        return (elapsed / settings.revealIntervalMs).toInt().coerceAtMost(settings.tileCount)
    }

    /** Fortschritt (0..1) des Ausblendens der zuletzt aufgedeckten Kachel. */
    fun fadeProgressAt(hostTime: Long, fadeMs: Long): Float {
        val elapsed = (pausedAt ?: hostTime) - revealAnchor
        if (elapsed < 0) return 0f
        val sinceLast = elapsed % settings.revealIntervalMs
        return (sinceLast.toFloat() / fadeMs).coerceIn(0f, 1f)
    }

    fun pointsAt(hostTime: Long): Int = Scoring.pointsFor(settings, revealedAt(hostTime))

    fun player(id: String?): PlayerInfo? = players.firstOrNull { it.id == id }

    /** Spieler, die in dieser Runde raten (und passen) können: verbunden und nicht Besitzer des Bildes. */
    val guessers: List<PlayerInfo> get() = players.filter { it.connected && it.id != ownerId }

    fun hasPassed(id: String): Boolean = id in passedIds

    val ranking: List<PlayerInfo> get() = players.sortedWith(compareByDescending<PlayerInfo> { it.score }.thenBy { it.name })
}
