package io.github.srliao.kioskplayer.core

import kotlinx.serialization.Serializable

@Serializable
data class StreamEntry(
    val id: String,
    val name: String?,
    val url: String,
) {
    /** What the UI shows. Never the raw null. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() } ?: "<no name>"
}
