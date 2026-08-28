package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsBufferTest {

    private fun event(i: Int) = LogEvent(i.toLong(), LogLevel.INFO, "Playing", "event $i")

    @Test
    fun `keeps events in insertion order`() {
        val b = DiagnosticsBuffer(10)
        repeat(3) { b.add(event(it)) }
        assertEquals(listOf("event 0", "event 1", "event 2"), b.snapshot().map { it.message })
    }

    @Test
    fun `evicts the oldest events beyond capacity`() {
        val b = DiagnosticsBuffer(3)
        repeat(5) { b.add(event(it)) }
        assertEquals(listOf("event 2", "event 3", "event 4"), b.snapshot().map { it.message })
    }

    @Test
    fun `snapshot is a copy that later writes do not mutate`() {
        val b = DiagnosticsBuffer(10)
        b.add(event(0))
        val snap = b.snapshot()
        b.add(event(1))
        assertEquals(1, snap.size)
    }
}
