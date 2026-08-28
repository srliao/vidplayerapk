package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BackoffTest {

    @Test
    fun `climbs 2 4 8 16 then holds at the 30s cap`() {
        val b = Backoff()
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (1..6).map { b.next() })
    }

    @Test
    fun `attempt tracks the number of retries`() {
        val b = Backoff()
        assertEquals(0, b.attempt)
        b.next(); b.next()
        assertEquals(2, b.attempt)
    }

    @Test
    fun `reset returns to the base delay`() {
        val b = Backoff()
        repeat(5) { b.next() }
        b.reset()
        assertEquals(0, b.attempt)
        assertEquals(2_000L, b.next())
    }

    @Test
    fun `never overflows after a very long outage`() {
        val b = Backoff()
        repeat(200) { b.next() }
        assertEquals(30_000L, b.next())
    }
}
