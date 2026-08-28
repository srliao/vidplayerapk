package io.github.srliao.kioskplayer

import java.io.IOException
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
        running = true
        val socket = ServerSocket(port)
        serverSocket = socket
        thread = Thread {
            while (running) {
                try {
                    socket.accept().use(::respond)
                } catch (e: IOException) {
                    if (!running) break
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
        val requestLine = socket.getInputStream().bufferedReader().readLine() ?: return
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
}
