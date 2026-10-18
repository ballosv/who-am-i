package de.ballosv.whoami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    private fun count(filter: CelebrityFilter) = filter.apply(CELEBRITIES).size

    @Test fun emptyFilterMeansAll() {
        assertTrue(CelebrityFilter().isAll)
        assertEquals(CELEBRITIES.size, count(CelebrityFilter()))
        assertEquals("Alle Kategorien", CelebrityFilter().describe())
    }

    @Test fun everySingleCategoryHasEnoughPeople() {
        Area.entries.forEach { assertTrue("$it", count(CelebrityFilter(areas = setOf(it))) >= 10) }
        Decade.entries.forEach { assertTrue("$it", count(CelebrityFilter(decades = setOf(it))) >= 20) }
        Origin.entries.forEach { assertTrue("$it", count(CelebrityFilter(origins = setOf(it))) >= 100) }
    }

    @Test fun groupsAreCombinedWithAndWithinGroupWithOr() {
        val music80s = CelebrityFilter(areas = setOf(Area.MUSIC), decades = setOf(Decade.D1980)).apply(CELEBRITIES)
        assertTrue(music80s.isNotEmpty())
        assertTrue(music80s.all { Area.MUSIC in it.areas && Decade.D1980 in it.decades })
        assertTrue(music80s.any { it.lastName == "Jackson" })

        val musicOrSport = count(CelebrityFilter(areas = setOf(Area.MUSIC, Area.SPORT)))
        assertTrue(musicOrSport > count(CelebrityFilter(areas = setOf(Area.MUSIC))))

        val germanPoliticians = CelebrityFilter(areas = setOf(Area.POLITICS), origins = setOf(Origin.GERMAN)).apply(CELEBRITIES)
        assertTrue(germanPoliticians.any { it.lastName == "Merkel" })
        assertTrue(germanPoliticians.none { it.lastName == "Obama" })
    }

    @Test fun describeListsSelection() {
        val f = CelebrityFilter(areas = setOf(Area.MUSIC), decades = setOf(Decade.D1990, Decade.D1980))
        assertEquals("Musik · 80er, 90er", f.describe())
    }

    @Test fun listHasNoDuplicatesAndEveryoneIsTagged() {
        assertEquals(CELEBRITIES.size, CELEBRITIES.map { it.wikiTitle }.toSet().size)
        assertTrue(CELEBRITIES.all { it.areas.isNotEmpty() && it.decades.isNotEmpty() })
    }

    @Test fun settingsCarryFilterOverTheWire() {
        val s = GameSettings(areas = setOf(Area.SPORT), decades = setOf(Decade.D2020), origins = setOf(Origin.GERMAN))
        val decoded = Message.json.decodeFromString(GameSettings.serializer(), Message.json.encodeToString(GameSettings.serializer(), s))
        assertEquals(s.celebrityFilter, decoded.celebrityFilter)
    }
}
