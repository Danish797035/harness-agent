package com.meenai.mina.core.usage

import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.record.RunEvent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The total number of model requests measured in a run. Run it and put it into its own coroutine
 * context. The model applied to [metered] will add a sum every time the request is completed, so the
 * bypass calls outside the main loop (intent judgment, verification, picture viewing, compression...)
 * and every failed retry are counted under the name of this run; calls that are not in this context (end
 * judgment after handing over the answer, memory retrieval) are not counted, and the Token usage page is
 * still recorded. Only added in memory, multi-thread safe.
 *
 * [onChange] is adjusted every time an item is added (the task uses it to push real-time usage to the
 * interface); [noteContext] records the context size of the latest request.
 */
class RunUsageTally : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<RunUsageTally>

    private val calls = AtomicInteger()
    private val unreported = AtomicInteger()
    private val input = AtomicLong()
    private val output = AtomicLong()
    private val cached = AtomicLong()
    private val context = AtomicInteger()
    private val limit = AtomicInteger()

    /** The usage has changed (updated in real time on the interface). When called from any thread, the callback needs to be cut to the appropriate thread. */
    @Volatile var onChange: (() -> Unit)? = null

    /** Remember a request; [usage] is null, indicating that no usage was reported or the call failed. */
    fun add(usage: Usage?) {
        calls.incrementAndGet()
        if (usage == null) {
            unreported.incrementAndGet()
        } else {
            input.addAndGet(usage.inputTokens.toLong())
            output.addAndGet(usage.outputTokens.toLong())
            cached.addAndGet(usage.cachedInputTokens.toLong())
        }
        onChange?.invoke()
    }

    /** How big was the context when the last request was sent, and what is the upper limit of the model (estimate, and the interface will display how much is left accordingly). */
    fun noteContext(tokens: Int, contextLimit: Int) {
        context.set(tokens)
        limit.set(contextLimit)
        onChange?.invoke()
    }

    /** The current total is used by the interface. */
    fun snapshot() = RunUsage(
        calls = calls.get(),
        inputTokens = input.get(),
        outputTokens = output.get(),
        contextTokens = context.get(),
        contextLimit = limit.get(),
        unreported = unreported.get(),
    )

    /** The total at this moment is regarded as [RunEvent.ModelCalls] in the running record. */
    fun event(atMs: Long) = RunEvent.ModelCalls(atMs, calls.get(), unreported.get(), input.get(), output.get(), cached.get())
}
