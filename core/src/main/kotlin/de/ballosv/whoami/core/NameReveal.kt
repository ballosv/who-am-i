package de.ballosv.whoami.core

/**
 * „Name schrittweise aufdecken“: Sind alle Kacheln weg und hat noch niemand gebuzzert, erscheint
 * der Name Buchstabe für Buchstabe, beginnend mit dem Vornamen. Die möglichen Punkte sinken nicht
 * pro Buchstabe, sondern in Stufen (siehe [thresholds]); ist der Name ganz aufgedeckt, endet die
 * Runde ohne Punkte.
 */
object NameReveal {
    /** Anzahl der Punktestufen im Namensteil (danach gibt es keine Punkte mehr). */
    const val STAGES = 4

    fun letterCount(text: String): Int = text.count { it.isLetterOrDigit() }

    /**
     * Buchstabenzahlen, ab denen die Punkte jeweils um eine Stufe sinken: halber Vorname,
     * ganzer Vorname, halber Nachname. Ohne Vornamen (Künstlernamen) Viertel des Namens.
     */
    fun thresholds(firstName: String, lastName: String): List<Int> {
        val f = letterCount(firstName)
        val l = letterCount(lastName)
        return if (f > 0) {
            listOf((f + 1) / 2, f, f + (l + 1) / 2)
        } else {
            listOf((l + 3) / 4, (l + 1) / 2, (3 * l + 3) / 4)
        }
    }

    /** Der Name mit den ersten [revealed] Buchstaben, der Rest als „_“ (Leerzeichen, Bindestriche usw. bleiben). */
    fun hint(displayName: String, revealed: Int): String {
        var seen = 0
        return buildString {
            for (c in displayName) {
                if (c.isLetterOrDigit()) {
                    append(if (seen < revealed) c else '_')
                    seen++
                } else {
                    append(c)
                }
            }
        }
    }

    /** Punkte in Stufe [stage] (0 = noch keine Schwelle erreicht), ausgehend von den Punkten bei allen Kacheln. */
    fun points(settings: GameSettings, stage: Int): Int {
        val base = Scoring.pointsFor(settings, settings.tileCount)
        return base * (STAGES - stage.coerceIn(0, STAGES)) / STAGES
    }
}
