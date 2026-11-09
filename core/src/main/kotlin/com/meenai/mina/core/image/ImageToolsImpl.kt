package com.meenai.mina.core.image

import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.put

internal object GenerateImageTool : AgentTool {
    override val verb = Verb.GenerateImage
    override val batchable = false
    override val routable = false
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val promptFile = ImageTools.GENERATE_IMAGE_NAME

    override fun available(ctx: ToolContext): Boolean = ctx.config.imageService != null

    override val def = ToolDef(
        ImageTools.GENERATE_IMAGE_NAME,
        Prompts.tool(ImageTools.GENERATE_IMAGE_NAME),
        mapOf(
            "prompt" to str("Detailed text description of the image to generate"),
            "size" to str("Optional image dimensions, e.g. 1024*1024")
        ),
        listOf("prompt")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val prompt = a.str("prompt")?.takeIf { it.isNotBlank() }
            ?: return fail("invalid_prompt", "Prompt cannot be empty")
        if (ImageIntentRouter.isVideoRequest(prompt)) {
            return fail("video_not_supported", ImageIntentRouter.VIDEO_NOT_SUPPORTED_MSG)
        }
        val size = a.str("size")

        val service: ImageGenerationService = ctx.config.imageService
            ?: return fail("no_image_service", "Image generation service is not available")

        ctx.config.onImageProgress?.invoke(true, prompt, "")

        val result = service.generateImage(prompt, size)

        return when (result) {
            is ImageResult.Success -> {
                val filePath = ctx.config.onImageSaved?.invoke(result.bytes, prompt, result.model)
                ctx.config.onImageProgress?.invoke(false, prompt, result.model)
                ok {
                    if (filePath != null) put("filePath", filePath)
                    put("model", result.model)
                    put("magicubeCost", result.costMagicube)
                }
            }
            is ImageResult.Error -> {
                ctx.config.onImageProgress?.invoke(false, prompt, "")
                fail("image_generation_failed", result.message)
            }
        }
    }
}

internal object EditImageTool : AgentTool {
    override val verb = Verb.EditImage
    override val batchable = false
    override val routable = false
    override val risk = Risk.Write
    override val offScreen = true
    override val logged = true
    override val promptFile = ImageTools.EDIT_IMAGE_NAME

    override fun available(ctx: ToolContext): Boolean = ctx.config.imageService != null

    override val def = ToolDef(
        ImageTools.EDIT_IMAGE_NAME,
        Prompts.tool(ImageTools.EDIT_IMAGE_NAME),
        mapOf(
            "prompt" to str("Detailed instructions on edits/changes to apply to the image"),
            "image" to str("Optional base64 image or reference identifier")
        ),
        listOf("prompt")
    )

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val prompt = a.str("prompt")?.takeIf { it.isNotBlank() }
            ?: return fail("invalid_prompt", "Prompt cannot be empty")

        val service = ctx.config.imageService
            ?: return fail("no_image_service", "Image generation service is not available")

        var imageBase64 = a.str("image")
        if (imageBase64.isNullOrBlank()) {
            val attached = ctx.config.attachedPhoto
            if (attached == null) {
                return fail("missing_image", "Edit ke liye photo attach karein.")
            }
            if (attached.source != PhotoSource.UserAttachment) {
                return fail("security_rejected", "Screen capture edit ke liye allow nahi hai.")
            }
            if (ctx.config.isSensitivePackage?.invoke(attached.sourcePackage) == true) {
                return fail("security_rejected", "Sensitive ya blocked app ki photo edit ke liye allow nahi hai.")
            }
            imageBase64 = attached.base64
        }

        ctx.config.onImageProgress?.invoke(true, prompt, "")

        val result = service.editImage(prompt, imageBase64)

        return when (result) {
            is ImageResult.Success -> {
                val filePath = ctx.config.onImageSaved?.invoke(result.bytes, prompt, result.model)
                ctx.config.onImageProgress?.invoke(false, prompt, result.model)
                ok {
                    if (filePath != null) put("filePath", filePath)
                    put("model", result.model)
                    put("magicubeCost", result.costMagicube)
                }
            }
            is ImageResult.Error -> {
                ctx.config.onImageProgress?.invoke(false, prompt, "")
                fail("image_generation_failed", result.message)
            }
        }
    }
}
