package com.meenai.mina.replay

import android.content.Context
import com.meenai.mina.core.replay.MAX_ROUTE_BYTES
import com.meenai.mina.core.replay.MAX_ROUTE_STEPS
import com.meenai.mina.core.replay.Route
import com.meenai.mina.core.replay.RouteCodec
import com.meenai.mina.core.replay.RouteStore
import com.meenai.mina.core.replay.replacing
import com.meenai.mina.core.replay.withCurrentToolNames
import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.core.assembly.legacyNames
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.LegacyNames
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Routes are saved as `<dir>/<first package name>/<id>.json`; when there are more than [max], the routes that have
 * been unused for a long time are deleted. Files that cannot be read are deleted directly (the format
 * changes during the development period and will not be compatible). The product path uses [shared],
 * with one instance and one lock for the entire process.
 */
class FileRouteStore(
    private val dir: File, private val max: Int = 200,
    /** Replace the old tool name with the current one when reading out the route you noted before changing the name. */
    private val legacy: LegacyNames = legacyNames(), private val tools: ToolIndex = knownTools(),
) : RouteStore {
    init { require(max > 0) { "max must be positive" } }

    private val lock = Any()

    override fun all(): List<Route> = synchronized(lock) { load().map { it.second } }

    override fun put(r: Route) = synchronized(lock) { putLocked(r, load()) }

    override fun putReplacing(r: Route) = synchronized(lock) {
        val existing = load()
        val old = existing.filter { it.second.template == r.template }
        putLocked(replacing(r, old.map { it.second }), existing)
        old.filter { it.second.id != r.id }.forEach { it.first.delete() }
        pruneEmptyDirs()
    }

    override fun update(id: String, change: (Route) -> Route?) = synchronized(lock) {
        val existing = load()
        existing.firstOrNull { it.second.id == id }?.second?.let(change)?.let { putLocked(it, existing) }
        Unit
    }

    override fun remove(id: String) = synchronized(lock) {
        load().filter { it.second.id == id }.forEach { it.first.delete() }
        pruneEmptyDirs()
    }

    override fun clear() = synchronized(lock) { dir.deleteRecursively(); Unit }

    override fun count(): Int = synchronized(lock) { load().size }

    private fun putLocked(r: Route, existing: List<Pair<File, Route>>) {
        val bytes = RouteCodec.encode(r).toByteArray()
        require(r.steps.size <= MAX_ROUTE_STEPS && bytes.size <= MAX_ROUTE_BYTES) { "route ${r.id} too large" }
        val f = fileOf(r)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        FileOutputStream(tmp).use { out -> out.write(bytes); out.fd.sync() }
        // Atomic replacement: old files remain intact until new files are in place
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        // Changed the first package name with the same id: delete the copy at the old location
        existing.filter { it.second.id == r.id && it.first.absoluteFile != f.absoluteFile }.forEach { it.first.delete() }
        val kept = existing.filter { it.second.id != r.id } + (f to r)
        kept.sortedBy { it.second.lastUsedMs }.take((kept.size - max).coerceAtLeast(0)).forEach { it.first.delete() }
        pruneEmptyDirs()
    }

    private fun fileOf(r: Route) = File(File(dir, safe(r.packages.firstOrNull() ?: NO_PACKAGE)), "${safe(r.id)}.json")

    private fun load(): List<Pair<File, Route>> =
        (dir.listFiles()?.filter { it.isDirectory } ?: emptyList()).flatMap { d ->
            (d.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()).mapNotNull { f ->
                try {
                    f to RouteCodec.decode(f.readText()).withCurrentToolNames(legacy, tools)
                } catch (e: Exception) {
                    f.delete()
                    null
                }
            }
        }

    private fun pruneEmptyDirs() {
        dir.listFiles()?.filter { it.isDirectory && it.list()?.isEmpty() == true }?.forEach { it.delete() }
    }

    /** Give the application in the process. */
    interface Owner {
        val routes: FileRouteStore
    }

    companion object {
        const val DIR = "routes"
        private const val NO_PACKAGE = "_"
        private val UNSAFE = Regex("[^A-Za-z0-9._-]")
        private fun safe(s: String) = UNSAFE.replace(s, "_").ifEmpty { "_" }.let { if (it == "." || it == "..") "_" else it }

        /** The copy in the product (App built): files/routes. */
        fun create(context: Context): FileRouteStore = FileRouteStore(File(context.applicationContext.filesDir, DIR))

        /** The share shared within the App (given by [Owner]; it is used for task end records and settings page clearing): one instance and one lock for the entire process. */
        fun shared(context: Context): FileRouteStore = (context.applicationContext as? Owner)?.routes
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement FileRouteStore.Owner to give the route store")
    }
}
