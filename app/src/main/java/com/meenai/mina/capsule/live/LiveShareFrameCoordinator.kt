package com.meenai.mina.capsule.live

/**
 * Coordinates frame capture, rate-limiting, screen-change filtering, and dispatching to the Vision model.
 *
 * Rules:
 * 1. Frame sirf tab lo jab screen badle (screen change detection via frameHash comparison).
 * 2. Kam se kam 3 second ka gap between frames (minIntervalMs = 3000).
 * 3. Paused state mein zero frames (koi frame na liya jaye, na bheja jaye).
 * 4. Vision model na ho to frame nahi jata, aur ek baar warning message diya jata hai.
 * 5. Frame phone pe save nahi hota, bhejne ke baad memory se hata diya jata hai.
 * 6. Frame resize to max 1080px on the longest side.
 */
class LiveShareFrameCoordinator(
    private val minIntervalMs: Long = 3000L,
    private val maxLongestSide: Int = 1080,
    private val isPaused: () -> Boolean,
    private val hasVisionModel: () -> Boolean,
    private val currentTaskProvider: () -> String?,
    private val onSendFrame: suspend (jpeg: ByteArray, width: Int, height: Int, currentTask: String?) -> Unit,
    private val onWarning: (String) -> Unit,
) {
    var lastDispatchedTimeMs: Long? = null
        private set
    var lastFrameHash: Long? = null
        private set
    var hasWarnedNoVisionModel: Boolean = false
        private set
    var framesDispatchedCount: Int = 0
        private set

    enum class ProcessResult {
        Dispatched,
        SkippedPaused,
        SkippedNoVisionModel,
        SkippedTooSoon,
        SkippedUnchanged,
    }

    suspend fun onFrameAvailable(
        frameData: ByteArray,
        width: Int,
        height: Int,
        frameHash: Long,
        currentTimeMs: Long,
    ): ProcessResult {
        // 1. Paused state: koi frame na liya jaye, na bheja jaye
        if (isPaused()) {
            return ProcessResult.SkippedPaused
        }

        // 2. Vision model check: agar na ho to ek baar message, share chalta rahe bina bheje
        if (!hasVisionModel()) {
            if (!hasWarnedNoVisionModel) {
                hasWarnedNoVisionModel = true
                onWarning("Screen dekhne wala model set nahi hai")
            }
            return ProcessResult.SkippedNoVisionModel
        }

        // 3. Rate limit: kam se kam 3 second ka gap
        val lastTime = lastDispatchedTimeMs
        if (lastTime != null && currentTimeMs - lastTime < minIntervalMs) {
            return ProcessResult.SkippedTooSoon
        }

        // 4. Change detection: frame sirf tab lo jab screen badle
        if (lastFrameHash != null && lastFrameHash == frameHash) {
            return ProcessResult.SkippedUnchanged
        }

        // 5. Calculate scaled dimensions (max 1080px longest side)
        val longest = maxOf(width, height)
        val factor = if (longest > maxLongestSide) maxLongestSide.toDouble() / longest else 1.0
        val targetW = (width * factor).toInt().coerceAtLeast(1)
        val targetH = (height * factor).toInt().coerceAtLeast(1)

        val task = currentTaskProvider()
        onSendFrame(frameData, targetW, targetH, task)

        lastDispatchedTimeMs = currentTimeMs
        lastFrameHash = frameHash
        framesDispatchedCount++

        return ProcessResult.Dispatched
    }

    fun reset() {
        lastDispatchedTimeMs = null
        lastFrameHash = null
        hasWarnedNoVisionModel = false
        framesDispatchedCount = 0
    }
}
