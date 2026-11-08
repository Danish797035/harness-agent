package com.meenai.mina.core.router.picker

/**
 * Parsed representation of model parameter size.
 *
 * @property totalParamsB Total parameters in billions (e.g. 397.0 for 397B-A17B, 70.0 for 70B). Null if unknown.
 * @property activeParamsB Active parameters in billions for MoE models (e.g. 17.0 for 397B-A17B).
 */
data class ParsedModelSize(
    val totalParamsB: Double?,
    val activeParamsB: Double? = null,
) {
    val isKnown: Boolean get() = totalParamsB != null

    val sizeLabel: String get() = when {
        totalParamsB != null && activeParamsB != null && activeParamsB != totalParamsB ->
            "${format(totalParamsB)}-A${format(activeParamsB)}"
        totalParamsB != null -> format(totalParamsB)
        else -> "Unknown"
    }

    companion object {
        fun format(v: Double): String =
            if (v % 1.0 == 0.0) "${v.toLong()}B" else "${v}B"
    }
}

/**
 * Robust parameter size parser for LLM models.
 * Parses parameter sizes dynamically from model ID, name, and description WITHOUT hardcoding model names.
 *
 * Supported formats:
 * - MoE active notation: 397B-A17B, 671B-A37B, 236B-A21B -> total: 397.0, active: 17.0
 * - MoE expert notation: 8x7B, 8x22B -> total: 56.0, active: 14.0
 * - Standard billions: 70B, 72B, 32B, 14B, 8B, 3B, 1B, 0.5B
 * - Standard millions: 350M, 400M -> 0.35B, 0.4B
 * - If size is not found: returns null (Unknown, NOT 0).
 */
object ModelSizeParser {

    // 397B-A17B or 671B-A37B or 397b-a17b or 397B_A17B
    private val MOE_ACTIVE_REGEX = Regex(
        """(?i)(?:^|[\s/_\-:])(\d+(?:\.\d+)?)\s*[bB]\s*[-_]?\s*[aA](\d+(?:\.\d+)?)\s*[bB]?(?:$|[\s/_\-:])"""
    )

    // 8x7B or 8x22B
    private val MOE_X_REGEX = Regex(
        """(?i)(?:^|[\s/_\-:])(\d+)\s*[xX]\s*(\d+(?:\.\d+)?)\s*[bB](?:$|[\s/_\-:])"""
    )

    // 70B, 32B, 8B, 0.5B
    private val STANDARD_B_REGEX = Regex(
        """(?i)(?:^|[\s/_\-:])(\d+(?:\.\d+)?)\s*[bB](?:$|[\s/_\-:])"""
    )

    // 350M, 400M -> 0.35B, 0.4B
    private val STANDARD_M_REGEX = Regex(
        """(?i)(?:^|[\s/_\-:])(\d+(?:\.\d+)?)\s*[mM](?:$|[\s/_\-:])"""
    )

    /**
     * Parses parameter size from model identifier, display name, and description.
     * Returns [ParsedModelSize] with null fields if no size is detected.
     */
    fun parse(modelId: String, name: String = "", description: String = ""): ParsedModelSize {
        val combined = "$modelId $name $description"

        // 1. Check for MoE active format like 397B-A17B
        val moeActive = MOE_ACTIVE_REGEX.find(combined)
        if (moeActive != null) {
            val total = moeActive.groupValues[1].toDoubleOrNull()
            val active = moeActive.groupValues[2].toDoubleOrNull()
            if (total != null && total in 0.1..2000.0) {
                return ParsedModelSize(totalParamsB = total, activeParamsB = active)
            }
        }

        // 2. Check for MoE format like 8x7B
        val moeX = MOE_X_REGEX.find(combined)
        if (moeX != null) {
            val count = moeX.groupValues[1].toDoubleOrNull()
            val each = moeX.groupValues[2].toDoubleOrNull()
            if (count != null && each != null) {
                val total = count * each
                if (total in 0.1..2000.0) {
                    return ParsedModelSize(totalParamsB = total, activeParamsB = each * 2.0)
                }
            }
        }

        // 3. Standard B format
        val standardB = STANDARD_B_REGEX.findAll(combined)
            .mapNotNull { m -> m.groupValues[1].toDoubleOrNull() }
            .firstOrNull { it in 0.1..2000.0 }
        if (standardB != null) {
            return ParsedModelSize(totalParamsB = standardB, activeParamsB = standardB)
        }

        // 4. Standard M format (millions)
        val standardM = STANDARD_M_REGEX.findAll(combined)
            .mapNotNull { m -> m.groupValues[1].toDoubleOrNull() }
            .firstOrNull { it in 10.0..10000.0 }
        if (standardM != null) {
            val inB = standardM / 1000.0
            return ParsedModelSize(totalParamsB = inB, activeParamsB = inB)
        }

        // Size na mile to unknown, 0 nahi!
        return ParsedModelSize(totalParamsB = null, activeParamsB = null)
    }

    /**
     * Legacy extraction returning a Double (defaults to 0.0 if unknown).
     */
    fun extractParameterSizeB(modelId: String, name: String = "", description: String = ""): Double {
        return parse(modelId, name, description).totalParamsB ?: 0.0
    }
}
