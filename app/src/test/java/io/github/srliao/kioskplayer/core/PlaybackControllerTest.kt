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

    /**
     * Just the media-lifecycle commands, in order. Every Connect must be
     * immediately preceded by a Teardown: without it VlcHost overwrites its
     * Media reference and leaks one native Media per reconnect.
     */
    private fun Step.lifecycle() = commands.mapNotNull {
        when (it) {
            is Command.Teardown -> "teardown"
            is Command.Connect -> "connect ${it.url}"
            else -> null
        }
    }

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
    fun `the initial connect tears down before it connects`() {
        val c = PlaybackController()
        c.step(Input.SurfaceReady(0))
        val step = c.step(Input.StreamSelected(0, cam))
        assertEquals(listOf("teardown", "connect rtsps://h/a"), step.lifecycle())
    }

    @Test
    fun `the retry tick tears down before it reconnects`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        val step = c.step(Input.Tick(7_000, null))
        assertEquals(listOf("teardown", "connect rtsps://h/a"), step.lifecycle())
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

    @Test
    fun `a silent stall tears down exactly once, not once per sample`() {
        val c = playing()
        var t = 100L
        val teardowns = mutableListOf<Int>()
        repeat(4) {
            t += 10_000
            teardowns += c.step(Input.Tick(t, sample(at = t, decoded = 500))).teardowns().size
        }
        assertEquals(listOf(0, 0, 0, 1), teardowns)
        assertEquals(PlaybackState.Reconnecting, c.playbackState)
        assertEquals(1, c.reconnectCount)
    }

    @Test
    fun `advancing frames never trigger a stall`() {
        val c = playing()
        var t = 100L
        var decoded = 0
        repeat(20) {
            t += 10_000
            decoded += 250
            c.step(Input.Tick(t, sample(at = t, decoded = decoded)))
        }
        assertEquals(PlaybackState.Playing, c.playbackState)
    }

    @Test
    fun `connecting times out after fifteen seconds with no frames`() {
        val c = PlaybackController()
        c.step(Input.SurfaceReady(0))
        c.step(Input.StreamSelected(0, cam))

        c.step(Input.Tick(14_000, sample(at = 14_000, decoded = 0)))
        assertEquals(PlaybackState.Connecting, c.playbackState)

        val step = c.step(Input.Tick(15_000, sample(at = 15_000, decoded = 0)))
        assertEquals(PlaybackState.Reconnecting, c.playbackState)
        assertEquals(1, step.teardowns().size)
    }

    @Test
    fun `a retry is deferred while the network is reported down`() {
        val c = playing()
        c.step(Input.NetworkChanged(1_000, available = false))
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))

        val step = c.step(Input.Tick(7_000, null))          // backoff has elapsed
        assertTrue(step.connects().isEmpty())
        assertEquals(PlaybackState.Reconnecting, c.playbackState)
        assertEquals(listOf(5_000L), step.ticks().map { it.delayMs })
    }

    @Test
    fun `the network coming back triggers an attempt within half a second`() {
        val c = playing()
        c.step(Input.NetworkChanged(1_000, available = false))
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        c.step(Input.Tick(7_000, null))                      // deferred

        val up = c.step(Input.NetworkChanged(20_000, available = true))
        assertEquals(listOf(500L), up.ticks().map { it.delayMs })

        val step = c.step(Input.Tick(20_500, null))
        assertEquals(listOf("rtsps://h/a"), step.connects().map { it.url })
    }

    @Test
    fun `the failsafe attempts anyway after sixty seconds of reported-down`() {
        val c = playing()
        c.step(Input.NetworkChanged(1_000, available = false))
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))

        c.step(Input.Tick(30_000, null))
        assertEquals(PlaybackState.Reconnecting, c.playbackState)

        val step = c.step(Input.Tick(61_001, null))          // 60s after down at 1_000
        assertEquals(listOf("rtsps://h/a"), step.connects().map { it.url })
    }

    @Test
    fun `a reported-up network never suppresses the stall watchdog`() {
        val c = playing()
        var t = 100L
        repeat(4) {
            t += 10_000
            c.step(Input.Tick(t, sample(at = t, decoded = 500)))
            c.step(Input.NetworkChanged(t, available = true))
        }
        assertEquals(PlaybackState.Reconnecting, c.playbackState)
    }

    @Test
    fun `switching streams mid-backoff cancels the retry and resets the ladder`() {
        val other = StreamEntry("b", "Driveway", "rtsps://h/b")
        val c = playing()
        var t = 5_000L
        repeat(3) {
            c.step(Input.Vlc(t, VlcEventKind.EncounteredError))
            t += 40_000                                   // well past any backoff
            c.step(Input.Tick(t, null))                   // retry fires
            t += 1_000
        }

        val step = c.step(Input.StreamSelected(t, other))
        assertEquals(listOf("rtsps://h/b"), step.connects().map { it.url })
        assertEquals(PlaybackState.Connecting, c.playbackState)

        val fail = c.step(Input.Vlc(t + 1_000, VlcEventKind.EncounteredError))
        assertEquals(2_000L, fail.ticks().single().delayMs)   // ladder was reset
    }

    @Test
    fun `surface loss suspends and surface return reconnects`() {
        val c = playing()
        val gone = c.step(Input.SurfaceGone(5_000))
        assertEquals(PlaybackState.Suspended, c.playbackState)
        assertEquals(1, gone.teardowns().size)

        val back = c.step(Input.SurfaceReady(9_000))
        assertEquals(PlaybackState.Connecting, c.playbackState)
        assertEquals(listOf("rtsps://h/a"), back.connects().map { it.url })
    }

    @Test
    fun `surface loss during backoff cancels cleanly`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        c.step(Input.SurfaceGone(5_500))
        assertEquals(PlaybackState.Suspended, c.playbackState)

        val tick = c.step(Input.Tick(8_000, null))
        assertTrue(tick.connects().isEmpty())
    }

    @Test
    fun `selecting no stream tears down and goes idle`() {
        val c = playing()
        val step = c.step(Input.StreamSelected(5_000, null))
        assertEquals(PlaybackState.Idle, c.playbackState)
        assertEquals(1, step.teardowns().size)
        assertEquals(UiState.NoStream, step.ui)
    }

    @Test
    fun `a second failure event while reconnecting is ignored`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        val step = c.step(Input.Vlc(5_001, VlcEventKind.EndReached))

        assertTrue(step.commands.isEmpty())
        assertEquals(1, c.reconnectCount)
        assertEquals(PlaybackState.Reconnecting, c.playbackState)
    }

    @Test
    fun `a redundant network-up event does not shortcut the ladder`() {
        val c = playing()
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))          // retryAt = 7_000
        val step = c.step(Input.NetworkChanged(5_500, available = true)) // already up: no-op
        assertTrue(step.commands.isEmpty())

        val tick = c.step(Input.Tick(6_100, null))
        assertTrue(tick.connects().isEmpty())
        assertEquals(PlaybackState.Reconnecting, c.playbackState)
    }

    @Test
    fun `a repeated down report does not reset the failsafe origin`() {
        val c = playing()
        c.step(Input.NetworkChanged(1_000, available = false))
        c.step(Input.Vlc(5_000, VlcEventKind.EncounteredError))
        c.step(Input.NetworkChanged(55_000, available = false))          // still down, re-reported

        val step = c.step(Input.Tick(61_001, null))                      // 60s past the 1_000 origin
        assertEquals(listOf("rtsps://h/a"), step.connects().map { it.url })
    }

    @Test
    fun `network events while playing do not reschedule the health tick`() {
        val c = playing()
        c.step(Input.NetworkChanged(4_000, available = false))
        val step = c.step(Input.NetworkChanged(5_000, available = true))
        assertTrue(step.ticks().isEmpty())
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
