package com.meenai.mina.core.vision

import java.util.UUID

/** 0–999 Normalized coordinates, the same coordinate system as tap. */
data class Point(val x: Int, val y: Int)

/**
 * find_visually found points: press (ref, snapshotId) to register, leaving only the most recent
 * [capacity] (first in, first out), record the hash of the screenshot when found, and use this to
 * determine whether the screen has changed when tapping. Any write operation is followed by the caller
 * [clear].
 */
class LocateRegistry(private val capacity: Int = 20) {
    private class Entry(val point: Point, val hash: Long)

    private var next = 1
    private val entries = LinkedHashMap<String, Entry>()

    /** Returns (ref, snapshotId), in the form of ("@v3", "loc_..."). The number is not reused within a mission. */
    fun put(x: Int, y: Int, hash: Long): Pair<String, String> {
        val ref = "@v${next++}"
        val snapshotId = "loc_${UUID.randomUUID()}"
        entries[key(ref, snapshotId)] = Entry(Point(x, y), hash)
        while (entries.size > capacity) entries.remove(entries.keys.first())
        return ref to snapshotId
    }

    fun get(ref: String, snapshotId: String): Pair<Point, Long>? = entries[key(ref, snapshotId)]?.let { it.point to it.hash }

    fun clear() = entries.clear()

    /** [snapshotId] is the order that has been registered here and is still retained. */
    fun has(snapshotId: String): Boolean = entries.keys.any { it.startsWith("$snapshotId|") }

    private fun key(ref: String, snapshotId: String) = "$snapshotId|$ref"
}
