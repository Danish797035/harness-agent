package com.meenai.mina.cap.phone.a11y

import android.view.accessibility.AccessibilityNodeInfo
import com.meenai.mina.core.cap.phone.PHONE_SNAPSHOT_PREFIX
import com.meenai.mina.core.uistate.ElementLocator
import com.meenai.mina.core.uistate.UiElement
import java.util.UUID

/** SnapshotId for mobile phone screen reading: the prefix of the mobile phone plus a random string (you can tell it belongs to the mobile phone by looking at the number, and it can be separated from the web page). */
internal fun snapshotId(): String = PHONE_SNAPSHOT_PREFIX + UUID.randomUUID()

/** An element in the snapshot: [element] handed to the model, [locator] used to identify the element, [node] used for direct operation. */
internal class NodeEntry(val element: UiElement, val locator: ElementLocator, val node: AccessibilityNodeInfo)

internal class NodeSnapshot(val id: String, val entries: List<NodeEntry>) {
    val locators: List<ElementLocator> get() = entries.map { it.locator }
}

/**
 * Numbering registration table: Each snapshot is renumbered (starting from @e1), and only the numbers of
 * the most recent snapshots are recognized. The number of the latest snapshot can be used directly;
 * earlier snapshots are only used to reposition according to the characteristics of the original
 * element.
 */
internal class NodeIds(private val keep: Int = 4) {
    sealed interface Lookup {
        class Current(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        class Old(val entry: NodeEntry, val snapshot: NodeSnapshot) : Lookup
        data object Unknown : Lookup
    }

    private val history = ArrayDeque<NodeSnapshot>()

    @Synchronized
    fun put(snapshot: NodeSnapshot) {
        history.addFirst(snapshot)
        while (history.size > keep) history.removeLast()
    }

    @Synchronized
    fun clear() { history.clear() }

    @Synchronized
    fun current(): NodeSnapshot? = history.firstOrNull()

    @Synchronized
    fun lookup(ref: String, snapshotId: String): Lookup {
        val index = ref.removePrefix("@e").toIntOrNull()?.minus(1) ?: return Lookup.Unknown
        val i = history.indexOfFirst { it.id == snapshotId }
        if (i < 0) return Lookup.Unknown
        val snap = history[i]
        val entry = snap.entries.getOrNull(index) ?: return Lookup.Unknown
        return if (i == 0) Lookup.Current(entry, snap) else Lookup.Old(entry, snap)
    }
}
