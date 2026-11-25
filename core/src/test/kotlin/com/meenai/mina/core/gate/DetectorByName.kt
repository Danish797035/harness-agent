package com.meenai.mina.core.gate

import com.meenai.mina.core.risk.GateTarget
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.tool.FakeScreen
import kotlinx.serialization.json.JsonObject
import com.meenai.mina.core.assembly.toolDispatcher

/** In the test, the judgment is based on the tool name: the target is the one declared by the tool (the same as the one passed to the judgment at the execution entry). */
private val declarations by lazy { toolDispatcher(FakeScreen()) }

internal fun gateTargetOf(tool: String): GateTarget? = declarations.gateTarget(tool)

internal fun IrreversibleDetector.needsCheck(tool: String, args: JsonObject) = needsCheck(gateTargetOf(tool), args)

internal fun IrreversibleDetector.classify(tool: String, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null) =
    classify(gateTargetOf(tool), args, snapshot, fresh)

internal fun IrreversibleDetector.unresolved(tool: String, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null) =
    unresolved(gateTargetOf(tool), args, snapshot, fresh)
