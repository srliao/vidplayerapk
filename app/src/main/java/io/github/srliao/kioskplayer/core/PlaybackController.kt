package io.github.srliao.kioskplayer.core

enum class PlaybackState { Idle, Connecting, Playing, Reconnecting, Suspended }

enum class VlcEventKind { Playing, Vout, EncounteredError, EndReached, Stopped }

sealed interface Input {
    val nowMs: Long
    data class Tick(override val nowMs: Long, val sample: HealthSample?) : Input
    data class Vlc(override val nowMs: Long, val kind: VlcEventKind) : Input
    data class StreamSelected(override val nowMs: Long, val entry: StreamEntry?) : Input
    data class NetworkChanged(override val nowMs: Long, val available: Boolean) : Input
    data class SurfaceReady(override val nowMs: Long) : Input
    data class SurfaceGone(override val nowMs: Long) : Input
}

sealed interface Command {
    data object Teardown : Command
    data class Connect(val url: String) : Command
    data class ScheduleTick(val delayMs: Long) : Command
    data class Log(val event: LogEvent) : Command
}

sealed interface UiState {
    data object NoStream : UiState
    data class Connecting(val name: String) : UiState
    data class Live(val name: String) : UiState
    data class Retrying(
        val name: String,
        val attempt: Int,
        val nextRetryInMs: Long,
        val lastError: String?,
    ) : UiState
}

data class Step(val commands: List<Command>, val ui: UiState)

/**
 * Every decision the player makes. Pure: no Android, no VLC, no clock of its
 * own - the current time arrives on each [Input], and the controller asks to be
 * woken via [Command.ScheduleTick]. That is what makes the reconnect logic
 * testable on the JVM, which matters because there is no ADB in this project.
 */
class PlaybackController(
    private val connectTimeoutMs: Long = 15_000,
    private val connectingTickMs: Long = 2_000,
    private val playingTickMs: Long = 10_000,
    private val healthyResetMs: Long = 60_000,
    private val networkDeferTickMs: Long = 5_000,
    private val networkDownFailsafeMs: Long = 60_000,
    private val networkUpDelayMs: Long = 500,
    private val backoff: Backoff = Backoff(),
    private val stalls: StallDetector = StallDetector(),
) {
    var playbackState: PlaybackState = PlaybackState.Idle
        private set

    var stream: StreamEntry? = null
        private set

    var reconnectCount: Int = 0
        private set

    var lastSample: HealthSample? = null
        private set

    // Only meaningful while Reconnecting. retryAtMs is not cleared when a retry
    // is abandoned, so reporting it unconditionally served a stale countdown on
    // /stats while the stream was live.
    val backoffMs: Long
        get() = if (playbackState == PlaybackState.Reconnecting) {
            (retryAtMs - lastNowMs).coerceAtLeast(0)
        } else {
            0
        }

    private var surfaceReady = false
    private var connectStartedAtMs = 0L
    private var healthySinceMs = 0L
    private var retryAtMs = 0L
    private var lastError: String? = null
    private var lastNowMs = 0L
    private var networkAvailable = true
    private var networkDownSinceMs: Long? = null

    fun step(input: Input): Step {
        lastNowMs = input.nowMs
        val cmds = mutableListOf<Command>()

        when (input) {
            is Input.SurfaceReady -> {
                surfaceReady = true
                if (playbackState == PlaybackState.Suspended || playbackState == PlaybackState.Idle) {
                    startOrPark(input.nowMs, cmds)
                }
            }

            is Input.SurfaceGone -> {
                surfaceReady = false
                if (playbackState != PlaybackState.Idle) {
                    cmds += Command.Teardown
                    log(cmds, input.nowMs, LogLevel.INFO, "surface gone, suspending")
                    playbackState = PlaybackState.Suspended
                }
            }

            is Input.StreamSelected -> {
                stream = input.entry
                backoff.reset()
                stalls.reset()
                lastError = null
                if (input.entry == null) {
                    cmds += Command.Teardown
                    playbackState = PlaybackState.Idle
                } else {
                    startOrPark(input.nowMs, cmds)
                }
            }

            is Input.NetworkChanged -> handleNetwork(input, cmds)
            is Input.Vlc -> handleVlc(input, cmds)
            is Input.Tick -> handleTick(input, cmds)
        }

        return Step(cmds, ui())
    }

    private fun handleNetwork(input: Input.NetworkChanged, cmds: MutableList<Command>) {
        val changed = networkAvailable != input.available
        networkAvailable = input.available
        networkDownSinceMs = if (input.available) null else (networkDownSinceMs ?: input.nowMs)

        if (changed) {
            log(cmds, input.nowMs, LogLevel.INFO,
                "network ${if (input.available) "up" else "down"}")
        }

        // Bringing the retry forward is what meets the 30-second recovery target
        // after a router reboot.
        if (changed && input.available && playbackState == PlaybackState.Reconnecting) {
            retryAtMs = input.nowMs + networkUpDelayMs
            cmds += Command.ScheduleTick(networkUpDelayMs)
        }
    }

    private fun startOrPark(nowMs: Long, cmds: MutableList<Command>) {
        val s = stream
        when {
            s == null -> playbackState = PlaybackState.Idle
            !surfaceReady -> playbackState = PlaybackState.Suspended
            else -> connect(s, nowMs, cmds)
        }
    }

    private fun connect(s: StreamEntry, nowMs: Long, cmds: MutableList<Command>) {
        cmds += Command.Teardown
        cmds += Command.Connect(s.url)
        connectStartedAtMs = nowMs
        stalls.reset()
        playbackState = PlaybackState.Connecting
        log(cmds, nowMs, LogLevel.INFO, "connecting to ${s.displayName}")
        cmds += Command.ScheduleTick(connectingTickMs)
    }

    private fun handleVlc(input: Input.Vlc, cmds: MutableList<Command>) {
        when (input.kind) {
            VlcEventKind.Vout ->
                if (playbackState == PlaybackState.Connecting) goLive(input.nowMs, cmds)

            // Not a health signal on its own: it reports true on a frozen stream.
            VlcEventKind.Playing -> Unit

            // Expected during our own teardown.
            VlcEventKind.Stopped -> Unit

            VlcEventKind.EncounteredError -> fail("playback error", input.nowMs, cmds)
            VlcEventKind.EndReached -> fail("stream ended", input.nowMs, cmds)
        }
    }

    private fun goLive(nowMs: Long, cmds: MutableList<Command>) {
        playbackState = PlaybackState.Playing
        healthySinceMs = nowMs
        lastError = null
        stalls.reset()
        log(cmds, nowMs, LogLevel.INFO, "live")
        cmds += Command.ScheduleTick(playingTickMs)
    }

    private fun fail(reason: String, nowMs: Long, cmds: MutableList<Command>) {
        if (playbackState != PlaybackState.Connecting && playbackState != PlaybackState.Playing) return
        lastError = reason
        reconnectCount += 1
        cmds += Command.Teardown
        val delay = backoff.next()
        retryAtMs = nowMs + delay
        playbackState = PlaybackState.Reconnecting
        log(cmds, nowMs, LogLevel.WARN, "$reason; retry ${backoff.attempt} in ${delay}ms")
        cmds += Command.ScheduleTick(delay)
    }

    private fun handleTick(input: Input.Tick, cmds: MutableList<Command>) {
        input.sample?.let { lastSample = it }

        when (playbackState) {
            PlaybackState.Connecting -> {
                if ((input.sample?.decodedVideo ?: 0) > 0) {
                    goLive(input.nowMs, cmds)
                } else if (input.nowMs - connectStartedAtMs >= connectTimeoutMs) {
                    fail("connect timed out after ${connectTimeoutMs}ms", input.nowMs, cmds)
                } else {
                    cmds += Command.ScheduleTick(connectingTickMs)
                }
            }

            PlaybackState.Playing -> {
                val s = input.sample
                if (s != null && stalls.update(s)) {
                    stalls.reset()
                    fail("silent stall: decodedVideo frozen for ${stalls.threshold} samples",
                        input.nowMs, cmds)
                    return
                }
                if (backoff.attempt > 0 && input.nowMs - healthySinceMs >= healthyResetMs) {
                    backoff.reset()
                    log(cmds, input.nowMs, LogLevel.INFO, "healthy for 60s, backoff reset")
                }
                cmds += Command.ScheduleTick(playingTickMs)
            }

            PlaybackState.Reconnecting -> {
                if (input.nowMs < retryAtMs) {
                    cmds += Command.ScheduleTick(retryAtMs - input.nowMs)
                    return
                }

                // Connectivity callbacks lie in both directions. Deferring while
                // down keeps the log readable; the failsafe means a callback that
                // never fires cannot strand the panel offline forever.
                val downForMs = networkDownSinceMs?.let { input.nowMs - it } ?: 0L
                if (!networkAvailable && downForMs < networkDownFailsafeMs) {
                    log(cmds, input.nowMs, LogLevel.INFO, "network down, deferring retry")
                    // Move retryAtMs to the deferral poll so the panel shows a
                    // real countdown instead of "retrying in 0s" for the whole
                    // outage. A network-up edge still overrides this.
                    retryAtMs = input.nowMs + networkDeferTickMs
                    cmds += Command.ScheduleTick(networkDeferTickMs)
                    return
                }
                if (!networkAvailable) {
                    log(cmds, input.nowMs, LogLevel.WARN,
                        "network still reported down after ${downForMs}ms, attempting anyway")
                }

                val s = stream
                if (s == null) playbackState = PlaybackState.Idle else connect(s, input.nowMs, cmds)
            }

            PlaybackState.Idle, PlaybackState.Suspended -> Unit
        }
    }

    private fun ui(): UiState {
        val s = stream ?: return UiState.NoStream
        return when (playbackState) {
            PlaybackState.Idle -> UiState.NoStream
            PlaybackState.Suspended, PlaybackState.Connecting -> UiState.Connecting(s.displayName)
            PlaybackState.Playing -> UiState.Live(s.displayName)
            PlaybackState.Reconnecting ->
                UiState.Retrying(s.displayName, backoff.attempt, backoffMs, lastError)
        }
    }

    private fun log(cmds: MutableList<Command>, nowMs: Long, level: LogLevel, message: String) {
        cmds += Command.Log(LogEvent(nowMs, level, playbackState.name, message))
    }
}
