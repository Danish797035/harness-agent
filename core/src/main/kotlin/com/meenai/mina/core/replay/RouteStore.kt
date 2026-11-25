package com.meenai.mina.core.replay

/** Learned practices. */
interface RouteStore {
    fun all(): List<Route>
    fun put(r: Route)
    fun remove(id: String)
    fun clear()
    fun count(): Int

    /** Read out [id], rewrite, and write back; [change] returns null and does not change. The implementation of concurrent use must be locked as a whole, and no other changes can be inserted between reading and writing. */
    fun update(id: String, change: (Route) -> Route?) {
        all().firstOrNull { it.id == id }?.let(change)?.let(::put)
    }

    /** When the same template already has a route, replace the old one with the new route (see [replacing]). The implementation of concurrent use requires overall locking. */
    fun putReplacing(r: Route) {
        val old = all().filter { it.template == r.template }
        put(replacing(r, old))
        old.filter { it.id != r.id }.forEach { remove(it.id) }
    }
}

/** The same path: the tools, parameters, and goals of each step are the same (the anchor point is slightly different each time the screen is read, so it does not count). */
fun samePath(a: Route, b: Route): Boolean =
    a.steps.size == b.steps.size && a.steps.zip(b.steps).all { (x, y) -> x.tool == y.tool && x.argsJson == y.argsJson && x.target == y.target }

/**
 * What should be brought to the new route when replacing the old route with the same template: the
 * success count is the maximum; if the path does not change, the error count and deactivated state will
 * be used (the same path did not match the previous step, and a new record should not be erased); if the
 * path changes, this is a new approach, and the error count starts from zero.
 */
fun replacing(r: Route, old: List<Route>): Route {
    val successes = maxOf(r.successes, old.maxOfOrNull { it.successes } ?: 0)
    val same = old.firstOrNull { samePath(it, r) } ?: return r.copy(successes = successes)
    return r.copy(successes = successes, consecutiveMisses = same.consecutiveMisses, missStep = same.missStep, disabled = same.disabled)
}
