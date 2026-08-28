package io.github.srliao.kioskplayer.core

/**
 * Written from the main thread, read from the diagnostics HTTP thread, so
 * every access is synchronized and [snapshot] hands back an immutable copy.
 */
class DiagnosticsBuffer(private val capacity: Int = 500) {

    private val events = ArrayDeque<LogEvent>()

    @Synchronized
    fun add(event: LogEvent) {
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<LogEvent> = events.toList()
}
