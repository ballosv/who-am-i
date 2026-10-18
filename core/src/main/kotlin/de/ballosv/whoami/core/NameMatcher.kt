package de.ballosv.whoami.core

import java.text.Normalizer

/**
 * Vergleicht eine Spielereingabe (getippt oder per Spracherkennung) tolerant mit dem
 * gesuchten Namen: Groß-/Kleinschreibung, Akzente, Bindestriche und kleine Tippfehler
 * werden ignoriert.
 */
object NameMatcher {

    fun matches(answer: String, firstName: String, lastName: String, accuracy: AnswerAccuracy): Boolean {
        val tokens = tokens(answer)
        if (tokens.isEmpty()) return false
        val last = normalize(lastName).replace(" ", "")
        val first = normalize(firstName).replace(" ", "")
        // Ohne Nachnamen (z. B. Künstlername im Vornamenfeld) zählt der Vorname als Name.
        if (last.isEmpty()) return first.isNotEmpty() && containsFuzzy(tokens, first)
        val lastOk = containsFuzzy(tokens, last)
        return when (accuracy) {
            AnswerAccuracy.LAST_NAME -> lastOk
            AnswerAccuracy.FULL_NAME -> lastOk && (first.isEmpty() || containsFuzzy(tokens, first))
        }
    }

    fun normalize(text: String): String {
        val replaced = text.lowercase()
            .replace("ß", "ss")
            .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue")
        val stripped = Normalizer.normalize(replaced, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return stripped.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    private fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }

    /** Prüft, ob eine zusammenhängende Tokenfolge der Antwort dem Ziel (ohne Leerzeichen) ähnelt. */
    private fun containsFuzzy(tokens: List<String>, target: String): Boolean {
        val tolerance = toleranceFor(target.length)
        for (start in tokens.indices) {
            val sb = StringBuilder()
            for (end in start until tokens.size) {
                sb.append(tokens[end])
                if (sb.length > target.length + tolerance) break
                if (levenshtein(sb, target) <= tolerance) return true
            }
        }
        return false
    }

    private fun toleranceFor(length: Int): Int = when {
        length <= 4 -> 0
        length <= 7 -> 1
        else -> 2
    }

    fun levenshtein(a: CharSequence, b: CharSequence): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[b.length]
    }
}
