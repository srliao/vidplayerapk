package io.github.srliao.kioskplayer

import android.content.Context
import io.github.srliao.kioskplayer.core.StreamCodec
import io.github.srliao.kioskplayer.core.StreamList

/**
 * The entire persistence layer. SharedPreferences with two keys - no Room, no
 * DataStore, no config file.
 */
class StreamStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): StreamList {
        val raw = prefs.getString(KEY_STREAMS, null) ?: return StreamList()
        return StreamList(StreamCodec.decode(raw), prefs.getString(KEY_CURRENT, null))
    }

    fun save(list: StreamList) {
        prefs.edit()
            .putString(KEY_STREAMS, StreamCodec.encode(list.entries))
            .putString(KEY_CURRENT, list.currentId)
            .apply()
    }

    private companion object {
        const val PREFS = "kiosk"
        const val KEY_STREAMS = "streams"
        const val KEY_CURRENT = "current_stream_id"
    }
}
