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
 */
class StreamReceiverServer(
    private val port: Int = 8081,
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

        val requestLine = readLine(input) ?: return
        if (!requestLine.startsWith("POST /add ")) {
            return write(socket, 404, PushRequest.errorJson("Unknown endpoint"))
        }

        var contentLength = -1
        var headers = 0
        while (true) {
            if (++headers > MAX_HEADERS) {
                return write(socket, 400, PushRequest.errorJson("Too many headers"))
            }
            val header = readLine(input) ?: return
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
        413 -> "Payload Too Large"
        else -> "Bad Request"
    }

    /** Reads one CRLF-terminated line, bounded - a hostile client must not OOM the app. */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (sb.length < MAX_REQUEST_LINE) {
            val c = input.read()
            if (c == -1) return sb.takeIf { it.isNotEmpty() }?.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return null
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 5_000
        const val MAX_REQUEST_LINE = 8 * 1024
        const val MAX_HEADERS = 50
        const val MAX_BODY = 8 * 1024
        const val MAX_DRAIN = 64 * 1024
        const val ACCEPT_RETRY_DELAY_MS = 100L
    }
}
