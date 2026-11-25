package com.meenai.mina.core.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** The Mina accessibility service seen by the App: whether it is in the system list ([enabled]), and whether the service instance is connected ([connected]). */
data class A11yObservation(val enabled: Boolean, val connected: Boolean) {
    /**
     * If the service is not running, it needs to be repaired: whether it is open in the system but not
     * bound (the system crashes when the process is killed), or it is removed from the system list (the
     * system does this when it is forcibly stopped). If the user wants to turn it off, turn off "Turn on
     * accessibility for me".
     */
    val stuck: Boolean get() = !connected
}

/** At what stage does the automatic restart of the accessibility service proceed: The party who wants to use the service decides to wait a little longer or give up immediately. */
enum class RepairPhase {
    /** There is nothing to fix (the service is connected, the user turned it off in Mina and turned it on for me, the assistant is not there, I tried twice and gave up). */
    Idle,
    /** If it's stuck, wait a while to see if the system can connect on its own. */
    Pending,
    /** Removing and adding it again, waiting for the service to connect. */
    Repairing,
}

/** The result of the last automatic restart of the accessibility service (only recorded in memory, say something to the settings page). */
data class RepairOutcome(val restarted: Boolean, val atMillis: Long, val attempts: Int)

/**
 * Mina's accessibility service is not automatically turned on at runtime: when the built-in assistant is
 * running, use it to write Mina's service back to the system list
 * ([RecoveryPermissions.restartAccessibility]: If it is in the list, remove it and add it back to let
 * the system rebind it, if not, add it directly). When the App itself has the authorization to modify
 * the system settings ([direct]), write directly without waiting for the assistant (wireless debugging
 * can also be repaired if it is turned off). Only look at the status of three channels: service
 * ([A11yObservation]), built-in assistant and whether the user has been unlocked; any changes will be
 * re-judged without polling.
 *
 * - - Fix it as long as it's not running; it doesn't matter if the user has turned off "Turn on
 * accessibility for me" in Mina.
 * - - If the user has not unlocked it once after booting (the encrypted credential storage has not been
 * unlocked), services that do not support direct startup cannot be bound: no repair will be done at this
 * time.
 * Wait for the user to unlock (the unlocked of [watch] changes to true) and then judge again, and the
 * repair will still be done.
 * - - Wait first when it is stuck [GRACE_MS]: When the process just starts up, the system will usually
 * connect immediately, so there is no need to do anything.
 * - - Each time it gets stuck, the maximum number of attempts is [MAX_ATTEMPTS], and the interval is
 * gradually lengthened; if it still fails to connect, it will stop, leaving a manual processing prompt
 * on the interface.
 * The time has passed only when the service is connected, and will be counted again when it is
 * disconnected.
 */
class AccessibilityRepair(
    private val restart: suspend (gapMs: Long) -> PermissionResult,
    private val consent: () -> RecoveryConsent,
    /** True when the app can write system settings by itself (it has obtained authorization to modify system settings): there is no need to wait for the built-in assistant. */
    private val direct: () -> Boolean = { false },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _last = MutableStateFlow<RepairOutcome?>(null)
    val last: StateFlow<RepairOutcome?> = _last.asStateFlow()

    private val _phase = MutableStateFlow(RepairPhase.Idle)
    val phase: StateFlow<RepairPhase> = _phase.asStateFlow()

    private enum class Result { Restored, GaveUp, Interrupted }

    /**
     * Keep watching until canceled. Changes in status during the repair process (including removing and
     * adding back by yourself) will be judged again based on the latest version. [unlocked] refers to
     * whether the user has unlocked it after booting; if it has not been unlocked, it will not be
     * repaired. Its changes during unlocking will be judged again here.
     */
    suspend fun watch(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean> = MutableStateFlow(true),
    ) {
        var exhausted = false
        combine(service, helper, unlocked, ::Triple).conflate().collect { (s, h, u) ->
            if (s.connected) {
                exhausted = false
                return@collect
            }
            if (exhausted || !eligible(s, h, u)) return@collect
            try {
                _phase.value = RepairPhase.Pending
                delay(GRACE_MS)
                if (!eligible(service.value, helper.value, unlocked.value)) return@collect
                _phase.value = RepairPhase.Repairing
                exhausted = repair(service, helper, unlocked) == Result.GaveUp
            } finally {
                _phase.value = RepairPhase.Idle
            }
        }
    }

    private fun eligible(s: A11yObservation, h: BuiltinShellStatus, unlocked: Boolean) =
        s.stuck && unlocked && (direct() || h == BuiltinShellStatus.Running) && consent().wants(RecoveryCapability.Accessibility)

    private suspend fun repair(
        service: StateFlow<A11yObservation>,
        helper: StateFlow<BuiltinShellStatus>,
        unlocked: StateFlow<Boolean>,
    ): Result {
        for (attempt in 1..MAX_ATTEMPTS) {
            if (attempt > 1) {
                delay(BACKOFF_MS * (attempt - 1))
                val s = service.value
                if (s.connected) return Result.Restored.also { record(true, attempt - 1) }
                if (!eligible(s, helper.value, unlocked.value)) return Result.Interrupted
            }
            if (restart(GAP_MS * attempt) == PermissionResult.Done &&
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { service.first { it.connected } } != null
            ) {
                return Result.Restored.also { record(true, attempt) }
            }
        }
        record(false, MAX_ATTEMPTS)
        return Result.GaveUp
    }

    private fun record(restarted: Boolean, attempts: Int) {
        _last.value = RepairOutcome(restarted, now(), attempts)
    }

    companion object {
        const val GRACE_MS = 3_000L
        const val MAX_ATTEMPTS = 2
        /** The pause between removing and adding back is lengthened one by one (1 second, 2 seconds): the two writes are too close together, and the system may only see the last list. */
        const val GAP_MS = 1_000L
        const val BACKOFF_MS = 5_000L
        const val CONNECT_TIMEOUT_MS = 8_000L
        /** How long does it take at the longest time from getting stuck to giving up: wait for a while, remove it twice and then add it back, wait for each connection, retreat once in the middle, and leave some margin for running commands. */
        const val WORST_CASE_MS = GRACE_MS + MAX_ATTEMPTS * CONNECT_TIMEOUT_MS + GAP_MS * 3 + BACKOFF_MS + 2_000L

        /**
         * When you want to use the accessibility service, wait for it: first wait for [baseMs] as usual;
         * it has not been connected yet, but is about to be repaired or is being repaired ([phase] is
         * not Idle), then wait until the side where it is connected or repaired stops (the service is
         * connected after repairing, and null is returned if it is abandoned), the total does not exceed
         * [capMs]. If there is nothing to be repaired, it will be the same as before. If [baseMs]
         * arrives, null will be returned.
         */
        suspend fun <T : Any> awaitService(
            service: StateFlow<T?>,
            phase: StateFlow<RepairPhase>,
            baseMs: Long,
            capMs: Long = WORST_CASE_MS,
        ): T? {
            withTimeoutOrNull(baseMs) { service.filterNotNull().first() }?.let { return it }
            if (phase.value == RepairPhase.Idle) return service.value
            return withTimeoutOrNull((capMs - baseMs).coerceAtLeast(0)) {
                combine(service, phase, ::Pair).first { (s, p) -> s != null || p == RepairPhase.Idle }.first
            }
        }
    }
}
