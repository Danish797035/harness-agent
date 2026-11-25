package com.meenai.mina.core.eval

/**
 * Visible obstacles on the screen: login wall, respective words for human-machine verification (login
 * box, verification code input, re-login button...). The reviewer only recognized the obstacle reported
 * by the model when there were such words on the screen ([RunFacts.takeoverBarrier]), not just what the
 * model said.
 */
object BarrierSignals {
    private val SIGNALS = mapOf(
        "login" to listOf(
            "获取验证码", "发送验证码", "短信验证码", "请输入密码", "密码登录", "验证码登录", "手机号登录", "扫码登录",
            "重新登录", "欢迎登录", "登录后查看", "sign in", "log in",
        ),
        "captcha" to listOf("人机验证", "安全验证", "滑块验证", "拖动滑块", "请完成验证", "验证您是真人", "captcha", "verify you are human", "not a robot"),
    )

    /** [texts] (一屏的元素文字) contains the words [kind] for obstacles; the type of obstacle that is not recognized is false. */
    fun shows(kind: String, texts: List<String>): Boolean {
        val words = SIGNALS[kind] ?: return false
        val text = texts.joinToString("\n").lowercase()
        return words.any { it in text }
    }
}

