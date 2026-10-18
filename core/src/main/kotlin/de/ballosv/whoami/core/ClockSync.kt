package de.ballosv.whoami.core

/**
 * Schätzt den Versatz zwischen der lokalen Uhr und der Host-Uhr (NTP-artig).
 * Es zählen die Messungen mit der kürzesten Laufzeit, weil diese am wenigsten
 * durch asymmetrische Verzögerungen verfälscht sind.
 */
class ClockSync(private val windowSize: Int = 12) {
    private data class Sample(val offset: Long, val rtt: Long)

    private val samples = ArrayDeque<Sample>()

    /** Versatz, der zur lokalen Zeit addiert die Host-Zeit ergibt. */
    @Volatile
    var offsetMs: Long = 0
        private set

    @Volatile
    var rttMs: Long = -1
        private set

    val hasEstimate: Boolean get() = rttMs >= 0

    @Synchronized
    fun addSample(sentLocal: Long, hostTime: Long, receivedLocal: Long) {
        val rtt = receivedLocal - sentLocal
        if (rtt < 0) return
        val offset = hostTime - (sentLocal + rtt / 2)
        samples.addLast(Sample(offset, rtt))
        while (samples.size > windowSize) samples.removeFirst()
        val best = samples.sortedBy { it.rtt }.take(3)
        offsetMs = best.map { it.offset }.average().toLong()
        rttMs = best.first().rtt
    }

    fun toHostTime(localTime: Long): Long = localTime + offsetMs
}
