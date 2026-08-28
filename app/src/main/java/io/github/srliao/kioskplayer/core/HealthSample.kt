package io.github.srliao.kioskplayer.core

import kotlinx.serialization.Serializable

/**
 * One observation of player health.
 *
 * [decodedVideo] is the stall trigger. [vlcTimeMs] is recorded but never acted
 * on: it is unreliable on a live stream with no duration, and logging it lets
 * the 72-hour soak settle whether it ever advances on this hardware.
 */
@Serializable
data class HealthSample(
    val atMs: Long,
    val decodedVideo: Int,
    val displayedPictures: Int,
    val demuxReadBytes: Int,
    val inputBitrate: Float,
    val isPlaying: Boolean,
    val vlcTimeMs: Long,
)
