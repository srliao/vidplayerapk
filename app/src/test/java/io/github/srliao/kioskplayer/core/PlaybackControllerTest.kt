package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackControllerTest {

    private val cam = StreamEntry("a", "Front Door", "rtsps://h/a")

    /** Brings a fresh controller to Playing and returns it. */
    private fun playing(c: PlaybackController = PlaybackController()): PlaybackController {
        c.step(Input.SurfaceReady(0))
        c.step(Input.StreamSelected(0, cam))
        c.step(Input.Vlc(100, VlcEventKind.Vout))
        assertEquals(PlaybackState.Playing, c.playbackState)
        return c
    }

    private fun Step.connects() = commands.filterIsInstance<Command.Connect>()
    private fun Step.teardowns() = commands.filterIsInstance<Command.Teardown>()
    private fun Step.ticks() = commands.filterIsInstance<Command.ScheduleTick>()

    @Test
    fun `starts idle with no stream`() {
        val c = PlaybackController()
        assertEquals(PlaybackState.Idle, c.playbackState)
    }

    @Test
    fun `selecting a stream with a surface connects and schedules a fast tick`() {
        val c = PlaybackController()
        c.step(Input.SurfaceReady(0))
        val step = c.step(Input.StreamSelected(0, cam))

        assertEquals(PlaybackState.Connecting, c.playbackState)
        assertEquals(listOf("rtsps://h/a"), step.connects().map { it.url })
        assertEquals(listOf(2_000L), step.ticks().map { it.delayMs })
        assertIs<UiState.Connecting>(step.ui)
    }

    @Test
    fun `selecting a stream without a surface waits in Suspended`() {
        val c = PlaybackController()
        val step = c.step(Input.StreamSelected(0, cam))
        assertEquals(PlaybackState.Suspended, c.playbackState)
        assertTrue(step.connects().isEmpty())
    }

    @Test
    fun `a Vout event promotes Connecting to Playing on the slow tick`() {
        val c = PlaybackController()
        c.step(Input.SurfaceReady(0))
        c.step(Input.StreamSelected(0, cam))
        val step = c.step(Input.Vlc(100, VlcEventKind.Vout))

        assertEquals(PlaybackState.Playing, c.playbackState)
        assertEquals(listOf(10_000L), step.ticks().map { it.delayMs })
        assertEquals(UiState.Live("Front Door"), step.ui)
    }

    @Test
    fun `a first decoded frame on a tick also promotes to Playing`() {
        val c = PlaybackController()
        c.step(Input.SurfaceReady(0))
        c.step(Input.StreamSelected(0, cam))
        c.step(Input.Tick(2_000, sample(at = 2_000, decoded = 4)))
        assertEquals(PlaybackState.Playing, c.playbackState)
    }

    @Test
    fun `EncounteredError while playing tears down exactly once and backs off 2s`() {
        val c = playing()
        val step = c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))

        assertEquals(PlaybackState.Reconnecting, c.playbackState)
        assertEquals(1, step.teardowns().size)
        assertTrue(step.connects().isEmpty())
        assertEquals(listOf(2_000L), step.ticks().map { it.delayMs })
        assertEquals(1, c.reconnectCount)

        val ui = step.ui
        assertIs<UiState.Retrying>(ui)
        assertEquals(1, ui.attempt)
    }

    @Test
    fun `EndReached is treated as a failure, not a clean finish`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EndReached))
        assertEquals(PlaybackState.Reconnecting, c.playbackState)
    }

    @Test
    fun `a Stopped event during teardown is ignored`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.Stopped))
        assertEquals(PlaybackState.Playing, c.playbackState)
    }

    @Test
    fun `the retry tick reconnects once the backoff has elapsed`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        val step = c.step(Input.Tick(7_000, null))

        assertEquals(PlaybackState.Connecting, c.playbackState)
        assertEquals(listOf("rtsps://h/a"), step.connects().map { it.url })
    }

    @Test
    fun `a tick before the backoff elapses reschedules instead of connecting`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        val step = c.step(Input.Tick(6_000, null))

        assertEquals(PlaybackState.Reconnecting, c.playbackState)
        assertTrue(step.connects().isEmpty())
        assertEquals(listOf(1_000L), step.ticks().map { it.delayMs })
    }

    @Test
    fun `repeated failures climb the backoff ladder`() {
        val c = playing()
        val delays = mutableListOf<Long>()
        var t = 5_000L
        repeat(5) {
            val step = c.step(Input.Vlc(t, VlcEventKind.EncounteredError))
            delays += step.ticks().single().delayMs
            t += delays.last()
            c.step(Input.Tick(t, null))          // retry fires, back to Connecting
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), delays)
    }

    @Test
    fun `sixty seconds of healthy playback resets the ladder`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        c.step(Input.Tick(7_000, null))                       // reconnect
        c.step(Input.Vlc(7_100, VlcEventKind.Vout))           // live again at 7.1s

        var decoded = 10
        var t = 7_100L
        repeat(7) {                                            // 70s of good samples
            t += 10_000
            decoded += 250
            c.step(Input.Tick(t, sample(at = t, decoded = decoded)))
        }

        val step = c.step(Input.Vlc(t, VlcEventKind.EncounteredError))
        assertEquals(2_000L, step.ticks().single().delayMs)
    }

    private fun sample(at: Long, decoded: Int, playing: Boolean = true) = HealthSample(
        atMs = at,
        decodedVideo = decoded,
        displayedPictures = decoded,
        demuxReadBytes = decoded * 1_000,
        inputBitrate = 0.5f,
        isPlaying = playing,
        vlcTimeMs = 0,
    )
}
