package io.github.srliao.kioskplayer

import io.github.srliao.kioskplayer.core.PushRequest
import io.github.srliao.kioskplayer.core.PushResult
import java.io.IOException
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Accepts a stream pushed from a computer on the same network. A sibling of
 * DiagnosticsServer: one endpoint, no dependency, alive only while
 * SetupActivity's Receive panel is open.
 *
 * Deliberately unauthenticated - see NOTICE in the README. The listener exists
 * only while a person is standing at the Setup screen.
 *
 * [onStream] runs on the server thread. Callers must hop to the main thread.
 *
 * [streamCount] must be safe to call from this thread; like DiagnosticsServer's
 * [DiagnosticsServer.snapshot], it reads a value published by the main thread
 * (Kiosk.streams is a plain var, unsafe to read from the socket thread) rather
 * than live controller state.
 */
class StreamReceiverServer(
    private val port: Int = 8081,
    private val streamCount: () -> Int,
    private val onStream: (name: String?, url: String) -> Unit,
) {
    @Volatile private var running = false
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    /** The port actually bound, which differs from [port] when it is 0 (tests). */
    val boundPort: Int get() = serverSocket?.localPort ?: -1

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
                    runCatching { Thread.sleep(ACCEPT_RETRY_DELAY_MS) }
                    continue
                }
                try {
                    client.use(::respond)
                } catch (e: Throwable) {
                    // A stalled or hostile client must not kill the accept loop.
                    // Wider than IOException on purpose: a serialization failure
                    // escaping here would silently end the thread with running
                    // still true, and the panel would sit there lying.
                }
            }
        }.apply {
            isDaemon = true
            name = "stream-receiver-http"
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
        socket.soTimeout = REQUEST_TIMEOUT_MS
        val input = socket.getInputStream()

        val requestLine = try {
            // A null here with nothing else means the client never sent a byte
            // (a bare connect, e.g. a port scanner) - there is no request to
            // answer, so staying silent is correct.
            readLine(input) ?: return
        } catch (e: LineTooLongException) {
            // Unlike the case above, bytes did arrive - a request was begun,
            // just never finished - so the client gets a 400, not a dropped
            // connection that pushstream.py would misreport as nothing listening.
            return write(socket, 400, PushRequest.errorJson("Request line too long"))
        }
        if (!requestLine.startsWith("POST /add ")) {
            return write(socket, 404, PushRequest.errorJson("Unknown endpoint"))
        }

        var contentLength = -1
        var headers = 0
        while (true) {
            if (++headers > MAX_HEADERS) {
                return write(socket, 400, PushRequest.errorJson("Too many headers"))
            }
            val header = try {
                // By this point the request line already parsed, so a request is
                // in flight; any failure here - EOF or an over-cap line - is the
                // client stopping short mid-headers, not a bare connect, and gets
                // a 400 for the same reason as above.
                readLine(input) ?: return write(socket, 400, PushRequest.errorJson("Incomplete request"))
            } catch (e: LineTooLongException) {
                return write(socket, 400, PushRequest.errorJson("Header line too long"))
            }
            if (header.isEmpty()) break
            if (header.substringBefore(':').trim().lowercase() == "content-length") {
                contentLength = header.substringAfter(':').trim().toIntOrNull() ?: -1
            }
        }

        if (contentLength < 0) {
            return write(socket, 400, PushRequest.errorJson("Missing Content-Length"))
        }
        if (contentLength > MAX_BODY) {
            // Drain the body the client is still writing before replying, so
            // closing the socket afterward doesn't race the client's write and
            // surface as a connection reset instead of a readable 413. Only
            // drain up to MAX_DRAIN - an unbounded drain here would itself be a
            // denial-of-service hole; soTimeout already bounds the read.
            if (contentLength <= MAX_DRAIN) {
                drain(input, contentLength)
            }
            return write(socket, 413, PushRequest.errorJson("Request too large"))
        }

        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        if (read < contentLength) {
            return write(socket, 400, PushRequest.errorJson("Truncated body"))
        }

        when (val result = PushRequest.evaluate(String(body, Charsets.UTF_8))) {
            is PushResult.Rejected -> write(socket, result.status, PushRequest.errorJson(result.reason))
            is PushResult.Accepted -> {
                // Checked here, not in PushRequest: an unauthenticated endpoint plus an
                // unbounded list is a loop away from wedging the Setup screen's main
                // thread (renderList() and StreamStore.save are both O(n) per push).
                if (streamCount() >= MAX_STREAMS) {
                    return write(socket, 409, PushRequest.errorJson("Stream list is full"))
                }
                onStream(result.name, result.url)
                write(socket, 200, PushRequest.okJson(result.name))
            }
        }
    }

    /** Reads and discards exactly [length] bytes, stopping early if the stream ends. */
    private fun drain(input: InputStream, length: Int) {
        val buffer = ByteArray(minOf(length, MAX_DRAIN))
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(buffer.size, remaining))
            if (n < 0) break
            remaining -= n
        }
    }

    private fun write(socket: Socket, status: Int, body: String) {
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write(
                buildString {
                    append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
                    append("Content-Type: application/json\r\n")
                    append("Content-Length: ").append(bytes.size).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }.toByteArray()
            )
            write(bytes)
            flush()
        }
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        404 -> "Not Found"
        409 -> "Conflict"
        413 -> "Payload Too Large"
        else -> "Bad Request"
    }

    /**
     * Reads one CRLF-terminated line, bounded - a hostile client must not OOM the
     * app. Returns null only for a clean EOF with nothing read for this line yet;
     * throws [LineTooLongException] when the line was begun but exceeds the cap
     * without a terminator, so callers can tell "no request" from "request cut off".
     */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_REQUEST_LINE) {
            val c = input.read()
            if (c == -1) return sb.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        throw LineTooLongException()
    }

    /** Signals an over-cap line to [respond], distinct from a clean close. */
    private class LineTooLongException : Exception()

    private companion object {
        const val REQUEST_TIMEOUT_MS = 5_000
        const val MAX_REQUEST_LINE = 8 * 1024
        const val MAX_HEADERS = 50
        const val MAX_BODY = 8 * 1024
        const val MAX_DRAIN = 64 * 1024
        const val ACCEPT_RETRY_DELAY_MS = 100L

        // A wall panel shows one camera at a time; 64 rows is already an absurd
        // Setup screen. The cap exists only to bound the damage an unauthenticated,
        // looping pusher can do to the main thread and the persisted list.
        const val MAX_STREAMS = 64
    }
}
