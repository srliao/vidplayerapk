package io.github.srliao.kioskplayer.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An immutable view published by the main thread after every controller step
 * and read by the HTTP server thread. The URL is masked because this response
 * crosses the network, even though the setup screen shows it in the clear.
 */
@Serializable
data class DiagnosticsSnapshot(
    val state: String,
    val streamName: String,
    val maskedUrl: String,
    val uptimeMs: Long,
    val reconnectCount: Int,
    val backoffMs: Long,
    val lastSample: HealthSample?,
    val events: List<LogEvent>,
) {
    fun toJson(): String = JSON.encodeToString(this)

    companion object {
        private val JSON = Json { prettyPrint = true }

        val EMPTY = DiagnosticsSnapshot(
            state = "Idle",
            streamName = "<none>",
            maskedUrl = "***",
            uptimeMs = 0,
            reconnectCount = 0,
            backoffMs = 0,
            lastSample = null,
            events = emptyList(),
        )
    }
}
