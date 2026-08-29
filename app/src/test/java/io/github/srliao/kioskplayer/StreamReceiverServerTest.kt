package io.github.srliao.kioskplayer

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreamReceiverServerTest {

    private val received = ArrayBlockingQueue<Pair<String?, String>>(8)
    private val server = StreamReceiverServer(port = 0, streamCount = { 0 }) { name, url ->
        received.put(Pair(name, url))
    }

    private fun startServer(): Int {
        server.start()
        return server.boundPort
    }

    private fun poll() = received.poll(2, TimeUnit.SECONDS)

    /** Returns status to body. */
    private fun post(port: Int, body: String): Pair<Int, String> {
        val conn = URL("http://127.0.0.1:$port/add").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        // Not setting a streaming mode makes HttpURLConnection buffer the body
        // and send a real Content-Length, which is what the server requires.
        conn.setRequestProperty("Content-Type", "application/json")
        OutputStreamWriter(conn.outputStream).use { it.write(body) }
        val status = conn.responseCode
        val text = (conn.errorStream ?: conn.inputStream).bufferedReader().use { it.readText() }
        conn.disconnect()
        return Pair(status, text)
    }

    @AfterTest
    fun tearDown() = server.stop()

    @Test
    fun `accepts a valid post and fires the callback`() {
        val port = startServer()
        val (status, body) = post(port, """{"url":"rtsps://h:7441/s","name":"Front Door"}""")
        assertEquals(200, status)
        assertTrue(body.contains("\"ok\":true"), body)
        assertEquals(Pair("Front Door", "rtsps://h:7441/s"), poll())
    }

    @Test
    fun `rejects an invalid url without firing the callback`() {
        val port = startServer()
        val (status, body) = post(port, """{"url":"http://h/s"}""")
        assertEquals(400, status)
        assertTrue(body.contains("Scheme must be"), body)
        assertNull(poll())
    }

    @Test
    fun `returns 404 for any other path`() {
        val port = startServer()
        val conn = URL("http://127.0.0.1:$port/stats").openConnection() as HttpURLConnection
        assertEquals(404, conn.responseCode)
        conn.disconnect()
        assertNull(poll())
    }

    @Test
    fun `rejects an over-cap body with 413 and no callback`() {
        val port = startServer()
        val huge = "x".repeat(9 * 1024)
        val (status, _) = post(port, """{"url":"rtsps://h/s","name":"$huge"}""")
        assertEquals(413, status)
        assertNull(poll())
    }

    @Test
    fun `an unterminated request does not wedge the accept loop`() {
        val port = startServer()
        // Open a socket, send a partial request line, never finish it. The
        // server's soTimeout must drop it rather than block the single accept
        // loop forever.
        Socket("127.0.0.1", port).use { rude ->
            rude.getOutputStream().write("POST /add HTTP".toByteArray())
            rude.getOutputStream().flush()

            val (status, _) = post(port, """{"url":"rtsps://h/s"}""")
            assertEquals(200, status)
            assertEquals(Pair(null, "rtsps://h/s"), poll())
        }
    }

    @Test
    fun `rejects a push at the cap with 409 and no callback`() {
        // A separate server whose supplier always reports the list as full -
        // proves the cap is enforced here, in the socket layer, not in
        // PushRequest, whose own contract and tests stay untouched.
        val cappedReceived = ArrayBlockingQueue<Pair<String?, String>>(8)
        val capped = StreamReceiverServer(port = 0, streamCount = { 64 }) { name, url ->
            cappedReceived.put(Pair(name, url))
        }
        capped.start()
        try {
            val (status, body) = post(capped.boundPort, """{"url":"rtsps://h/s"}""")
            assertEquals(409, status)
            assertTrue(body.contains("Stream list is full"), body)
            assertNull(cappedReceived.poll(2, TimeUnit.SECONDS))
        } finally {
            capped.stop()
        }
    }

    @Test
    fun `stop releases the port`() {
        val port = startServer()
        server.stop()
        val reopened = java.net.ServerSocket(port)
        reopened.close()
    }
}
