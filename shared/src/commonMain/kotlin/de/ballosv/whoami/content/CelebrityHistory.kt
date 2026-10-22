package de.ballosv.whoami.content

import de.ballosv.whoami.platform.KeyValueStore

/**
 * Merkt sich, welche Promis zuletzt gezeigt wurden, damit über mehrere Spielrunden hinweg
 * möglichst alle Personen drankommen, bevor sich eine wiederholt.
 */
class CelebrityHistory {
    private val sp = KeyValueStore("celebrity_history")

    /** Zuletzt gezeigte Artikeltitel, ältester zuerst. */
    fun recent(): List<String> =
        sp.getString(KEY)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    fun record(titles: List<String>) {
        val updated = (recent() - titles.toSet()) + titles
        sp.putString(KEY, updated.takeLast(MAX_ENTRIES).joinToString(SEPARATOR))
    }

    private companion object {
        const val KEY = "recent"
        const val SEPARATOR = "\n"
        const val MAX_ENTRIES = 1_000
    }
}
