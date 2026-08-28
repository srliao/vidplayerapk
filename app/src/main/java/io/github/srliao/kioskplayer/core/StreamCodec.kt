package io.github.srliao.kioskplayer.core

import kotlinx.serialization.json.Json

/**
 * kotlinx.serialization rather than org.json: core must stay free of Android
 * imports, and org.json is stubbed to throw in JVM unit tests.
 */
object StreamCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(entries: List<StreamEntry>): String = json.encodeToString(entries)

    /** Corrupt storage must not brick the app; it degrades to an empty list. */
    fun decode(raw: String): List<StreamEntry> =
        runCatching { json.decodeFromString<List<StreamEntry>>(raw) }.getOrDefault(emptyList())
}
