package de.ballosv.whoami.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameMatcherTest {
    private fun last(answer: String, first: String, last: String) =
        NameMatcher.matches(answer, first, last, AnswerAccuracy.LAST_NAME)

    private fun full(answer: String, first: String, last: String) =
        NameMatcher.matches(answer, first, last, AnswerAccuracy.FULL_NAME)

    @Test fun lastNameOnly() {
        assertTrue(last("Merkel", "Angela", "Merkel"))
        assertTrue(last("angela merkel", "Angela", "Merkel"))
        assertTrue(last("Merkl", "Angela", "Merkel"))
        assertFalse(last("Angela", "Angela", "Merkel"))
    }

    @Test fun fullNameRequiresBoth() {
        assertTrue(full("Angela Merkel", "Angela", "Merkel"))
        assertTrue(full("Merkel, Angela", "Angela", "Merkel"))
        assertFalse(full("Merkel", "Angela", "Merkel"))
    }

    @Test fun firstOfSeveralGivenNamesIsEnough() {
        assertTrue(full("Anna Netrebko", "Anna Jurjewna", "Netrebko"))
        assertTrue(full("Johann Wolfgang Goethe", "Johann Wolfgang", "Goethe"))
        assertTrue(full("Johann Goethe", "Johann Wolfgang", "Goethe"))
        assertFalse(full("Wolfgang Goethe", "Johann Wolfgang", "Goethe"))
    }

    @Test fun umlautsAndAccents() {
        assertTrue(last("Mueller", "Thomas", "Müller"))
        assertTrue(last("Muller", "Thomas", "Müller"))
        assertTrue(last("Beyonce", "", "Beyoncé"))
        assertTrue(last("Strauss", "Johann", "Strauß"))
    }

    @Test fun compoundAndSpacedNames() {
        assertTrue(last("di caprio", "Leonardo", "DiCaprio"))
        assertTrue(full("Karl Theodor zu Guttenberg", "Karl-Theodor", "zu Guttenberg"))
    }

    @Test fun nameParticlesAreOptional() {
        assertTrue(last("Leyen", "Ursula", "von der Leyen"))
        assertTrue(last("von der Leyen", "Ursula", "von der Leyen"))
        assertTrue(last("Vinci", "Leonardo", "da Vinci"))
        assertTrue(last("van Gogh", "Vincent", "van Gogh"))
        assertTrue(last("Djokovic", "Novak", "Đoković"))
    }

    @Test fun shortNamesAreStrict() {
        assertTrue(last("Pitt", "Brad", "Pitt"))
        assertFalse(last("Pott", "Brad", "Pitt"))
    }

    @Test fun emptyAnswerNeverMatches() {
        assertFalse(last("  ", "Brad", "Pitt"))
    }
}

class TileOrderTest {
    @Test fun isPermutationAndDeterministic() {
        val a = TileOrder.order(25, 7)
        assertTrue(a.sorted() == (0 until 25).toList())
        assertTrue(a.contentEquals(TileOrder.order(25, 7)))
    }
}

class FreshnessTest {
    @Test fun unseenFirstThenOldest() {
        val items = (1..10).map { "p$it" }
        val recent = listOf("p3", "p1", "p2") // p3 am längsten her, p2 zuletzt
        repeat(20) { seed ->
            val order = Freshness.order(items, recent, kotlin.random.Random(seed)) { it }
            assertTrue(order.take(7).toSet() == (4..10).map { "p$it" }.toSet())
            assertTrue(order.drop(7) == listOf("p3", "p1", "p2"))
        }
    }

    @Test fun weightPrefersPopularAmongUnseen() {
        val items = listOf("star", "nobody")
        var starFirst = 0
        repeat(1000) { seed ->
            val order = Freshness.order(items, emptyList(), kotlin.random.Random(seed), weight = { if (it == "star") 9.0 else 1.0 }) { it }
            if (order.first() == "star") starFirst++
        }
        assertTrue(starFirst in 850..950) // erwartet 90 %
    }

    @Test fun rotatesThroughAllBeforeRepeating() {
        val items = (1..12).map { "p$it" }
        val history = mutableListOf<String>()
        val seen = mutableListOf<String>()
        repeat(4) { round ->
            val picked = Freshness.order(items, history, kotlin.random.Random(round)) { it }.take(3)
            seen += picked
            history.removeAll(picked); history += picked
        }
        assertTrue(seen.toSet().size == 12)
    }
}
