package com.meenai.mina.core.selfcheck

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** A self-test looks like this: [results] in the order of [SelfCheck], one for each item. */
data class SelfCheckState(
    val phase: Phase = Phase.Idle,
    val results: List<CheckResult> = SelfCheck.entries.map { CheckResult(it) },
    val facts: DeviceFacts? = null,
    val elapsedMs: Long = 0,
) {
    enum class Phase { Idle, Running, Done, Cancelled }

    fun of(check: SelfCheck): CheckResult = results[check.ordinal]

    /** After the run is completed or canceled: a report can be issued. */
    val ended: Boolean get() = phase == Phase.Done || phase == Phase.Cancelled
}

/**
 * Run through each item of [SelfCheck] in order: skip the first one if it is not passed and write down
 * which item is missing; each item has its own time limit, and there is also a total time limit of
 * [budgetMs] for the entire run. When time runs out, the remaining items are recorded as skipped. If an
 * error occurs, it will be recorded as failure, and subsequent items will not be interrupted. No matter
 * it runs out, makes an error or is canceled, [SelfCheckPort.release] will be adjusted in the end.
 * [state] gives the progress at any time, and the interface follows the drawing. An instance only runs
 * once.
 */
class SelfCheckRunner(
    private val port: SelfCheckPort,
    private val now: () -> Long = System::currentTimeMillis,
    private val budgetMs: Long = TOTAL_BUDGET_MS,
) {
    private val _state = MutableStateFlow(SelfCheckState())
    val state: StateFlow<SelfCheckState> = _state.asStateFlow()

    suspend fun run(): SelfCheckState {
        val start = now()
        _state.value = SelfCheckState(SelfCheckState.Phase.Running)
        try {
            for (c in SelfCheck.entries) step(c, start)
            finish(SelfCheckState.Phase.Done, start)
        } catch (e: CancellationException) {
            finish(SelfCheckState.Phase.Cancelled, start)
            throw e
        } finally {
            withContext(NonCancellable) { runCatching { port.release() } }
        }
        return _state.value
    }

    private suspend fun step(c: SelfCheck, start: Long) {
        val results = _state.value.results
        c.needs.firstOrNull { !results[it.ordinal].status.passed }?.let { blocker ->
            set(CheckResult(c, CheckStatus.Skipped, "needs ${blocker.key} (${results[blocker.ordinal].status.name.lowercase()})", blockedBy = blocker))
            return
        }
        val left = budgetMs - (now() - start)
        if (left <= 0) {
            set(CheckResult(c, CheckStatus.Skipped, "out of time", Advice.OutOfTime))
            return
        }
        set(CheckResult(c, CheckStatus.Running))
        val t0 = now()
        val limit = minOf(c.timeoutMs, left)
        val outcome = try {
            withTimeoutOrNull(limit) { if (c == SelfCheck.DEVICE) device() else port.check(c) }
                ?: CheckOutcome.fail("timed out after ${seconds(limit)} s", Advice.TimedOut)
        } catch (e: TimeoutCancellationException) {
            // The time limit in the port has expired (it is not that the self-test was canceled this
            // time)
            CheckOutcome.fail("timed out inside the check", Advice.TimedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CheckOutcome.fail("error: ${e.javaClass.simpleName}", Advice.Error)
        }
        set(CheckResult(c, outcome.status, outcome.detail, outcome.advice, durationMs = now() - t0))
        _state.value = _state.value.copy(elapsedMs = now() - start)
    }

    private suspend fun device(): CheckOutcome {
        val f = port.facts()
        _state.value = _state.value.copy(facts = f)
        return judgeDevice(f)
    }

    private fun set(r: CheckResult) {
        val s = _state.value
        _state.value = s.copy(results = s.results.map { if (it.check == r.check) r else it })
    }

    /** End: Anything that has not yet been concluded (what is running when canceling and what follows) will be recorded as skipped. */
    private fun finish(phase: SelfCheckState.Phase, start: Long) {
        val s = _state.value
        _state.value = s.copy(
            phase = phase,
            elapsedMs = now() - start,
            results = s.results.map { if (it.status.final) it else CheckResult(it.check, CheckStatus.Skipped, "cancelled", Advice.Cancelled) },
        )
    }

    companion object {
        /** The total time limit for the entire self-test: the time limits for each item add up to be longer than this, which is far from being used up under normal circumstances. */
        const val TOTAL_BUDGET_MS = 90_000L

        private fun seconds(ms: Long) = (ms + 999) / 1000

        /**
         * The "Device" item: It cannot be used for mobile phone operations below Android 12; it cannot
         * be used if there is no way to launch an assistant in this environment; without wireless
         * debugging, it can only be started with a computer, which is partially usable.
         */
        fun judgeDevice(f: DeviceFacts): CheckOutcome {
            val detail = "Android ${f.androidRelease} (SDK ${f.sdk}), ${f.abis.firstOrNull() ?: "unknown ABI"}" +
                ", wireless debugging ${when (f.wirelessDebugging) { true -> "present"; false -> "missing"; null -> "unknown" }}"
            return when {
                f.sdk < DeviceFacts.MIN_PHONE_SDK -> CheckOutcome.fail("$detail; phone control needs Android 12+", Advice.PhoneTooOld)
                f.phoneControlUnavailable -> CheckOutcome.fail(
                    "$detail, developer options page missing; phone control unavailable in this environment", Advice.PhoneControlUnavailable,
                )
                f.wirelessDebugging == false -> CheckOutcome.partial(detail, Advice.NoWirelessDebugging)
                else -> CheckOutcome.pass(detail)
            }
        }
    }
}
