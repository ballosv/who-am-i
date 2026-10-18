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
