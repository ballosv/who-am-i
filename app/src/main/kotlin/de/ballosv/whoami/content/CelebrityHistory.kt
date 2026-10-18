package de.ballosv.whoami.content

import android.content.Context

/**
 * Merkt sich, welche Promis zuletzt gezeigt wurden, damit über mehrere Spielrunden hinweg
 * möglichst alle Personen drankommen, bevor sich eine wiederholt.
 */
class CelebrityHistory(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("celebrity_history", Context.MODE_PRIVATE)

    /** Zuletzt gezeigte Artikeltitel, ältester zuerst. */
    fun recent(): List<String> =
        sp.getString(KEY, null)?.split(SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    fun record(titles: List<String>) {
        val updated = (recent() - titles.toSet()) + titles
        sp.edit().putString(KEY, updated.takeLast(MAX_ENTRIES).joinToString(SEPARATOR)).apply()
    }

    private companion object {
        const val KEY = "recent"
        const val SEPARATOR = "\n"
        const val MAX_ENTRIES = 1_000
    }
}
