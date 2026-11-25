package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.Usage
import kotlin.math.ceil

/**
 * The size of the next request (system prompt word + tool definition + message, the same caliber as
 * [ContextLimits]), the fixed overhead is only counted once:
 *
 * - - Anchor point (the most recent reply with real usage of the same model): the input token of that
 * request (reported by the service provider, including the prompt word and tool definition at that time)
 * + The output that will be sent again in this reply (the output minus the thinking token reported
 * separately by the service provider, thinking does not count the context growth) + The estimate of
 * subsequent messages + The estimate of the change in fixed overhead since that time (expanding a set of
 * tools, etc.).
 * - - No anchor point (the first step, the model was changed, and it was just compressed): message +
 * fixed overhead, all estimated.
 *
 * The estimate is always [TokenEstimate] multiplied by the same calibration ratio [scale](see
 * [ContextCompressor.scale]).
 */
internal object UsageMeter {
    /** The actual cost of a reply, and an estimate of the fixed cost of making that request (not multiplied). */
    data class Anchor(val usage: Usage, val overhead: Int)

    fun of(messages: List<Message>, overhead: Int, scale: Double, anchorOf: (Message) -> Anchor? = { null }): Int {
        val at = messages.indexOfLast { it.role == Role.Assistant && anchorOf(it) != null }
        if (at < 0) return estimate(TokenEstimate.of(messages) + overhead, scale)
        val a = anchorOf(messages[at])!!
        val later = TokenEstimate.of(messages.subList(at + 1, messages.size)) + (overhead - a.overhead)
        return a.usage.inputTokens + resent(a.usage) + estimate(later, scale)
    }

    /** The part of the reply that will be sent as input next time: the output minus the thought token reported separately. */
    fun resent(u: Usage): Int = (u.outputTokens - u.reasoningTokens).coerceAtLeast(0)

    fun estimate(tokens: Int, scale: Double): Int = ceil(tokens * scale).toInt()
}
