package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PushRequestTest {

    private fun accepted(body: String) = PushRequest.evaluate(body) as? PushResult.Accepted
    private fun rejected(body: String) = PushRequest.evaluate(body) as? PushResult.Rejected

    @Test
    fun `accepts a url with a name`() {
        val result = accepted("""{"url":"rtsps://h:7441/s","name":"Front Door"}""")
        assertEquals("rtsps://h:7441/s", result?.url)
        assertEquals("Front Door", result?.name)
    }

    @Test
    fun `accepts a url with no name field at all`() {
        val result = accepted("""{"url":"rtsps://h/s"}""")
        assertEquals("rtsps://h/s", result?.url)
        assertEquals(null, result?.name)
    }

    @Test
    fun `normalises a blank name to null`() {
        assertEquals(null, accepted("""{"url":"rtsps://h/s","name":"   "}""")?.name)
        assertEquals(null, accepted("""{"url":"rtsps://h/s","name":""}""")?.name)
    }

    @Test
    fun `trims whitespace around a name`() {
        assertEquals("Back Yard", accepted("""{"url":"rtsps://h/s","name":"  Back Yard  "}""")?.name)
    }

    @Test
    fun `trims the url through the validator`() {
        assertEquals("rtsps://h/s", accepted("""{"url":"  rtsps://h/s  "}""")?.url)
    }

    @Test
    fun `ignores unknown fields`() {
        assertEquals("rtsps://h/s", accepted("""{"url":"rtsps://h/s","future":true}""")?.url)
    }

    @Test
    fun `rejects a missing url field as 400`() {
        val result = rejected("""{"name":"Front Door"}""")
        assertEquals(400, result?.status)
    }

    @Test
    fun `rejects malformed json as 400`() {
        assertEquals(400, rejected("""{"url":""")?.status)
        assertEquals(400, rejected("")?.status)
    }

    @Test
    fun `rejects a json array as 400`() {
        assertEquals(400, rejected("""["rtsps://h/s"]""")?.status)
    }

    @Test
    fun `passes the validator reason through verbatim`() {
        val result = rejected("""{"url":"http://h/s"}""")
        assertEquals(400, result?.status)
        assertEquals("Scheme must be rtsp:// or rtsps://, not http://", result?.reason)
    }

    @Test
    fun `ok json carries the name`() {
        assertEquals("""{"ok":true,"name":"Front Door"}""", PushRequest.okJson("Front Door"))
    }

    @Test
    fun `ok json omits a null name`() {
        assertEquals("""{"ok":true}""", PushRequest.okJson(null))
    }

    @Test
    fun `error json escapes quotes and backslashes in the reason`() {
        // A rejection reason embeds user input, so unescaped JSON assembly
        // would let a crafted scheme break the response body.
        val json = PushRequest.errorJson("bad \"scheme\" \\x")
        assertEquals("""{"ok":false,"error":"bad \"scheme\" \\x"}""", json)
    }
}
