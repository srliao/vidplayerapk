package io.github.srliao.kioskplayer

import android.content.Context
import io.github.srliao.kioskplayer.core.DiagnosticsBuffer
import io.github.srliao.kioskplayer.core.DiagnosticsSnapshot
import io.github.srliao.kioskplayer.core.StreamEntry
import io.github.srliao.kioskplayer.core.StreamList
import io.github.srliao.kioskplayer.core.UiState
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.videolan.libvlc.util.VLCVideoLayout

/**
 * The singleton object graph. Owns the stream list and the diagnostics
 * snapshot; delegates all playback decisions to VlcHost's controller.
 */
class Kiosk(appContext: Context) {

    val diagnostics = DiagnosticsBuffer()

    private val store = StreamStore(appContext)
    private val snapshotRef = AtomicReference(DiagnosticsSnapshot.EMPTY)

    private val vlc = VlcHost(
        context = appContext,
        diagnostics = diagnostics,
        onUiState = { state -> onUiState?.invoke(state) },
        onSnapshot = { snapshot -> snapshotRef.set(snapshot) },
    )

    var onUiState: ((UiState) -> Unit)? = null

    var streams: StreamList = store.load()
        private set

    fun snapshotJson(): String = snapshotRef.get().toJson()

    fun add(name: String?, url: String) {
        val entry = StreamEntry(UUID.randomUUID().toString(), name?.trim()?.ifBlank { null }, url)
        val wasEmpty = streams.entries.isEmpty()
        update(streams.add(entry))
        if (wasEmpty) play()
    }

    fun remove(id: String) {
        val wasCurrent = streams.current?.id == id
        update(streams.remove(id))
        if (wasCurrent) play()
    }

    fun select(id: String) {
        if (streams.current?.id == id) return
        update(streams.select(id))
        play()
    }

    fun next() {
        update(streams.next())
        play()
    }

    private fun update(list: StreamList) {
        streams = list
        store.save(list)
    }

    fun attach(layout: VLCVideoLayout) {
        // play() first: surfaceReady is still false, so the controller parks in
        // Suspended and emits nothing. The SurfaceReady from attach() then drives
        // a single connect - and still after attachViews(), so surface ordering
        // is preserved. Calling attach() first would connect twice per screen-on.
        play()
        vlc.attach(layout)
    }

    fun detach() = vlc.detach()

    fun onNetworkChanged(available: Boolean) = vlc.onNetworkChanged(available)

    private fun play() = vlc.select(streams.current)
}
