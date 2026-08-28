package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UrlValidatorTest {

    private fun valid(s: String) = UrlValidator.check(s) as? UrlCheck.Valid

    @Test
    fun `accepts rtsp and rtsps`() {
        assertEquals("rtsp://h/s", valid("rtsp://h/s")?.url)
        assertEquals("rtsps://h/s", valid("rtsps://h/s")?.url)
    }

    @Test
    fun `accepts uppercase scheme`() {
        assertEquals("RTSPS://h/s", valid("RTSPS://h/s")?.url)
    }

    @Test
    fun `trims surrounding whitespace`() {
        assertEquals("rtsps://h/s", valid("  rtsps://h/s\n")?.url)
    }

    @Test
    fun `preserves query strings such as enableSrtp`() {
        assertEquals("rtsps://h:7441/x?enableSrtp", valid("rtsps://h:7441/x?enableSrtp")?.url)
    }

    @Test
    fun `accepts embedded credentials`() {
        assertEquals("rtsp://u:p@h/s", valid("rtsp://u:p@h/s")?.url)
    }

    @Test
    fun `rejects empty input`() {
        assertTrue(UrlValidator.check("   ") is UrlCheck.Invalid)
    }

    @Test
    fun `rejects a non-rtsp scheme`() {
        val r = UrlValidator.check("http://h/s")
        assertTrue(r is UrlCheck.Invalid)
        assertTrue((r as UrlCheck.Invalid).reason.contains("rtsp"))
    }

    @Test
    fun `rejects input with no scheme separator`() {
        assertTrue(UrlValidator.check("10.0.1.5/stream") is UrlCheck.Invalid)
    }

    @Test
    fun `rejects a url with no host`() {
        assertTrue(UrlValidator.check("rtsps://") is UrlCheck.Invalid)
        assertTrue(UrlValidator.check("rtsps:///path") is UrlCheck.Invalid)
    }
}
