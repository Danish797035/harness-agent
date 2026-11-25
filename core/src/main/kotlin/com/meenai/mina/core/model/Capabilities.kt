package com.meenai.mina.core.model

data class Capabilities(
    val toolCalls: Boolean,
    val vision: Boolean,
    val contextTokens: Int,
    val reasoning: Boolean,
    /**
     * The accuracy of image positioning (boxing elements on screenshots) has been verified. The points
     * shown in the model frame that have not been verified will be handed over to find_visually only if
     * there is additional supporting evidence.
     */
    val groundingVerified: Boolean = false,
    /** How the server reuses the same prompt word at the beginning (to save money and speed up); it is up to the adapter to decide whether to include a cache mark in the request. */
    val promptCaching: PromptCaching = PromptCaching.Implicit,
    /** The upper limit of images requested at one time is clearly announced by the service provider; do not guess if it is unknown. */
    val maxImages: Int = Int.MAX_VALUE,
    /** Optional local compression trigger point, based on the number of tokens in the entire request. */
    val compactionTriggerTokens: Int? = null,
)

/**
 * [Implicit]: The server automatically reuses the same beginning, no tags are needed in the request.
 * [Explicit]: Mark the cache location (cache_control) on the message, otherwise it will not be reused.
 * [None]: Not reused.
 */
enum class PromptCaching(val wire: String) {
    Implicit("implicit"), Explicit("explicit"), None("none");

    companion object {
        fun of(wire: String?): PromptCaching = entries.firstOrNull { it.wire == wire } ?: Implicit
    }
}

enum class ModelRole { Main, Vision, Light }
