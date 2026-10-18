package de.ballosv.whoami.core

import kotlin.random.Random

object Freshness {
    /**
     * Zufällige Reihenfolge, aber Einträge, die noch nie dran waren, zuerst; danach die,
     * deren letzter Auftritt am längsten her ist. [recent] ist nach Alter sortiert (ältester zuerst).
     */
    fun <T> order(items: List<T>, recent: List<String>, random: Random = Random.Default, key: (T) -> String): List<T> {
        val lastSeen = recent.withIndex().associate { (index, k) -> k to index }
        return items.shuffled(random).sortedBy { lastSeen[key(it)] ?: -1 }
    }
}
