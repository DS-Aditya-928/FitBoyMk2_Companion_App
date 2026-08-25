package com.example.fitboymk2

class IdManager<T> {
    private val itemToId = mutableMapOf<T, Int>()
    private val idToItem = mutableMapOf<Int, T>()
    private val availableIds = ArrayDeque((1..255).toList())

    fun getId(input: T): Int? {
        itemToId[input]?.let { return it }
        val nextId = availableIds.removeFirstOrNull() ?: return null
        itemToId[input] = nextId
        idToItem[nextId] = input
        return nextId
    }

    fun releaseItem(input: T): Int? {
        val id = itemToId.remove(input)
        if (id != null) {
            idToItem.remove(id)
            availableIds.add(id)
        }
        return id
    }

    fun releaseId(input: Int): T? {
        val item = idToItem.remove(input)
        if (item != null) {
            itemToId.remove(item)
            availableIds.add(input)
        }
        return item
    }
}