package com.meenai.mina.core.router.picker

/**
 * Filter for identifying valid LLM chat models and excluding non-chat models:
 * - Image-edit and image-generation models (e.g. Qwen-Image-Edit, MusePublic/Qwen-Image-Edit,
 *   Qwen/Qwen-Image, Qwen/Qwen-Image-2.1, FLUX, Stable Diffusion, t2i, i2i)
 * - Embeddings, rerankers, text-to-speech (TTS), speech-to-text (ASR/whisper), audio, moderation, guard models.
 *
 * Rules:
 * 1. Image-edit / image-generation models are NOT chat models and must NEVER enter Main, Light, Vision, or any role.
 * 2. Models containing image-edit, image-gen, t2i, i2i, or ending in -image / -image-<number> must NEVER be tagged as Vision models.
 * 3. Genuine vision models (e.g. Qwen2.5-VL) must NOT be excluded.
 * 4. Vision token matching for "vl" must strictly match "-vl", "vl-", "_vl" or token boundaries, not loose substring.
 */
object ChatModelFilter {

    private val IMAGE_EDIT_OR_GEN_PATTERNS = listOf(
        Regex("""(?i)\b(?:image[-_]?edit|imageedit)\b"""),
        Regex("""(?i)\b(?:image[-_]?gen(?:eration)?|imagegen)\b"""),
        Regex("""(?i)\b(?:t2i|i2i|text[-_]?to[-_]?image|image[-_]?to[-_]?image)\b"""),
        Regex("""(?i)\b(?:flux|stable[-_]?diffusion|diffusers|midjourney|dall[-_]?e)\b"""),
        // Matches names ending in -image or -image-<version>, e.g. Qwen-Image, Qwen/Qwen-Image-2.1
        Regex("""(?i)(?:^|[-_/])image(?:-\d+(?:\.\d+)*)?$"""),
    )

    private val NON_CHAT_PATTERNS = listOf(
        Regex("""(?i)\b(?:embedding|embeddings|rerank|reranker|bge)\b"""),
        Regex("""(?i)\b(?:tts|stt|asr|whisper|speech|voice|audio|transcribe)\b"""),
        Regex("""(?i)\b(?:moderation|guard|reward|discriminator|ocr)\b"""),
    )

    // Token boundary match for "vl": "-vl", "vl-", "_vl", or standalone "vl" token
    private val VL_TOKEN_REGEX = Regex("""(?i)(?:^|[-_/])vl(?:[-_/]|$)""")
    private val VISION_TOKEN_REGEX = Regex("""(?i)(?:^|[-_/])(?:vision|omni)(?:[-_/]|$)""")

    /**
     * Checks if a model ID or name represents an image generation or editing model.
     * Examples: Qwen-Image, Qwen/Qwen-Image-2.1, Qwen-Image-Edit, FLUX.1-schnell, SDXL.
     */
    fun isImageEditOrGen(modelId: String, name: String = ""): Boolean {
        val cleanId = modelId.substringBefore(':').trim()
        val cleanName = name.substringBefore(':').trim()
        val combined = "$modelId $name"

        if (IMAGE_EDIT_OR_GEN_PATTERNS.any { it.containsMatchIn(cleanId) }) return true
        if (cleanName.isNotEmpty() && IMAGE_EDIT_OR_GEN_PATTERNS.any { it.containsMatchIn(cleanName) }) return true
        return IMAGE_EDIT_OR_GEN_PATTERNS.any { it.containsMatchIn(combined) }
    }

    /**
     * Checks if a model should be excluded from chat/routing candidates.
     * Excludes: embedding, rerank, tts, asr, speech, video, audio, moderation, image-gen, image-edit, etc.
     */
    fun isExcluded(modelId: String, name: String = ""): Boolean {
        if (isImageEditOrGen(modelId, name)) return true
        val combined = "$modelId $name"
        return NON_CHAT_PATTERNS.any { it.containsMatchIn(combined) }
    }

    /**
     * Determines if a model genuinely supports multimodal vision input for chat.
     * Image-edit and image-generation models MUST return false.
     * Checks "-vl", "vl-", "_vl", "vision", "omni" on clean token boundaries.
     */
    fun supportsVision(modelId: String, declaredVision: Boolean = false): Boolean {
        if (isImageEditOrGen(modelId)) return false
        if (declaredVision) return true
        val cleanId = modelId.substringBefore(':').trim()
        return VL_TOKEN_REGEX.containsMatchIn(cleanId) ||
            VISION_TOKEN_REGEX.containsMatchIn(cleanId)
    }
}
