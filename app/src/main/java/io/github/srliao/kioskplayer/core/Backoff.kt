package io.github.srliao.kioskplayer.core

/**
 * 2s, 4s, 8s, 16s, then capped. Never gives up: the cap means a panel that has
 * been offline all night recovers within 30 seconds of the network returning.
 * No jitter - there is one client, and determinism is worth more in tests.
 */
class Backoff(
    private val baseMs: Long = 2_000,
    private val capMs: Long = 30_000,
) {
    var attempt: Int = 0
        private set

    fun next(): Long {
        attempt += 1
        if (attempt >= 20) return capMs          // guard the shift from overflowing
        return minOf(baseMs shl (attempt - 1), capMs)
    }

    fun reset() {
        attempt = 0
    }
}
