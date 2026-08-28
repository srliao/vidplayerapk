package io.github.srliao.kioskplayer.core

/**
 * Detects a stream that has died without libVLC emitting any event: the last
 * frame stays on screen and the app looks healthy to everything, including
 * Fully Kiosk. At the 10-second sampling rate, [threshold] of 3 fires ~30s in.
 */
class StallDetector(val threshold: Int = 3) {

    private var previous: HealthSample? = null

    var consecutiveStalls: Int = 0
        private set

    /** Returns true exactly when the stall threshold is reached. */
    fun update(sample: HealthSample): Boolean {
        val prev = previous
        previous = sample

        if (!sample.isPlaying) {
            consecutiveStalls = 0
            return false
        }
        if (prev == null) return false

        consecutiveStalls =
            if (sample.decodedVideo > prev.decodedVideo) 0 else consecutiveStalls + 1

        return consecutiveStalls >= threshold
    }

    fun reset() {
        previous = null
        consecutiveStalls = 0
    }
}
