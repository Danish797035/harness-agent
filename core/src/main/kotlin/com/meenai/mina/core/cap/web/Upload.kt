package com.meenai.mina.core.cap.web

import com.meenai.mina.core.risk.GateTarget
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.meenai.mina.core.workspace.WorkspacePathCheck
import com.meenai.mina.core.uistate.action
import com.meenai.mina.core.uistate.session

/** The page script gives the file selection control (input type=file) the role. */
private const val FILE_ROLE = "file"

/** Pass the file in the workspace to the file control on the page; click on the file control, which is as irreversible as clicking. */
internal object Upload : WebTool {
    override val verb = Verb.Upload
    override val label = "Upload file"
    override val risk = Risk.External
    override val gateTarget = GateTarget.FileControl
    override val logged = true
    override val wideTarget = true
    override val def = ToolDef("web_upload",
        Prompts.tool("web_upload", "max_files" to UPLOAD_MAX_FILES, "workspace" to WORKSPACE_PREFIX),
        mapOf(
            "ref" to str("ref of the file control from the latest web_observe/web_list_elements."),
            "snapshotId" to str("snapshotId of that web_observe/web_list_elements result."),
            "paths" to buildJsonObject {
                put("type", "array"); put("description", "1-$UPLOAD_MAX_FILES workspace paths, e.g. /workspace/inbox/photo.jpg.")
                put("minItems", 1); put("maxItems", UPLOAD_MAX_FILES); put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("ref", "snapshotId", "paths"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ref = a.ref()!!
        val paths = a.strList("paths", 1, UPLOAD_MAX_FILES)!!.map(String::trim)
        val target = ctx.session.recentSnapshots.lastOrNull { it.snapshotId == ref.second }?.elements?.firstOrNull { it.ref == ref.first }
        if (target != null && target.role != FILE_ROLE) {
            throw ArgError("ref", "${ref.first} is a ${target.role}, not a file control; pass the ref of the file input (role $FILE_ROLE)")
        }
        // If the work area is not checked, it will be regarded as out of bounds: it would rather not be
        // transmitted than the file outside the work area to the page.
        val pathCheck = ctx.workspacePath ?: { WorkspacePathCheck.Outside }
        for (p in paths) {
            val check = if (!workspaceShaped(p)) WorkspacePathCheck.Outside else pathCheck(p)
            when (check) {
                WorkspacePathCheck.Outside -> return fail("path_outside_workspace",
                    Feedback.format("upload.outside", "path" to p, "workspace" to WORKSPACE_PREFIX))
                WorkspacePathCheck.NotFound -> return fail("file_not_found", Feedback.format("upload.not_found", "path" to p))
                WorkspacePathCheck.Ok -> Unit
            }
        }
        return ctx.action(ctx.web!!.upload(ref.first, ref.second, paths))
    }

    /** /workspace/, no. and.. segments, and no path with consecutive slashes; the rest is left to the app side for normalization before judging. */
    private fun workspaceShaped(p: String): Boolean {
        if (!p.startsWith(WORKSPACE_PREFIX) || "//" in p) return false
        return p.removePrefix(WORKSPACE_PREFIX).split('/').none { it == "." || it == ".." || it.isEmpty() }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        (o["paths"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
}
