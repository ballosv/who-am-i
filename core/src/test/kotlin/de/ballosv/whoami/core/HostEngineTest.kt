package de.ballosv.whoami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HostEngineTest {
    private val settings = GameSettings(
        mode = GameMode.MULTIPLAYER,
        gridSize = 3,
        revealIntervalMs = 1000,
        maxPoints = 900,
        pointsStepPerTile = 100,
        minPoints = 100,
        answerTimeSeconds = 5,
        halfPointsPenalty = true,
    )
    private val questions = listOf(
        Question("a", "Angela", "Merkel"),
        Question("b", "Brad", "Pitt"),
    )

    private fun engine(): HostEngine {
        val e = HostEngine(settings, PlayerInfo("host", "Host"), Random(1))
        e.addOrUpdatePlayer("p2", "Zweiter")
        e.startCountdown(questions, now = 0, countdownMs = 1000)
        e.tick(1000)
        return e
    }

    @Test fun revealsTilesOverTime() {
        val e = engine()
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        assertEquals(0, e.snapshot.revealedAt(1500))
        assertEquals(2, e.snapshot.revealedAt(3000))
        assertEquals(700, e.snapshot.pointsAt(3000))
        assertEquals(9, e.snapshot.revealedAt(100_000))
        assertEquals(100, e.snapshot.pointsAt(100_000))
    }

    private fun nameEngine(): HostEngine {
        val e = HostEngine(settings.copy(revealName = true), PlayerInfo("host", "Host"), Random(1))
        e.addOrUpdatePlayer("p2", "Zweiter")
        e.startCountdown(questions, now = 0, countdownMs = 1000)
        e.tick(1000)
        return e
    }

    @Test fun nameIsRevealedLetterByLetterAfterAllTiles() {
        val e = nameEngine()
        // 9 Kacheln à 1 s ab 1000 -> ab 10 000 kommen die Buchstaben, je 1 s
        e.tick(10_500)
        assertNull(e.snapshot.nameHint)
        assertEquals(100, e.snapshot.pointsAt(10_500))
        e.tick(11_000)
        assertEquals("A_____ ______", e.snapshot.nameHint)
        e.tick(17_000)
        assertEquals("Angela M_____", e.snapshot.nameHint)
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        // Ganzer Name (12 Buchstaben) bei 22 000: Runde ohne Punkte vorbei
        e.tick(21_999)
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        e.tick(22_000)
        assertEquals(Phase.ROUND_RESULT, e.snapshot.phase)
        assertEquals(EventType.UNSOLVED, e.snapshot.lastEvent!!.type)
        assertNull(e.snapshot.nameHint)
        assertEquals("Angela Merkel", e.snapshot.solution)
    }

    @Test fun namePointsDropInStages() {
        val s = nameEngine().snapshot
        // Punkte bei allen Kacheln: 100; Stufen nach halbem Vornamen (3), Vornamen (6), halbem Nachnamen (9)
        assertEquals(listOf(3, 6, 9), s.nameThresholds)
        assertEquals(100, s.pointsAt(12_000)) // 2 Buchstaben
        assertEquals(75, s.pointsAt(13_000)) // „Ang“
        assertEquals(75, s.pointsAt(15_000))
        assertEquals(50, s.pointsAt(16_000)) // „Angela“
        assertEquals(25, s.pointsAt(19_000)) // „Angela Mer“
        assertEquals(25, s.pointsAt(21_500))
    }

    @Test fun buzzDuringNameRevealUsesStagePoints() {
        val e = nameEngine()
        e.tick(16_000)
        assertTrue(e.buzz("p2", pressedAt = 16_200, now = 16_200))
        assertEquals(50, e.snapshot.buzzPoints)
        // Während der Antwort steht der Name still
        assertEquals(6, e.snapshot.nameLettersAt(30_000))
        assertTrue(e.answer("p2", "Merkel", now = 17_000))
        assertEquals(50, e.snapshot.player("p2")!!.score)
    }

    @Test fun noBuzzOnceNameIsComplete() {
        val e = nameEngine()
        assertFalse(e.buzz("p2", pressedAt = 22_000, now = 22_000))
    }

    @Test fun nameRevealHelpers() {
        assertEquals("J. K. R______", NameReveal.hint("J. K. Rowling", 3))
        assertEquals(listOf(2, 4, 6), NameReveal.thresholds("", "Madonna"))
        assertEquals(listOf(1, 2, 6), NameReveal.thresholds("Ed", "Sheeran"))
    }

    @Test fun withoutOptionNoNameReveal() {
        val e = engine()
        assertEquals(0, e.snapshot.nameLetters)
        e.tick(15_000)
        assertNull(e.snapshot.nameHint)
        assertEquals(100, e.snapshot.pointsAt(15_000))
    }

    @Test fun correctAnswerAwardsPointsAtBuzzTime() {
        val e = engine()
        assertTrue(e.buzz("p2", pressedAt = 3_200, now = 3_250))
        assertEquals(Phase.ANSWERING, e.snapshot.phase)
        // Pausiert: auch später sind nicht mehr Kacheln offen.
        assertEquals(2, e.snapshot.revealedAt(10_000))
        assertTrue(e.answer("p2", "merkel", now = 4_000))
        assertEquals(Phase.ROUND_RESULT, e.snapshot.phase)
        assertEquals(700, e.snapshot.player("p2")!!.score)
        assertEquals("Angela Merkel", e.snapshot.solution)
    }

    @Test fun wrongAnswerCostsHalfAndResumes() {
        val e = engine()
        e.buzz("p2", 3_200, 3_200)
        assertFalse(e.answer("p2", "Scholz", now = 5_200))
        val s = e.snapshot
        assertEquals(Phase.REVEALING, s.phase)
        assertEquals(-350, s.player("p2")!!.score)
        assertEquals(EventType.WRONG, s.lastEvent!!.type)
        // Die Pause von 2 s verschiebt die Kachel-Uhr.
        assertEquals(2, s.revealedAt(5_200))
        assertEquals(3, s.revealedAt(6_000))
    }

    @Test fun timeoutCountsAsWrong() {
        val e = engine()
        e.buzz("host", 2_000, 2_000)
        e.tick(6_999)
        assertEquals(Phase.ANSWERING, e.snapshot.phase)
        e.tick(7_000)
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        assertEquals(EventType.TIMEOUT, e.snapshot.lastEvent!!.type)
        assertEquals(-400, e.snapshot.player("host")!!.score)
    }

    @Test fun earlierBuzzWithinWindowWins() {
        val e = engine()
        assertTrue(e.buzz("host", 3_100, 3_100))
        assertTrue(e.buzz("p2", 3_050, 3_200))
        assertEquals("p2", e.snapshot.buzzPlayerId)
        assertFalse(e.buzz("host", 3_000, 3_600))
    }

    @Test fun fixedPenalty() {
        val e = HostEngine(settings.copy(halfPointsPenalty = false, fixedPenalty = 42), PlayerInfo("h", "H"))
        e.startCountdown(questions, 0, 0)
        e.tick(0)
        e.buzz("h", 0, 0)
        e.answer("h", "falsch", 10)
        assertEquals(-42, e.snapshot.player("h")!!.score)
    }

    @Test fun unsolvedRoundEndsAndGameFinishes() {
        val e = engine()
        val end = 1000 + 9 * 1000 + HostEngine.UNSOLVED_GRACE_MS
        e.tick(end)
        assertEquals(Phase.ROUND_RESULT, e.snapshot.phase)
        assertEquals(EventType.UNSOLVED, e.snapshot.lastEvent!!.type)
        e.tick(end + HostEngine.RESULT_MS)
        assertEquals(1, e.snapshot.roundIndex)
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        assertNull(e.snapshot.solution)
        e.buzz("p2", end + 10_000, end + 10_000)
        e.answer("p2", "Pitt", end + 11_000)
        e.tick(end + 11_000 + HostEngine.RESULT_MS)
        assertEquals(Phase.FINISHED, e.snapshot.phase)
        assertEquals("p2", e.snapshot.ranking.first().id)
    }

    @Test fun battleOwnerCannotBuzzAndRoundsRotate() {
        val e = HostEngine(settings.copy(mode = GameMode.BATTLE, personCount = 2), PlayerInfo("h", "H"), Random(3))
        e.addOrUpdatePlayer("p", "P")
        e.addBattleImage("h", Question("h1", "A", "Aa"))
        e.addBattleImage("h", Question("h2", "B", "Bb"))
        e.addBattleImage("h", Question("h3", "C", "Cc")) // über dem Limit
        e.addBattleImage("p", Question("p1", "D", "Dd"))
        e.setReady("h", true); e.setReady("p", true)
        assertTrue(e.allReady(2))
        val qs = e.battleQuestions()
        assertEquals(3, qs.size)
        assertTrue(qs[0].ownerId != qs[1].ownerId)
        e.startCountdown(qs, 0, 0)
        e.tick(0)
        val owner = e.snapshot.ownerId!!
        assertFalse(e.buzz(owner, 10, 10))
        assertTrue(e.buzz(if (owner == "h") "p" else "h", 10, 10))
    }

    @Test fun clockSyncPrefersFastSamples() {
        val c = ClockSync()
        c.addSample(sentLocal = 0, hostTime = 1_050, receivedLocal = 100)   // offset 1000
        c.addSample(sentLocal = 200, hostTime = 1_600, receivedLocal = 1_000) // langsam, verfälscht
        c.addSample(sentLocal = 2_000, hostTime = 3_010, receivedLocal = 2_020) // offset 1000
        c.addSample(sentLocal = 3_000, hostTime = 4_012, receivedLocal = 3_024) // offset 1000
        assertEquals(1000.0, c.offsetMs.toDouble(), 2.0)
    }

    @Test fun tileGapIsClampedAndTransmitted() {
        assertEquals(0, GameSettings(tileGapDp = -5).normalized().tileGapDp)
        assertEquals(GameSettings.MAX_TILE_GAP_DP, GameSettings(tileGapDp = 99).normalized().tileGapDp)
        val e = HostEngine(settings.copy(tileGapDp = 8), PlayerInfo("h", "H"))
        val decoded = Message.decode(Message.encode(Message.State(e.snapshot))) as Message.State
        assertEquals(8, decoded.snapshot.settings.tileGapDp)
    }

    @Test fun creditIsRevealedOnlyWithSolution() {
        val e = HostEngine(settings, PlayerInfo("h", "H"))
        e.startCountdown(listOf(Question("x", "Angela", "Merkel", credit = "Foto: A. B., CC BY 2.0")), 0, 0)
        e.tick(0)
        assertNull(e.snapshot.credit)
        e.buzz("h", 10, 10)
        e.answer("h", "Merkel", 20)
        assertEquals("Foto: A. B., CC BY 2.0", e.snapshot.credit)
    }

    @Test fun allPassedRevealsWithoutPoints() {
        val e = engine()
        e.setPassed("host", true, 2_000)
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        assertEquals(listOf("host"), e.snapshot.passedIds)
        e.setPassed("p2", true, 2_500)
        val s = e.snapshot
        assertEquals(Phase.ROUND_RESULT, s.phase)
        assertEquals(EventType.ALL_PASSED, s.lastEvent!!.type)
        assertEquals("Angela Merkel", s.solution)
        assertEquals(9, s.revealedAt(2_500))
        assertTrue(s.players.all { it.score == 0 })
        // Neue Runde: Pässe zurückgesetzt
        e.tick(2_500 + HostEngine.RESULT_MS)
        assertTrue(e.snapshot.passedIds.isEmpty())
    }

    @Test fun passCanBeWithdrawn() {
        val e = engine()
        e.setPassed("host", true, 2_000)
        e.setPassed("host", false, 2_100)
        e.setPassed("p2", true, 2_200)
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        assertEquals(listOf("p2"), e.snapshot.passedIds)
    }

    @Test fun buzzingWithdrawsPassAndPassWaitsForAnswer() {
        val e = engine()
        e.setPassed("p2", true, 2_000)
        e.buzz("p2", 2_100, 2_100)
        assertFalse(e.snapshot.hasPassed("p2"))
        // Host passt, während p2 antwortet: Runde läuft weiter bis zur Antwort
        e.setPassed("host", true, 2_200)
        assertEquals(Phase.ANSWERING, e.snapshot.phase)
        e.setPassed("p2", true, 2_300) // wer antwortet, kann nicht passen
        assertFalse(e.snapshot.hasPassed("p2"))
        e.answer("p2", "falsch", 2_400)
        assertEquals(Phase.REVEALING, e.snapshot.phase)
        e.setPassed("p2", true, 2_500)
        assertEquals(Phase.ROUND_RESULT, e.snapshot.phase)
    }

    @Test fun disconnectOfLastNonPasserEndsRound() {
        val e = engine()
        e.setPassed("host", true, 2_000)
        e.setConnected("p2", false, 2_100)
        assertEquals(Phase.ROUND_RESULT, e.snapshot.phase)
    }

    @Test fun battleOwnerCannotPass() {
        val e = HostEngine(settings.copy(mode = GameMode.BATTLE), PlayerInfo("h", "H"))
        e.addOrUpdatePlayer("p", "P")
        e.startCountdown(listOf(Question("x", "A", "Bcdef", ownerId = "h")), 0, 0)
        e.tick(0)
        e.setPassed("h", true, 10)
        assertTrue(e.snapshot.passedIds.isEmpty())
        e.setPassed("p", true, 20)
        assertEquals(Phase.ROUND_RESULT, e.snapshot.phase)
    }

    @Test fun protocolRoundTrip() {
        val msg = Message.State(engine().snapshot)
        assertEquals(msg, Message.decode(Message.encode(msg)))
        val data = ByteArray(40_000) { it.toByte() }
        val asm = ImageAssembler()
        val chunks = ImageAssembler.split("x", data)
        assertEquals(3, chunks.size)
        chunks.dropLast(1).forEach { assertNull(asm.add(it)) }
        assertTrue(data.contentEquals(asm.add(chunks.last())!!))
        chunks.forEach { assertTrue(Message.encode(it).length < 32_000) }
    }
}
