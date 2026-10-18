package de.ballosv.whoami.core

import kotlin.random.Random

/**
 * Verbindliche Spiellogik, läuft nur auf dem Host. Jede Änderung erzeugt einen neuen
 * [GameSnapshot], der über [onChange] an alle Geräte verteilt wird. Zeitabhängige
 * Übergänge (Countdown, Antwortzeit, Rundenende) prüft [tick].
 */
class HostEngine(
    settings: GameSettings,
    host: PlayerInfo,
    private val random: Random = Random.Default,
) {
    var snapshot: GameSnapshot = GameSnapshot(
        settings = settings.normalized(),
        players = listOf(host.copy(isHost = true)),
    )
        private set

    var onChange: ((GameSnapshot) -> Unit)? = null

    private var questions: List<Question> = emptyList()
    private var eventCounter = 0L
    private var buzzAcceptedAt = 0L

    /** Battle-Modus: eingereichte Bilder pro Spieler. */
    private val battleEntries = LinkedHashMap<String, MutableList<Question>>()

    val hostId: String = host.id

    private fun update(block: (GameSnapshot) -> GameSnapshot) {
        val next = block(snapshot)
        if (next == snapshot) return
        snapshot = next.copy(version = snapshot.version + 1)
        onChange?.invoke(snapshot)
    }

    private fun updatePlayer(id: String, block: (PlayerInfo) -> PlayerInfo) = update { s ->
        s.copy(players = s.players.map { if (it.id == id) block(it) else it })
    }

    // ---------------------------------------------------------------- Lobby

    fun updateSettings(settings: GameSettings) {
        if (snapshot.phase != Phase.LOBBY) return
        update { it.copy(settings = settings.normalized()) }
    }

    fun addOrUpdatePlayer(id: String, name: String) {
        val existing = snapshot.player(id)
        if (existing != null) {
            updatePlayer(id) { it.copy(name = name, connected = true) }
            return
        }
        if (snapshot.phase != Phase.LOBBY) return
        update { it.copy(players = it.players + PlayerInfo(id = id, name = name)) }
    }

    fun setName(id: String, name: String) = updatePlayer(id) { it.copy(name = name.trim().take(24)) }

    fun setConnected(id: String, connected: Boolean, now: Long) {
        if (snapshot.phase == Phase.LOBBY && !connected && id != hostId) {
            battleEntries.remove(id)
            update { s -> s.copy(players = s.players.filterNot { it.id == id }) }
            return
        }
        updatePlayer(id) { it.copy(connected = connected) }
        if (!connected && snapshot.phase == Phase.ANSWERING && snapshot.buzzPlayerId == id) {
            failAnswer(now, EventType.TIMEOUT, null)
        }
    }

    fun setReady(id: String, ready: Boolean) {
        if (snapshot.phase != Phase.LOBBY) return
        updatePlayer(id) { it.copy(ready = ready) }
    }

    fun addBattleImage(ownerId: String, question: Question) {
        if (snapshot.phase != Phase.LOBBY) return
        val list = battleEntries.getOrPut(ownerId) { mutableListOf() }
        if (list.size >= snapshot.settings.personCount || list.any { it.imageId == question.imageId }) return
        list += question.copy(ownerId = ownerId)
        updatePlayer(ownerId) { it.copy(imageCount = list.size) }
    }

    fun clearBattleImages(ownerId: String) {
        battleEntries.remove(ownerId)
        updatePlayer(ownerId) { it.copy(imageCount = 0, ready = false) }
    }

    fun battleImageIds(): List<String> = battleEntries.values.flatten().map { it.imageId }

    /** Alle (mindestens [minPlayers]) Spieler sind verbunden und bereit. */
    fun allReady(minPlayers: Int): Boolean {
        val players = snapshot.players.filter { it.connected }
        if (players.size < minPlayers) return false
        if (!players.all { it.ready }) return false
        if (snapshot.settings.mode == GameMode.BATTLE) {
            return players.all { (battleEntries[it.id]?.size ?: 0) > 0 }
        }
        return true
    }

    // ---------------------------------------------------------------- Start

    /** Wechselt in die Vorbereitungsphase (Bilder laden/verteilen). */
    fun beginPreparing() {
        if (snapshot.phase != Phase.LOBBY) return
        update { it.copy(phase = Phase.PREPARING, preparingProgress = 0f, message = null) }
    }

    fun setPreparingProgress(progress: Float, message: String? = null) {
        if (snapshot.phase != Phase.PREPARING) return
        update { it.copy(preparingProgress = progress.coerceIn(0f, 1f), message = message) }
    }

    /** Fügt die Battle-Bilder reihum (Spieler für Spieler) zu einer Rundenliste zusammen. */
    fun battleQuestions(): List<Question> {
        val lists = battleEntries.values.map { it.shuffled(random).toMutableList() }.shuffled(random)
        val result = mutableListOf<Question>()
        while (lists.any { it.isNotEmpty() }) {
            for (list in lists) if (list.isNotEmpty()) result += list.removeAt(0)
        }
        return result
    }

    fun startCountdown(questions: List<Question>, now: Long, countdownMs: Long = COUNTDOWN_MS) {
        require(questions.isNotEmpty()) { "Keine Personen zum Erraten" }
        this.questions = questions
        update { s ->
            s.copy(
                phase = Phase.COUNTDOWN,
                roundCount = questions.size,
                roundIndex = 0,
                countdownEndsAt = now + countdownMs,
                players = s.players.map { it.copy(score = 0) },
                message = null,
                lastEvent = null,
                solution = null,
            )
        }
    }

    private fun startRound(index: Int, anchor: Long) {
        val q = questions[index]
        update { s ->
            s.copy(
                phase = Phase.REVEALING,
                roundIndex = index,
                imageId = q.imageId,
                ownerId = q.ownerId,
                tileOrder = TileOrder.order(s.settings.tileCount, random.nextLong()).toList(),
                revealAnchor = anchor,
                pausedAt = null,
                buzzPlayerId = null,
                solution = null,
                answerDeadline = 0,
            )
        }
    }

    // ---------------------------------------------------------------- Spiel

    fun canBuzz(playerId: String): Boolean {
        val s = snapshot
        val p = s.player(playerId) ?: return false
        return p.connected && playerId != s.ownerId
    }

    /**
     * Ein Spieler drückt den Buzzer. [pressedAt] ist der vom Gerät gemeldete Zeitpunkt in
     * Host-Zeit. Treffen innerhalb von [BUZZ_WINDOW_MS] mehrere Buzzer ein, gewinnt der
     * früheste Druck – so hat eine langsamere Verbindung keinen Nachteil.
     */
    fun buzz(playerId: String, pressedAt: Long, now: Long): Boolean {
        if (!canBuzz(playerId)) return false
        val s = snapshot
        // Nicht weiter in die Vergangenheit/Zukunft akzeptieren, als es Netzlatenz erklären kann.
        val ts = pressedAt.coerceIn(now - MAX_BUZZ_LAG_MS, now)
        when (s.phase) {
            Phase.REVEALING -> {
                if (now < s.revealAnchor) return false
                val points = Scoring.pointsFor(s.settings, s.revealedAt(ts))
                buzzAcceptedAt = now
                update {
                    it.copy(
                        phase = Phase.ANSWERING,
                        buzzPlayerId = playerId,
                        buzzPoints = points,
                        pausedAt = now,
                        answerDeadline = now + it.settings.answerTimeSeconds * 1000L,
                        buzzPressedAt = ts,
                    )
                }
                return true
            }
            Phase.ANSWERING -> {
                val earlier = playerId != s.buzzPlayerId && ts < s.buzzPressedAt &&
                    now - buzzAcceptedAt <= BUZZ_WINDOW_MS
                if (!earlier) return false
                val points = Scoring.pointsFor(s.settings, s.revealedAt(ts))
                update {
                    it.copy(
                        buzzPlayerId = playerId,
                        buzzPoints = points,
                        buzzPressedAt = ts,
                        answerDeadline = now + it.settings.answerTimeSeconds * 1000L,
                    )
                }
                return true
            }
            else -> return false
        }
    }

    /** @return true bei richtiger Antwort */
    fun answer(playerId: String, text: String, now: Long): Boolean {
        val s = snapshot
        if (s.phase != Phase.ANSWERING || s.buzzPlayerId != playerId) return false
        val q = questions[s.roundIndex]
        if (NameMatcher.matches(text, q.firstName, q.lastName, s.settings.accuracy)) {
            val points = s.buzzPoints
            update {
                it.copy(
                    phase = Phase.ROUND_RESULT,
                    players = it.players.map { p -> if (p.id == playerId) p.copy(score = p.score + points) else p },
                    solution = q.displayName,
                    lastEvent = GameEvent(++eventCounter, EventType.CORRECT, playerId, points, text),
                    nextRoundAt = now + RESULT_MS,
                    pausedAt = null,
                )
            }
            return true
        }
        failAnswer(now, EventType.WRONG, text)
        return false
    }

    private fun failAnswer(now: Long, type: EventType, text: String?) {
        val s = snapshot
        val playerId = s.buzzPlayerId ?: return
        val penalty = Scoring.penaltyFor(s.settings, s.buzzPoints)
        val pausedAt = s.pausedAt ?: now
        update {
            it.copy(
                phase = Phase.REVEALING,
                players = it.players.map { p -> if (p.id == playerId) p.copy(score = p.score - penalty) else p },
                lastEvent = GameEvent(++eventCounter, type, playerId, -penalty, text),
                buzzPlayerId = null,
                // Kachel-Uhr läuft dort weiter, wo sie angehalten wurde.
                revealAnchor = it.revealAnchor + (now - pausedAt),
                pausedAt = null,
            )
        }
    }

    fun tick(now: Long) {
        val s = snapshot
        when (s.phase) {
            Phase.COUNTDOWN -> if (now >= s.countdownEndsAt) startRound(0, s.countdownEndsAt)
            Phase.ANSWERING -> if (now >= s.answerDeadline) failAnswer(now, EventType.TIMEOUT, null)
            Phase.REVEALING -> {
                val end = s.revealAnchor + s.settings.tileCount * s.settings.revealIntervalMs + UNSOLVED_GRACE_MS
                if (now >= end) {
                    val q = questions[s.roundIndex]
                    update {
                        it.copy(
                            phase = Phase.ROUND_RESULT,
                            solution = q.displayName,
                            lastEvent = GameEvent(++eventCounter, EventType.UNSOLVED),
                            nextRoundAt = now + RESULT_MS,
                        )
                    }
                }
            }
            Phase.ROUND_RESULT -> if (now >= s.nextRoundAt) {
                val next = s.roundIndex + 1
                if (next >= questions.size) {
                    update { it.copy(phase = Phase.FINISHED, imageId = null, ownerId = null) }
                } else {
                    startRound(next, now + NEXT_ROUND_DELAY_MS)
                }
            }
            else -> Unit
        }
    }

    fun setTvMode(enabled: Boolean) = update { it.copy(tvMode = enabled) }

    /** Zurück in die Lobby für eine neue Runde mit denselben Spielern. */
    fun backToLobby() {
        questions = emptyList()
        battleEntries.clear()
        update { s ->
            s.copy(
                phase = Phase.LOBBY,
                imageId = null,
                ownerId = null,
                buzzPlayerId = null,
                solution = null,
                lastEvent = null,
                players = s.players.filter { it.connected }
                    .map { it.copy(ready = false, imageCount = 0, score = 0) },
            )
        }
    }

    companion object {
        const val COUNTDOWN_MS = 3_500L
        const val RESULT_MS = 5_000L
        const val NEXT_ROUND_DELAY_MS = 1_500L
        const val UNSOLVED_GRACE_MS = 8_000L
        const val BUZZ_WINDOW_MS = 300L
        const val MAX_BUZZ_LAG_MS = 1_000L
    }
}
