package io.github.srliao.kioskplayer

import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * One endpoint, no dependency. Only alive while DiagnosticsActivity is
 * resumed - a kiosk should not hold a listening socket during normal use.
 *
 * [snapshot] must be safe to call from this thread; it reads an
 * AtomicReference published by the main thread, never live controller state.
 */
class DiagnosticsServer(
    private val port: Int = 8080,
    private val snapshot: () -> String,
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        val socket = ServerSocket(port)
        serverSocket = socket
        running = true
        thread = Thread {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: IOException) {
                    // An intentional stop() closes the socket to break accept().
                    if (!running || socket.isClosed) break
                    // Anything else may be transient (e.g. descriptor exhaustion).
                    // Back off so a persistent failure cannot pin a core on a
                    // battery-powered panel.
                    runCatching { Thread.sleep(ACCEPT_RETRY_DELAY_MS) }
                    continue
                }
                try {
                    client.use(::respond)
                } catch (e: Throwable) {
                    // A stalled or misbehaving client (including a read
                    // timeout from soTimeout) must not kill the accept loop -
                    // just drop this connection and move on. Deliberately wider
                    // than IOException: snapshot() can throw a
                    // SerializationException, and letting that escape would
                    // silently kill this thread for the rest of the session
                    // with running still true.
                }
            }
        }.apply {
            isDaemon = true
            name = "diagnostics-http"
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        thread = null
    }

    private fun respond(socket: Socket) {
        // A stalled client must not wedge this single-threaded endpoint.
        socket.soTimeout = REQUEST_TIMEOUT_MS
        val requestLine = readRequestLine(socket.getInputStream()) ?: return
        val ok = requestLine.startsWith("GET /stats")
        val body = if (ok) snapshot() else """{"error":"not found"}"""
        val status = if (ok) "200 OK" else "404 Not Found"
        val bytes = body.toByteArray()

        socket.getOutputStream().apply {
            write(
                buildString {
                    append("HTTP/1.1 ").append(status).append("\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray()
            )
            write(bytes)
            flush()
        }
    }

    /** Reads one request line, bounded — a hostile client must not be able to OOM the app. */
    private fun readRequestLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_REQUEST_LINE) {
            val c = input.read()
            if (c == -1) return sb.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null // over the cap: treat as a bad request and close
    }

    private companion object {
        /** A stalled client must not wedge the single-threaded accept loop past this. */
        const val REQUEST_TIMEOUT_MS = 5_000

        /** A hostile client sending an unterminated line must not exhaust memory. */
        const val MAX_REQUEST_LINE = 8 * 1024

        /** Backoff after a non-stop IOException from accept(), so a persistent
         *  failure (e.g. descriptor exhaustion) cannot pin a core. */
        const val ACCEPT_RETRY_DELAY_MS = 100L
    }
}
