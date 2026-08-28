package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamCodecTest {

    @Test
    fun `round-trips entries including a null name`() {
        val entries = listOf(
            StreamEntry("1", "Front Door", "rtsps://h/a"),
            StreamEntry("2", null, "rtsps://h/b?enableSrtp"),
        )
        assertEquals(entries, StreamCodec.decode(StreamCodec.encode(entries)))
    }

    @Test
    fun `round-trips names containing quotes and newlines`() {
        val entries = listOf(StreamEntry("1", "the \"back\" door\nupstairs", "rtsps://h/a"))
        assertEquals(entries, StreamCodec.decode(StreamCodec.encode(entries)))
    }

    @Test
    fun `decodes an empty list`() {
        assertEquals(emptyList(), StreamCodec.decode(StreamCodec.encode(emptyList())))
    }

    @Test
    fun `returns an empty list rather than throwing on corrupt input`() {
        assertEquals(emptyList(), StreamCodec.decode("{not json"))
    }
}
