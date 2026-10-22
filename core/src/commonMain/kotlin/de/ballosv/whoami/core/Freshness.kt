package de.ballosv.whoami.core

import kotlin.math.ln
import kotlin.random.Random

object Freshness {
    /**
     * Zufällige Reihenfolge, aber Einträge, die noch nie dran waren, zuerst; danach die,
     * deren letzter Auftritt am längsten her ist. [recent] ist nach Alter sortiert (ältester zuerst).
     * Innerhalb der noch nie gezeigten Einträge kommen solche mit höherem [weight] eher dran.
     */
    fun <T> order(
        items: List<T>,
        recent: List<String>,
        random: Random = Random.Default,
        weight: (T) -> Double = { 1.0 },
        key: (T) -> String,
    ): List<T> {
        val lastSeen = recent.withIndex().associate { (index, k) -> k to index }
        // Gewichtete Zufallsreihenfolge (Efraimidis–Spirakis): höheres Gewicht -> eher weit vorne.
        val randomKey = items.associateWith { -ln(1.0 - random.nextDouble()) / weight(it).coerceAtLeast(1e-6) }
        return items.sortedWith(compareBy<T>({ lastSeen[key(it)] ?: -1 }, { randomKey.getValue(it) }))
    }
}
