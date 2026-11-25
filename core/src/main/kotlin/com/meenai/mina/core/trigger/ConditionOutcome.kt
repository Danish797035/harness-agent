package com.meenai.mina.core.trigger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** The machine result of the condition check; neither failure to parse nor execution failure can be regarded as the condition not being met. */
sealed interface ConditionOutcome {
    data class Checked(val met: Boolean, val evidence: String?) : ConditionOutcome
    data class Error(val reason: String) : ConditionOutcome

    companion object {
        fun parse(answer: String): ConditionOutcome {
            val raw = answer.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val body = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: return Error("Condition check did not return a readable result")
            val met = (body["met"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
                ?: return Error("Condition check result missing met status")
            val evidence = (body["evidence"] as? JsonPrimitive)?.content?.take(500)
            return Checked(met, evidence)
        }
    }
}
