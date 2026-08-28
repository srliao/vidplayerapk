package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class MaskingTest {

    @Test
    fun `keeps scheme and host, hides path`() {
        assertEquals(
            "rtsps://10.0.1.5:7441/***",
            maskUrl("rtsps://10.0.1.5:7441/abcDEF123?enableSrtp"),
        )
    }

    @Test
    fun `strips embedded credentials`() {
        assertEquals(
            "rtsp://cam.local/***",
            maskUrl("rtsp://admin:hunter2@cam.local/stream1"),
        )
    }

    @Test
    fun `handles a url with no path`() {
        assertEquals("rtsps://host/***", maskUrl("rtsps://host"))
    }

    @Test
    fun `returns a placeholder for junk input`() {
        assertEquals("***", maskUrl("not a url"))
    }
}
