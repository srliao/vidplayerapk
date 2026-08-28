package io.github.srliao.kioskplayer.core

import kotlinx.serialization.Serializable

enum class LogLevel { INFO, WARN, ERROR }

/** Never contains a stream URL. */
@Serializable
data class LogEvent(
    val atMs: Long,
    val level: LogLevel,
    val state: String,
    val message: String,
)
