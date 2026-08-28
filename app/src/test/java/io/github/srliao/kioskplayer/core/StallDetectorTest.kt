package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StallDetectorTest {

    private var t = 0L

    private fun sample(
        decoded: Int,
        demux: Int = 0,
        playing: Boolean = true,
    ): HealthSample {
        t += 10_000
        return HealthSample(
            atMs = t,
            decodedVideo = decoded,
            displayedPictures = decoded,
            demuxReadBytes = demux,
            inputBitrate = 0.5f,
            isPlaying = playing,
            vlcTimeMs = 0,
        )
    }

    @Test
    fun `the first sample can never stall - there is nothing to compare`() {
        assertFalse(StallDetector().update(sample(0)))
    }

    @Test
    fun `three frozen samples after a baseline trigger a stall`() {
        val d = StallDetector()
        assertFalse(d.update(sample(100)))   // baseline
        assertFalse(d.update(sample(100)))   // stall 1
        assertFalse(d.update(sample(100)))   // stall 2
        assertTrue(d.update(sample(100)))    // stall 3 -> fire
        assertEquals(3, d.consecutiveStalls)
    }

    @Test
    fun `an advancing frame count resets the counter`() {
        val d = StallDetector()
        d.update(sample(100))
        d.update(sample(100))
        d.update(sample(100))
        assertEquals(2, d.consecutiveStalls)
        assertFalse(d.update(sample(101)))
        assertEquals(0, d.consecutiveStalls)
    }

    @Test
    fun `a frozen count does not stall while the player is not playing`() {
        val d = StallDetector()
        d.update(sample(100))
        assertFalse(d.update(sample(100, playing = false)))
        assertFalse(d.update(sample(100, playing = false)))
        assertFalse(d.update(sample(100, playing = false)))
        assertEquals(0, d.consecutiveStalls)
    }

    @Test
    fun `a pause clears stalls accumulated before it`() {
        val d = StallDetector()
        d.update(sample(100)); d.update(sample(100)); d.update(sample(100))
        assertEquals(2, d.consecutiveStalls)
        d.update(sample(100, playing = false))
        assertEquals(0, d.consecutiveStalls)
        assertFalse(d.update(sample(100)))
    }

    @Test
    fun `a wedged decoder still stalls even while bytes keep arriving`() {
        val d = StallDetector()
        d.update(sample(100, demux = 1_000))
        d.update(sample(100, demux = 2_000))
        d.update(sample(100, demux = 3_000))
        assertTrue(d.update(sample(100, demux = 4_000)))
    }

    @Test
    fun `reset clears both the counter and the baseline`() {
        val d = StallDetector()
        d.update(sample(100))
        d.update(sample(100))
        d.reset()
        assertEquals(0, d.consecutiveStalls)
        assertFalse(d.update(sample(100)))
    }
}
