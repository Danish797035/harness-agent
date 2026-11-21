package com.meenai.mina.core.usage

/**
 * 一次运行到此刻的模型用量,给界面实时显示:调用次数、报上来的 token,
 * 以及最近一次请求的上下文大小与模型的上限([contextLeft] 是还剩多少)。
 */
data class RunUsage(
    val calls: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val contextTokens: Int = 0,
    val contextLimit: Int = 0,
    val unreported: Int = 0,
) {
    /** 上下文还剩多少 token(不知道上限时为 0)。 */
    val contextLeft: Int get() = (contextLimit - contextTokens).coerceAtLeast(0)
}
