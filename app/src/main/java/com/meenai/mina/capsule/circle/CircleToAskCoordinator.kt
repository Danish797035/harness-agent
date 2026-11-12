package com.meenai.mina.capsule.circle

import com.meenai.mina.capsule.CapsuleSecurityPolicy
import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.model.ChatModel

/**
 * Coordinates security checks, selection validation, and Vision model querying for "Circle to ask".
 */
class CircleToAskCoordinator(
    securityChecker: ((packages: Collection<String>?, state: AppAccessState) -> Boolean)? = null,
    private val visionModelProvider: () -> ChatModel?,
    private val sendQuery: suspend (jpeg: ByteArray, width: Int, height: Int, query: String) -> String,
    private val onWarning: (String) -> Unit,
    private val isDeviceLocked: () -> Boolean = { false },
) {
    private val securityChecker: (packages: Collection<String>?, state: AppAccessState) -> Boolean =
        securityChecker ?: { pkgs, st ->
            CapsuleSecurityPolicy.canPerformScreenAction(pkgs, isDeviceLocked(), st)
        }
    sealed interface StartResult {
        data object Allowed : StartResult
        data object Blocked : StartResult
    }

    sealed interface AskResult {
        data class Success(val reply: String) : AskResult
        data object NoVisionModel : AskResult
        data object TooSmall : AskResult
        data class Failure(val error: String) : AskResult
    }

    /**
     * Checks if Circle to Ask is permitted given the currently foreground packages and security policy.
     * Disallowed on blocked apps, Aadhaar, bank, and UPI apps.
     */
    fun canOpenCircleToAsk(
        foregroundPackages: Collection<String>?,
        appAccessState: AppAccessState,
    ): Boolean {
        return securityChecker(foregroundPackages, appAccessState)
    }

    /**
     * Executes the ask request with the cropped image and user's question.
     * Enforces:
     * - Rejection if selection is smaller than 24dp.
     * - Disallows sending if Vision model is not configured (and displays warning).
     * - Never sends or retains screenshot on storage.
     */
    suspend fun executeAsk(
        rect: CropRect,
        density: Float,
        croppedJpeg: ByteArray,
        width: Int,
        height: Int,
        userQuery: String,
    ): AskResult {
        if (!CircleCropGeometry.isValidSelection(rect, density)) {
            return AskResult.TooSmall
        }
        val model = visionModelProvider()
        if (model == null) {
            onWarning("Screen dekhne wala model set nahi hai")
            return AskResult.NoVisionModel
        }
        return try {
            val reply = sendQuery(croppedJpeg, width, height, userQuery)
            AskResult.Success(reply)
        } catch (e: Exception) {
            AskResult.Failure(e.message ?: "Failed to query vision model")
        }
    }
}
