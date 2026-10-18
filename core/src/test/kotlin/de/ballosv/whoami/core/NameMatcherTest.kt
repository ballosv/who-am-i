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
