package com.meenai.mina.core.uistate

enum class ScreenState(val wire: String) {
    NORMAL("normal"),
    /** There are no windows or readable elements. */
    BLANK("blank"),
    /** The interface continues to change and has not finished loading. */
    LOADING("loading"),
    /** Anti-screenshot page: The screen is completely black but there is a window. */
    PROTECTED("protected"),
    /** Login, verification code, payment and other pages that need to be handled by the user personally. */
    NEEDS_HUMAN("needs_human"),
}

/**
 * [takeoverReason] is the reason recommended for hand_to_user; [cause] is a short description of the
 * basis for judgment. Exception: [ScreenDiagnosis.UNSUPPORTED_BROWSER] (site rejects built-in browser)
 * does not need people, state is NORMAL, needsHuman is false, it is time to change the route.
 */
data class ScreenCheck(
    val state: ScreenState,
    val currentApp: String?,
    val needsHuman: Boolean,
    val takeoverReason: String? = null,
    val cause: String? = null,
)

/**
 * Facts collected by the port. [blackFrames] is the result of whether the screen is completely black
 * every time the interface is quiet, in chronological order; it is empty when the screen cannot be
 * obtained. [hasWindow] has an App window that can read the root node; [anyWindow] has an App window (it
 * does not count if the root node cannot be read). [foregroundApp] is the foreground App that is known
 * by pressing the focus window or the top task when the window cannot be read; it is null if it is not
 * known.
 */
data class ScreenFacts(
    val currentApp: String?,
    val hasWindow: Boolean,
    val readableElements: Int,
    val changing: Boolean,
    val blackFrames: List<Boolean>,
    val texts: List<String>,
    val passwordField: Boolean,
    val anyWindow: Boolean = hasWindow,
    val foregroundApp: String? = null,
)

object ScreenDiagnosis {
    /** There is no App window on the screen (this was the case when the virtual screen was first built). */
    const val NO_WINDOW = "no window"
    const val UNREADABLE_WINDOW = "unreadable window"
    /** Web page: The website does not support the built-in browser (requires changing browser or downloading App). */
    const val UNSUPPORTED_BROWSER = "unsupported_browser"

    private val CAPTCHA = listOf(
        "captcha", "人机验证", "安全验证", "滑块验证", "拖动滑块", "向右滑动完成", "请完成验证", "verify you are human", "not a robot",
    )
    private val PAYMENT = listOf(
        "支付密码", "确认支付", "确认付款", "立即支付", "输入密码支付", "payment password", "confirm payment", "pay now",
    )
    private val SMS_CODE = listOf(
        "获取验证码", "发送验证码", "短信验证码", "get code", "send code", "verification code",
    )

    /** The first payment word in the on-screen text (confirm payment, payment password, etc.); if not, it is null. */
    fun paymentText(texts: List<String>): String? {
        val text = Hant.fold(texts.joinToString("\n")).lowercase()
        return PAYMENT.firstOrNull { it in text }
    }

    fun classify(f: ScreenFacts): ScreenCheck {
        val text = Hant.fold(f.texts.joinToString("\n")).lowercase()
        fun hit(words: List<String>) = words.firstOrNull { it in text }
        fun human(reason: String, cause: String) = ScreenCheck(ScreenState.NEEDS_HUMAN, f.currentApp, true, reason, cause)

        // The screen may also go completely black for a short time when switching pages: if the
        // interface is still changing, press Loading.
        if (f.changing && f.blackFrames.any { it }) {
            return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "black frame while the screen keeps changing")
        }
        val human = when {
            hit(CAPTCHA) != null -> "captcha" to "captcha text: ${hit(CAPTCHA)}"
            hit(PAYMENT) != null -> "payment" to "payment text: ${hit(PAYMENT)}"
            f.passwordField -> "login" to "password field"
            hit(SMS_CODE) != null -> "login" to "verification code text: ${hit(SMS_CODE)}"
            else -> null
        }
        // If it is completely black during two quiet times, it is considered to be anti-screenshot. Once
        // it is black, it may just be that the drawing has not been drawn yet.
        if (f.hasWindow && f.blackFrames.size >= 2 && f.blackFrames.all { it }) {
            return ScreenCheck(ScreenState.PROTECTED, f.currentApp, true, human?.first ?: "other", "secure window: the frame stays black")
        }
        human?.let { (reason, cause) -> return human(reason, cause) }
        if (f.changing) return ScreenCheck(ScreenState.LOADING, f.currentApp, false, cause = "screen keeps changing")
        if (!f.hasWindow || f.readableElements == 0) {
            val cause = when {
                f.hasWindow -> "no readable elements"
                f.anyWindow || f.foregroundApp != null -> UNREADABLE_WINDOW
                else -> NO_WINDOW
            }
            return ScreenCheck(ScreenState.BLANK, f.currentApp ?: f.foregroundApp, false, cause = cause)
        }
        return ScreenCheck(ScreenState.NORMAL, f.currentApp, false)
    }
}
