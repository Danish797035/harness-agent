package com.meenai.mina.core.router

import com.meenai.mina.core.model.Usage

/**
 * Represents a model switch event inside FreeKeyRouter.
 */
data class RouterSwitchEvent(
    val oldModel: String?,
    val newModel: String,
    val provider: String,
    val reason: String,
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * State of the FreeKeyRouter exposed for UI and telemetry.
 *
 * @param currentModel Currently active model ID.
 * @param provider Active model provider name.
 * @param reason Reason for current selection or recent switch.
 * @param usage Aggregated or last token usage.
 * @param switchEvents Chronological list of switch events in the current session.
 */
data class RouterStatus(
    val currentModel: String? = null,
    val provider: String? = null,
    val reason: String? = null,
    val usage: Usage? = null,
    val switchEvents: List<RouterSwitchEvent> = emptyList(),
)
