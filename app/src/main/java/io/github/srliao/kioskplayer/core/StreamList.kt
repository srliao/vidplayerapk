package io.github.srliao.kioskplayer.core

/**
 * Insertion-ordered and never reordered by playback, so a camera's position on
 * the setup screen is stable enough to become muscle memory on a wall panel.
 */
data class StreamList(
    val entries: List<StreamEntry> = emptyList(),
    val currentId: String? = null,
) {
    val current: StreamEntry?
        get() = entries.firstOrNull { it.id == currentId } ?: entries.firstOrNull()

    fun add(entry: StreamEntry): StreamList =
        copy(entries = entries + entry, currentId = currentId ?: entry.id)

    fun remove(id: String): StreamList {
        val remaining = entries.filterNot { it.id == id }
        val newCurrent = when {
            remaining.isEmpty() -> null
            currentId == id -> remaining.first().id
            else -> currentId
        }
        return StreamList(remaining, newCurrent)
    }

    fun select(id: String): StreamList =
        if (entries.any { it.id == id }) copy(currentId = id) else this

    fun next(): StreamList {
        if (entries.size < 2) return this
        val i = entries.indexOfFirst { it.id == current?.id }
        return copy(currentId = entries[(i + 1) % entries.size].id)
    }
}
