package com.meenai.mina.core.usage

/**
 * The usage of the model from one run to this moment is displayed on the interface in real time: the
 * number of calls, the token reported, as well as the context size of the latest request and the upper
 * limit of the model ([contextLeft] is how much is left).
 */
data class RunUsage(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val contextTokens: Int = 0,
    val contextLimit: Int = 0,
    val unreported: Int = 0,
) {
    /** How many tokens are left in the context (the upper limit is 0 when the upper limit is unknown). */
    val contextLeft: Int get() = (contextLimit - contextTokens).coerceAtLeast(0)
}
