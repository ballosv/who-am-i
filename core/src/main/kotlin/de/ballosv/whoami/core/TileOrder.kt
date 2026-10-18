package de.ballosv.whoami.core

import kotlin.random.Random

object TileOrder {
    /** Reihenfolge, in der die Kacheln aufgedeckt werden; auf allen Geräten identisch für denselben Seed. */
    fun order(tileCount: Int, seed: Long): IntArray {
        val list = IntArray(tileCount) { it }
        val random = Random(seed)
        for (i in tileCount - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = list[i]; list[i] = list[j]; list[j] = tmp
        }
        return list
    }
}
