package io.github.srliao.kioskplayer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamListTest {

    private fun entry(id: String) = StreamEntry(id, "cam-$id", "rtsps://h/$id")

    @Test
    fun `display name falls back when name is null or blank`() {
        assertEquals("<no name>", StreamEntry("1", null, "u").displayName)
        assertEquals("<no name>", StreamEntry("1", "  ", "u").displayName)
        assertEquals("Front Door", StreamEntry("1", "Front Door", "u").displayName)
    }

    @Test
    fun `add appends and selects the first entry only`() {
        val list = StreamList().add(entry("a")).add(entry("b"))
        assertEquals(listOf("a", "b"), list.entries.map { it.id })
        assertEquals("a", list.currentId)
    }

    @Test
    fun `current falls back to the first entry when currentId is unknown`() {
        val list = StreamList(listOf(entry("a"), entry("b")), currentId = "ghost")
        assertEquals("a", list.current?.id)
    }

    @Test
    fun `current is null for an empty list`() {
        assertNull(StreamList().current)
    }

    @Test
    fun `removing the current entry selects the first remaining`() {
        val list = StreamList().add(entry("a")).add(entry("b")).remove("a")
        assertEquals(listOf("b"), list.entries.map { it.id })
        assertEquals("b", list.currentId)
    }

    @Test
    fun `removing a non-current entry leaves the selection alone`() {
        val list = StreamList().add(entry("a")).add(entry("b")).remove("b")
        assertEquals("a", list.currentId)
    }

    @Test
    fun `removing the last entry clears the selection`() {
        val list = StreamList().add(entry("a")).remove("a")
        assertEquals(emptyList(), list.entries)
        assertNull(list.currentId)
    }

    @Test
    fun `select only accepts an id that exists`() {
        val list = StreamList().add(entry("a")).add(entry("b"))
        assertEquals("b", list.select("b").currentId)
        assertEquals("a", list.select("ghost").currentId)
    }

    @Test
    fun `next advances in list order and wraps`() {
        var list = StreamList().add(entry("a")).add(entry("b")).add(entry("c"))
        list = list.next(); assertEquals("b", list.currentId)
        list = list.next(); assertEquals("c", list.currentId)
        list = list.next(); assertEquals("a", list.currentId)
    }

    @Test
    fun `next is a no-op below two entries`() {
        assertNull(StreamList().next().currentId)
        assertEquals("a", StreamList().add(entry("a")).next().currentId)
    }
}
