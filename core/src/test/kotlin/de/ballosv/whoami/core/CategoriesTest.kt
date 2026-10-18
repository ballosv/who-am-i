package de.ballosv.whoami.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {
    private fun count(filter: CelebrityFilter) = filter.apply(CELEBRITIES).size

    @Test fun emptyFilterMeansAll() {
        assertTrue(CelebrityFilter().isAll)
        assertEquals(CELEBRITIES.count { it.tier <= 3 }, count(CelebrityFilter()))
        assertEquals("Alle Kategorien · Bekannt", CelebrityFilter().describe())
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

    @Test fun fameLevelsNarrowTheSelection() {
        val famous = count(CelebrityFilter(fame = FameLevel.FAMOUS))
        val very = count(CelebrityFilter(fame = FameLevel.VERY_FAMOUS))
        val extreme = count(CelebrityFilter(fame = FameLevel.EXTREMELY_FAMOUS))
        assertTrue("$extreme < $very < $famous", extreme in 100 until very && very < famous)
        // In der Liste stehen nur bewertete Personen; „Bekannt“ umfasst alle
        assertEquals(CELEBRITIES.size, famous)
        assertTrue(CelebrityFilter(fame = FameLevel.EXTREMELY_FAMOUS).apply(CELEBRITIES).any { it.lastName == "Merkel" })
        assertEquals("Alle Kategorien · Bekannt", CelebrityFilter(fame = FameLevel.FAMOUS).describe())
        assertEquals(FameLevel.VERY_FAMOUS, GameSettings().fame)
    }

    @Test fun oldSavedFameValueFallsBackToDefault() {
        val old = """{"mode":"SINGLE","fame":"ALL"}"""
        assertEquals(FameLevel.VERY_FAMOUS, Message.json.decodeFromString(GameSettings.serializer(), old).fame)
    }

    @Test fun generatedListIsParsedTieredAndMerged() {
        val lines = listOf(
            "qid\ttitle\tfirst\tlast\tareas\tdecades\torigin\tlinks\tviews",
            "Q567\tAngela Merkel\tAngela\tMerkel\tP\t00 10\tD\t220\t250",
            "Q1\tNeue Person\tNeue\tPerson\tA M\t90 00\tI\t100",
            "Q2\tZweite Person\tZweite\tPerson\tS H\t50\tD\t60",
            "Q3\tKaputt\t\t\t\t\tI\t1",
        )
        val generated = CelebrityData.parse(lines)
        assertEquals(3, generated.size)
        assertEquals(1, generated[0].tier) // Platz 1 von 4: oberste 8 %
        assertEquals(4, generated[2].tier) // Platz 3 von 4: über 40 % -> zu unbekannt
        assertEquals(setOf(Area.ACTING, Area.MUSIC), generated[1].areas)
        val merged = CelebrityData.merge(CURATED_CELEBRITIES, generated)
        // Merkel nur einmal, mit Wikidata-ID und Bekanntheit aus der erzeugten Liste
        val merkel = merged.filter { it.lastName == "Merkel" }
        assertEquals(1, merkel.size)
        assertEquals("Q567", merkel[0].qid)
        assertEquals(250, merkel[0].popularity)
        assertEquals(CURATED_CELEBRITIES.size + 2, merged.size)
    }

    @Test fun ratingDecidesTierAndWeight() {
        val lines = listOf(
            "qid\ttitle\tfirst\tlast\tareas\tdecades\torigin\tlinks\tviews\trating",
            "Q1\tEins\tA\tEins\tP\t50\tD\t20\t90000\t3",
            "Q2\tZwei\tB\tZwei\tP\t50\tD\t20\t10\t5",
            "Q3\tDrei\tC\tDrei\tP\t50\tD\t20\t10\t4",
        )
        val generated = CelebrityData.parse(lines)
        // Seitenaufrufe zählen nicht mehr: die Bewertung bestimmt Stufe und Gewicht
        assertEquals(listOf(3, 1, 2), generated.map { it.tier })
        assertTrue(generated[1].popularity > generated[2].popularity)
        assertTrue(generated[2].popularity > generated[0].popularity)
        val curated = listOf(Celebrity("Willy", "Brandt", "Willy Brandt", setOf(Area.POLITICS), setOf(Decade.D1970), Origin.GERMAN))
        assertEquals(1, CelebrityData.merge(curated, emptyList(), mapOf("Willy Brandt" to 5))[0].tier)
    }

    @Test fun everyCuratedPersonIsRated() {
        val generated = CelebrityData.loadGenerated()
        val titles = generated.map { it.wikiTitle }.toSet()
        val names = generated.map { it.displayName.lowercase() }.toSet()
        val unrated = CURATED_CELEBRITIES.filter {
            it.wikiTitle !in titles && it.displayName.lowercase() !in names && it.wikiTitle !in CURATED_RATINGS
        }
        assertEquals(emptyList<String>(), unrated.map { it.wikiTitle })
    }

    @Test fun describeListsSelection() {
        val f = CelebrityFilter(areas = setOf(Area.MUSIC), decades = setOf(Decade.D1990, Decade.D1980))
        assertEquals("Musik · 80er, 90er · Bekannt", f.describe())
    }

    @Test fun listHasNoDuplicatesAndEveryoneIsTagged() {
        assertEquals(CELEBRITIES.size, CELEBRITIES.map { it.wikiTitle }.toSet().size)
        assertTrue(CELEBRITIES.all { it.areas.isNotEmpty() && it.decades.isNotEmpty() })
    }

    @Test fun settingsCarryFilterOverTheWire() {
        val s = GameSettings(areas = setOf(Area.SPORT), decades = setOf(Decade.D2020), origins = setOf(Origin.GERMAN))
        val decoded = Message.json.decodeFromString(GameSettings.serializer(), Message.json.encodeToString(GameSettings.serializer(), s))
        assertEquals(s.celebrityFilter, decoded.celebrityFilter)
        assertEquals(FameLevel.VERY_FAMOUS, GameSettings().fame)
    }
}
