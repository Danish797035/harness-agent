package com.meenai.mina.core.tool

import com.meenai.mina.core.model.Part
import kotlinx.serialization.json.JsonObject
import com.meenai.mina.core.assembly.toolDispatcher

/** In the test, the distributor is directly driven (testing the tool itself, not the execution sequence); the product code can only obtain the certificate through the execution entrance. */
internal suspend fun ToolDispatcher.execute(call: Part.ToolCall, step: Int? = null, sub: SubActions? = null): ToolExecution =
    execute(call, ActionPass(), step, sub)

/** Ask for the declaration of the tool by name in the test (the product code is asked through the distributor of this task). */
internal fun isWritingTool(name: String, @Suppress("UNUSED_PARAMETER") args: JsonObject): Boolean = toolDispatcher(FakeScreen()).writesScreen(name)

internal fun isSandboxTool(name: String): Boolean = toolDispatcher(FakeScreen()).index[name]?.capability == "sandbox"
