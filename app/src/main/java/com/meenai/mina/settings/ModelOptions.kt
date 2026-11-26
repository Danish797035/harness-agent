package com.meenai.mina.settings

import androidx.compose.runtime.Immutable
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.router.picker.ChatModelFilter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * An optional model in the list. [capabilities] is the merged capability: those provided by the
 * interface will still be used, and those not provided will continue to use the default initial value;
 * [toolCallsKnown] and [visionKnown] note which ones are explicitly stated by the interface (the
 * interface will be marked "Unconfirmed" accordingly). [chat] is an interface, but it does not say it is
 * a conversation model (see [ListedModel.chat]), and it is null if it does not. [typed] is not in the
 * listed model and is filled in manually by the user (marked "Hand-filled" on the interface).
 * [visionByUser] is what the user said about whether the picture can be viewed (see
 * [Provider.visionOverrides]). If it is not said, it is null; if it is not said, it is included in
 * [capabilities].
 */
@Immutable
data class ModelOption(
    val id: String,
    val capabilities: Capabilities,
    val toolCallsKnown: Boolean,
    val visionKnown: Boolean,
    val contextKnown: Boolean,
    val displayName: String = "",
    val chat: Boolean? = null,
    val typed: Boolean = false,
    val visionByUser: Boolean? = null,
) {
    /** Capability flags for reference; users do not filter by them when selecting models. */
    val canBeMain: Boolean get() = capabilities.toolCalls
    val canSee: Boolean get() = capabilities.vision
}

object ModelOptions {
    /**
     * Merges listed models with preset abilities. The main model can be placed at the front of the
     * queue, and the rest can remain in the order given by the service provider. Models that only write
     * interface protocols that Mina cannot speak about (see [ListedModel.usable]) will inevitably fail
     * to be called and will not be listed.
     */
    fun merge(listed: List<ListedModel>, base: Capabilities): List<ModelOption> = listed.filter { it.usable }.map { m ->
        val isImageEditOrGen = ChatModelFilter.isImageEditOrGen(m.id)
        val visionEffective = if (isImageEditOrGen) false else (m.vision ?: base.vision)
        val visionKnownEffective = if (isImageEditOrGen) false else (m.vision != null)
        ModelOption(
            id = m.id,
            capabilities = base.copy(
                toolCalls = m.toolCalls ?: base.toolCalls,
                vision = visionEffective,
                contextTokens = m.contextTokens ?: base.contextTokens,
                reasoning = m.reasoning ?: base.reasoning,
                // Whether the positioning is accurate after looking at the picture can only be
                // determined by actual testing. All new connections have not been verified.
                groundingVerified = false,
            ),
            toolCallsKnown = m.toolCalls != null,
            visionKnown = visionKnownEffective,
            contextKnown = m.contextTokens != null,
            displayName = m.displayName,
            chat = m.chat,
        )
    }.sortedBy { if (it.canBeMain) 0 else 1 }

    /**
     * The order of using it as the main model and trying to call it: as long as it can be used as the
     * main model and is not used for other purposes (see [looksNonChat]); first (the model name or
     * display name has the words in [familyTokens], see [ProviderPreset.familyTokens]), and then the
     * interface clearly says that the tool can be called; in the same file of your own, the version in
     * the model name is the latest first (see [ModelVersion]: The official version of the same version
     * comes first, and the version that cannot be read is listed after the one that can be read), and
     * the rest are in the order of the list. The order of the service provider list is usually with
     * older models first, which may have been offline; models from other companies each have their own
     * version numbers and are not compared together.
     */
    fun candidates(options: List<ModelOption>, familyTokens: List<String> = emptyList()): List<String> =
        options.filter { it.canBeMain && !looksNonChat(it) }
            .map { Triple(it, inFamily(it, familyTokens), ModelVersion.of(it.id)) }
            .sortedWith(
                compareBy<Triple<ModelOption, Boolean, ModelVersion>>({ if (it.second) 0 else 1 }, { if (it.first.toolCallsKnown) 0 else 1 })
                    .thenComparator { a, b -> if (a.second) ModelVersion.NEWEST_FIRST.compare(a.third, b.third) else 0 },
            )
            .map { it.first.id }

    /** The main model selected by default: the first one of [candidates]; if there is no one, choose the first one that can be the main model. */
    fun defaultMain(options: List<ModelOption>, familyTokens: List<String> = emptyList()): String? =
        candidates(options, familyTokens).firstOrNull() ?: options.firstOrNull { it.canBeMain }?.id

    /** The default own model word with a matching address; if the address is not recognized, it is empty. */
    fun familyOf(baseUrl: String): List<String> = ProviderPresets.forBaseUrl(baseUrl.trim())?.familyTokens.orEmpty()

    private fun inFamily(o: ModelOption, tokens: List<String>): Boolean =
        tokens.any { t -> o.id.contains(t, ignoreCase = true) || o.displayName.contains(t, ignoreCase = true) }

    /**
     * It doesn’t look like a conversational model (vector, rearrangement, voice, drawing, review...):
     * The interface mentioned is the interface, but it is not mentioned that the model name is divided
     * into segments separated by punctuation and slashes. Even if one segment starts with the words in
     * [NON_CHAT_WORDS].
     */
    fun looksNonChat(o: ModelOption): Boolean =
        ChatModelFilter.isExcluded(o.id, o.displayName) ||
            o.chat?.not() ?: o.id.lowercase().split(Regex("[^a-z0-9]+")).any { part -> NON_CHAT_WORDS.any { part.startsWith(it) } }

    private val NON_CHAT_WORDS = listOf(
        "embed", "rerank", "tts", "whisper", "transcribe", "speech", "audio", "realtime", "image", "dall", "moderation", "ocr", "asr",
    )

    /** Short form of context length: 64K, 1M. */
    fun contextLabel(tokens: Int): String = when {
        tokens >= 1_000_000 && tokens % 1_000_000 < 50_000 -> "${tokens / 1_000_000}M"
        tokens >= 1_000_000 -> String.format("%.1fM", tokens / 1_000_000.0)
        tokens >= 1000 -> if (tokens % 1024 == 0) "${tokens / 1024}K" else "${(tokens + 500) / 1000}K"
        else -> "$tokens"
    }

    /**
     * Whether a model can be used to view pictures: what the user has said on this model ([userSaid])
     * shall prevail; if it has not been mentioned, it will also be used for viewing pictures
     * ([usedForVision]: select it as a model for viewing pictures, or "use the main model" for viewing
     * pictures), it means that the user said it can be viewed; if there is no such situation, press
     * [stored] (what is stated in the list, what is not written is the default value).
     */
    fun seesImages(stored: Boolean, userSaid: Boolean?, usedForVision: Boolean): Boolean = userSaid ?: (usedForVision || stored)

    /** The initial value of the service provider's capabilities: use the default if the address matches the default, otherwise use the "custom" one. */
    fun baseOf(p: Provider): Capabilities = (ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.custom).capabilities

    /**
     * All models available for a service provider: memorized list (combined initial value of
     * capabilities; if already configured, use the capabilities in the configuration), plus models that
     * have been configured but are not in the list (such as manually filled in), and those that have
     * been manually filled in and not yet configured ([Provider.typedModels]). The user said whether the
     * picture can be viewed ([Provider.visionOverrides]) depends on what the user said.
     */
    fun ofProvider(p: Provider, configs: List<ProviderConfig>): List<ModelOption> = listedOf(p, configs).map { o ->
        val said = p.visionOverrides[o.id] ?: return@map o
        o.copy(capabilities = o.capabilities.copy(vision = said), visionByUser = said)
    }

    private fun listedOf(p: Provider, configs: List<ProviderConfig>): List<ModelOption> {
        val saved = configs.filter { it.providerId == p.id }
        val byModel = saved.associateBy { it.model }
        val listed = merge(p.models.orEmpty(), baseOf(p)).map { o -> byModel[o.id]?.let { o.copy(capabilities = it.capabilities) } ?: o }
        val rest = saved.filter { c -> listed.none { it.id == c.model } }.map {
            // For those companies that cannot list models, the models are all filled in by hand; for
            // those that have a list, those that later disappear from the list are not counted by hand.
            val typed = it.model in p.typedModels || p.models?.isEmpty() == true
            ModelOption(it.model, it.capabilities, toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = typed)
        }
        // Those that have been filled in by hand and have not been used for any purpose (no
        // configuration): list them as usual, and can be based on the initial value of this company
        val typed = p.typedModels.filter { m -> listed.none { it.id == m } && rest.none { it.id == m } }.sorted().map {
            ModelOption(it, baseOf(p), toolCallsKnown = false, visionKnown = false, contextKnown = false, typed = true)
        }
        return listed + rest + typed
    }
}

/**
 * The version read from the model name is only used to rank models from the same company, not to judge
 * which one (flash, pro, lite...) is better. The model name is cut into segments according to `-` `_`
 * `:` `/` (lowercase, decimal point retained):
 * - - [numbers] is the first segment like a version number: 1–2 digits, with decimals (`2.5`, `3.1`),
 * letters in front (`qwen3`, `v3.2`, `k2`, `x4`),
 * It can be followed by a letter (`4o`, `1v`); what ends with b / k / m is the parameter amount or
 * context length (`72b`, `8k`), which does not count. The following 1–2 digit pure numeric fields are
 * connected into minor versions (`claude-3-5` → 3.5, `seed-1-6` → 1.6).
 * - - [date] is the date or snapshot number composed of the remaining pure numeric fields: year, month
 * and day with 4 digits (`-2025-08-07`, `-09-2025`),
 * Otherwise, the numbers as written (`-0827`, `-20241022`, `-001`; the 6-digit ones are regarded as the
 * dates with two digits in the year); if not, it is null.
 * - - [stage]: 0 official version; 1 `latest`, this type of alias that will change; 2 preview, test
 * (`preview`, `exp`, `beta`, `alpha`, `rc`...).
 * If there is neither version number nor date, it cannot be read ([parsed] is false).
 */
internal data class ModelVersion(val numbers: List<Int>, val stage: Int, val date: Long?) {
    val parsed: Boolean get() = numbers.isNotEmpty() || date != null

    companion object {
        /** The one that can be read is first; the one with the larger version is first; the official version, alias, and preview of the same version are in order; the one without a date (mostly pointing to the latest snapshot) is first, and the new one by date is first. */
        val NEWEST_FIRST: Comparator<ModelVersion> = Comparator { a, b ->
            when {
                a.parsed != b.parsed -> if (a.parsed) -1 else 1
                !a.parsed -> 0
                else -> compareNumbers(b.numbers, a.numbers).takeIf { it != 0 }
                    ?: a.stage.compareTo(b.stage).takeIf { it != 0 }
                    ?: compareValues(b.date ?: Long.MAX_VALUE, a.date ?: Long.MAX_VALUE)
            }
        }

        fun of(id: String): ModelVersion {
            val parts = id.lowercase().split(Regex("[^a-z0-9.]+")).filter { it.isNotEmpty() }
            var numbers = emptyList<Int>()
            var at = -1
            for ((i, p) in parts.withIndex()) {
                val m = VERSION.matchEntire(p) ?: continue
                if (m.groupValues[3] in SIZE_SUFFIXES) continue
                numbers = m.groupValues[2].split('.').map { it.toInt() }
                at = i
                break
            }
            var end = at + 1
            if (at >= 0 && numbers.size == 1) {
                // Small versions separated by horizontal lines: claude-3-5, seed-1-6
                while (end < parts.size && numbers.size < 3 && MINOR.matches(parts[end])) numbers = numbers + parts[end++].toInt()
            }
            val rest = parts.filterIndexed { i, _ -> i < at || i >= end }
            val stage = when {
                rest.any { p -> PRE_RELEASE.any { p.startsWith(it) } } -> 2
                "latest" in rest -> 1
                else -> 0
            }
            return ModelVersion(numbers, stage, dateOf(rest.filter { p -> p.all { it.isDigit() } }))
        }

        private fun dateOf(digits: List<String>): Long? {
            if (digits.isEmpty()) return null
            val year = digits.firstOrNull { it.length == 4 && it.toInt() in 1990..2099 }
            if (year != null) {
                val md = (digits - year).take(2).map { it.take(2).toLong() }
                return year.toLong() * 10_000 + (md.getOrNull(0) ?: 0) * 100 + (md.getOrNull(1) ?: 0)
            }
            val joined = digits.joinToString("").take(14)
            return if (digits.size == 1 && joined.length == 6) 20_000_000 + joined.toLong() else joined.toLong()
        }

        private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
            for (i in 0 until maxOf(a.size, b.size)) {
                val c = (a.getOrNull(i) ?: 0).compareTo(b.getOrNull(i) ?: 0)
                if (c != 0) return c
            }
            return 0
        }

        private val VERSION = Regex("([a-z]*)((?:0|[1-9]\\d?)(?:\\.\\d{1,3})*)([a-z]?)")
        private val MINOR = Regex("[1-9]?\\d")
        private val SIZE_SUFFIXES = setOf("b", "k", "m")
        private val PRE_RELEASE = listOf("preview", "exp", "beta", "alpha", "rc", "dev", "test")
    }
}

/** Grouping rules and display names of service providers: The same interface type, the same address, and the same name count as the same service provider. */
object ProviderConnection {
    fun keyOf(kind: ProviderConfig.Kind, baseUrl: String, name: String) = "${kind.name}|${normalizeUrl(baseUrl)}|${name.trim()}"

    /** If there is no name, use the host name of the address. */
    fun displayName(c: ProviderConfig): String = c.name.ifBlank { nameForUrl(c.baseUrl) }

    fun nameForUrl(baseUrl: String): String =
        ProviderPresets.forBaseUrl(baseUrl)?.label ?: baseUrl.trim().toHttpUrlOrNull()?.host ?: baseUrl.trim()

    fun normalizeUrl(url: String) = url.trim().trimEnd('/')
}
