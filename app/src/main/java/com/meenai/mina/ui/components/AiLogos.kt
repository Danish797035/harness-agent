package com.meenai.mina.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme

enum class AiBrand(
    val brandName: String,
    val brandColor: Color,
    val containerColor: Color,
    val drawableResId: Int,
) {
    Google("Google", Color(0xFF1A73E8), Color(0xFFE8F0FE), R.drawable.ic_ai_gemini),
    OpenAi("OpenAI", Color(0xFF10A37F), Color(0xFFE6F7F2), R.drawable.ic_ai_openai),
    Anthropic("Anthropic", Color(0xFFD97757), Color(0xFFFBF0EA), R.drawable.ic_ai_claude),
    Meta("Meta", Color(0xFF0668E1), Color(0xFFEBF3FF), R.drawable.ic_ai_meta),
    DeepSeek("DeepSeek", Color(0xFF1E88E5), Color(0xFFE3F2FD), R.drawable.ic_ai_deepseek),
    Qwen("Qwen", Color(0xFF615CED), Color(0xFFF0EFFF), R.drawable.ic_ai_qwen),
    Zhipu("Z.ai", Color(0xFF2B66FF), Color(0xFFEBF1FF), R.drawable.ic_ai_zhipu),
    ModelScope("ModelScope", Color(0xFF624AFF), Color(0xFFF1EFFF), R.drawable.ic_ai_modelscope),
    Mistral("Mistral", Color(0xFFFD5900), Color(0xFFFFF0EB), R.drawable.ic_ai_mistral),
    Xai("xAI", Color(0xFF111827), Color(0xFFF3F4F6), R.drawable.ic_ai_xai),
    OpenRouter("OpenRouter", Color(0xFF4F46E5), Color(0xFFEEF2FF), R.drawable.ic_ai_openrouter),
    Generic("AI", Color(0xFF6B7280), Color(0xFFF3F4F6), 0);

    companion object {
        fun detect(providerOrModel: String): AiBrand {
            val key = providerOrModel.lowercase()
            return when {
                key.contains("gemini") || key.contains("google") -> Google
                key.contains("chatgpt") || key.contains("openai") || key.contains("gpt") || key.contains("o1") || key.contains("o3") -> OpenAi
                key.contains("claude") || key.contains("anthropic") -> Anthropic
                key.contains("llama") || key.contains("meta") -> Meta
                key.contains("deepseek") -> DeepSeek
                key.contains("qwen") || key.contains("alibaba") -> Qwen
                key.contains("glm") || key.contains("zhipu") || key.contains("zai") || key.contains("z.ai") -> Zhipu
                key.contains("modelscope") || key.contains("magicube") -> ModelScope
                key.contains("mistral") || key.contains("mixtral") || key.contains("codestral") -> Mistral
                key.contains("grok") || key.contains("xai") -> Xai
                key.contains("openrouter") -> OpenRouter
                else -> Generic
            }
        }
    }
}

/**
 * Official office logo badge for AI providers and models.
 */
@Composable
fun AiLogo(
    providerOrModel: String,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
    showContainer: Boolean = true,
    corner: Dp = size * 0.28f,
) {
    val brand = AiBrand.detect(providerOrModel)
    val colors = MinaTheme.colors
    val containerBg = if (showContainer) {
        if (MinaTheme.isDark) brand.brandColor.copy(alpha = 0.22f) else brand.containerColor
    } else Color.Transparent

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(containerBg),
        contentAlignment = Alignment.Center,
    ) {
        if (brand.drawableResId != 0) {
            val iconSize = if (showContainer) size * 0.64f else size
            Image(
                painter = painterResource(brand.drawableResId),
                contentDescription = brand.brandName,
                modifier = Modifier.size(iconSize),
                contentScale = ContentScale.Fit,
            )
        } else {
            Icon(
                imageVector = MinaIcons.Spark,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}
