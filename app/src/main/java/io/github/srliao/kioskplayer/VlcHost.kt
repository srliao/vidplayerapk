package io.github.srliao.kioskplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.srliao.kioskplayer.core.Command
import io.github.srliao.kioskplayer.core.DiagnosticsBuffer
import io.github.srliao.kioskplayer.core.DiagnosticsSnapshot
import io.github.srliao.kioskplayer.core.HealthSample
import io.github.srliao.kioskplayer.core.Input
import io.github.srliao.kioskplayer.core.LogEvent
import io.github.srliao.kioskplayer.core.LogLevel
import io.github.srliao.kioskplayer.core.PlaybackController
import io.github.srliao.kioskplayer.core.StreamEntry
import io.github.srliao.kioskplayer.core.UiState
import io.github.srliao.kioskplayer.core.VlcEventKind
import io.github.srliao.kioskplayer.core.maskUrl
import org.videolan.libvlc.Dialog
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

class VlcHost(
    context: Context,
    private val diagnostics: DiagnosticsBuffer,
    private val onUiState: (UiState) -> Unit,
    private val onSnapshot: (DiagnosticsSnapshot) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val controller = PlaybackController()
    private val startedAtMs = SystemClock.elapsedRealtime()

    private val libVlc = LibVLC(
        context,
        arrayListOf(
            "--rtsp-tcp",                     // UDP over Wi-Fi artifacts badly
            "--no-audio",
            "--drop-late-frames",
            "--skip-frames",
            "--network-caching=1000",         // 300 drops frames instead of riding out jitter
            "--rtsp-frame-buffer-size=500000" // live555 defaults to 250k; 4K H.265 keyframes overrun it
        ),
    )

    private val player = MediaPlayer(libVlc)
    private var media: Media? = null
    private var attachedLayout: VLCVideoLayout? = null

    private val tickRunnable = Runnable { dispatch(Input.Tick(now(), sample())) }

    init {
        // Without this libVLC blocks on a modal trust dialog nobody can answer
        // on a wall panel, and the app looks like it has hung.
        Dialog.setCallbacks(libVlc, object : Dialog.Callbacks {
            override fun onDisplay(dialog: Dialog.QuestionDialog) {
                // Two dialogs in sequence: the first offers only "View certificate"
                // (action 1); the second offers "Accept 24 hours" (1) and
                // "Accept permanently" (2). Prefer permanent when it is offered.
                dialog.postAction(if (dialog.action2Text != null) 2 else 1)
            }

            override fun onDisplay(dialog: Dialog.ErrorMessage) {
                // Log only. An error *dialog* is not necessarily a playback
                // failure, and MediaPlayer.Event.EncounteredError already covers
                // the real ones - dispatching here too would double-teardown.
                // Deliberately fixed text: the title comes from libVLC, and this
                // buffer is published unauthenticated over HTTP.
                diagnostics.add(
                    LogEvent(now(), LogLevel.ERROR, "vlc", "libvlc reported an error dialog")
                )
            }

            override fun onDisplay(dialog: Dialog.LoginDialog) = dialog.dismiss()
            override fun onDisplay(dialog: Dialog.ProgressDialog) = Unit
            override fun onCanceled(dialog: Dialog?) = Unit
            override fun onProgressUpdate(dialog: Dialog.ProgressDialog) = Unit
        })

        // libvlc-all 3.7.5 exposes only the single-argument setEventListener publicly; the
        // Handler overload is protected on VLCObject, so events arrive on a libVLC thread.
        // Map the event to a plain enum synchronously — AbstractVLCEvent.release() means it
        // must not outlive this callback — then hop to the main looper ourselves. The state
        // machine still never needs a lock. Posting also removes a re-entrancy hazard:
        // player.stop() inside teardown() can fire Stopped synchronously, and inline
        // delivery would re-enter dispatch() in the middle of its command loop.
        player.setEventListener(
            MediaPlayer.EventListener { event ->
                val kind = eventKind(event)
                if (kind != null) {
                    val atMs = now()
                    handler.post { dispatch(Input.Vlc(atMs, kind)) }
                }
            },
        )
    }

    fun attach(layout: VLCVideoLayout) {
        attachedLayout = layout
        player.attachViews(layout, null, false, false)   // SurfaceView, no subtitles
        dispatch(Input.SurfaceReady(now()))
    }

    fun detach() {
        dispatch(Input.SurfaceGone(now()))
        player.detachViews()
        attachedLayout = null
    }

    fun select(entry: StreamEntry?) = dispatch(Input.StreamSelected(now(), entry))

    // Delivered from a ConnectivityManager callback, which arrives on a framework
    // background thread. Hop to the main looper for the same reason the VLC event
    // listener does: the controller is lock-free and must only ever run on main.
    fun onNetworkChanged(available: Boolean) {
        val atMs = now()
        handler.post { dispatch(Input.NetworkChanged(atMs, available)) }
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        teardown()
        player.release()
        libVlc.release()
    }

    private fun now() = SystemClock.elapsedRealtime()

    /** Reads only primitives: the event must not outlive the callback. */
    private fun eventKind(event: MediaPlayer.Event): VlcEventKind? = when (event.type) {
        MediaPlayer.Event.Vout -> if (event.voutCount > 0) VlcEventKind.Vout else null
        MediaPlayer.Event.Playing -> VlcEventKind.Playing
        MediaPlayer.Event.Stopped -> VlcEventKind.Stopped
        MediaPlayer.Event.EndReached -> VlcEventKind.EndReached
        MediaPlayer.Event.EncounteredError -> VlcEventKind.EncounteredError
        else -> null
    }

    private fun sample(): HealthSample? {
        val m = media ?: return null
        if (m.isReleased) return null
        val stats = m.stats ?: return null
        return HealthSample(
            atMs = now(),
            decodedVideo = stats.decodedVideo,
            displayedPictures = stats.displayedPictures,
            demuxReadBytes = stats.demuxReadBytes,
            inputBitrate = stats.inputBitrate.takeIf { it.isFinite() } ?: 0f,
            isPlaying = player.isPlaying,
            vlcTimeMs = player.time,
        )
    }

    private fun dispatch(input: Input) {
        val step = controller.step(input)
        for (command in step.commands) {
            when (command) {
                is Command.Teardown -> teardown()
                is Command.Connect -> connect(command.url)
                is Command.ScheduleTick -> {
                    handler.removeCallbacks(tickRunnable)
                    handler.postDelayed(tickRunnable, command.delayMs)
                }
                is Command.Log -> diagnostics.add(command.event)
            }
        }
        onUiState(step.ui)
        onSnapshot(buildSnapshot())
    }

    private fun connect(url: String) {
        // A fresh Media every time. Reusing one across reconnects leaks and
        // eventually wedges the player.
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)   // force=false so software decode can take over
        player.media = m                     // the player retains its own reference
        media = m                            // ours, released in teardown()
        player.play()
    }

    private fun teardown() {
        player.stop()
        media?.release()
        media = null
    }

    private fun buildSnapshot(): DiagnosticsSnapshot {
        val stream = controller.stream
        return DiagnosticsSnapshot(
            state = controller.playbackState.name,
            streamName = stream?.displayName ?: "<none>",
            maskedUrl = stream?.let { maskUrl(it.url) } ?: "***",
            uptimeMs = now() - startedAtMs,
            reconnectCount = controller.reconnectCount,
            backoffMs = controller.backoffMs,
            lastSample = controller.lastSample,
            events = diagnostics.snapshot(),
        )
    }
}
