package com.meenai.mina.core.cap.mcp

import com.meenai.mina.core.action.ActionRisk
import com.meenai.mina.core.action.Risk as ApprovalRisk
import com.meenai.mina.core.capability.Capability
import com.meenai.mina.core.capability.CapabilityStatus
import com.meenai.mina.core.capability.PromptEnv
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Trust
import com.meenai.mina.core.observation.Untrusted
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** The transport layer only needs to implement these two operations; tools are called once for each Run start, and the directory remains unchanged thereafter. */
interface McpEndpoint {
    val serverId: String
    fun tools(): List<McpToolDescriptor>
    suspend fun call(name: String, arguments: JsonObject): McpResult
}

data class McpAnnotations(
    val readOnlyHint: Boolean? = null,
    val destructiveHint: Boolean? = null,
    val openWorldHint: Boolean? = null,
)

data class McpToolDescriptor(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val annotations: McpAnnotations = McpAnnotations(),
    val estimatedCostUnits: Long = 0,
)

data class McpResult(val text: String, val isError: Boolean = false, val costUnits: Long = 0)

/** Each service is configured independently; Restricted does not respect the read-only annotation that the service claims. */
enum class McpTrust { Restricted, TrustAnnotations }

/** The full text of long results is saved by the host; the returned reference contains only the file path or resource ID. */
fun interface McpResultStore { fun save(serverId: String, toolName: String, content: String): String }

data class McpConnection(
    val endpoint: McpEndpoint,
    val resultStore: McpResultStore? = null,
    val maxCostUnits: Long? = null,
    val previewChars: Int = 4000,
    val trust: McpTrust = McpTrust.Restricted,
) {
    init { require(previewChars > 0 && (maxCostUnits == null || maxCostUnits >= 0)) }
}

/** If there is no read-only annotation, it will be treated as writable; destructive and open-world operations will be recorded as irreversible and external (the approval record will know that the task has done external things). */
fun mcpApprovalRisk(a: McpAnnotations, trust: McpTrust = McpTrust.TrustAnnotations): ApprovalRisk = when {
    a.destructiveHint == true -> ApprovalRisk(ActionRisk.Irreversible)
    a.openWorldHint == true -> ApprovalRisk(ActionRisk.External)
    a.readOnlyHint == true && trust == McpTrust.TrustAnnotations -> ApprovalRisk(ActionRisk.Read)
    else -> ApprovalRisk(ActionRisk.Change)
}

internal class McpCapability(connection: McpConnection, override val id: String) : Capability {
    private val endpoint = connection.endpoint
    private val budget = McpBudget(connection.maxCostUnits)
    private val tools = endpoint.tools().toList().map { McpClientTool(id, endpoint, it, budget, connection.resultStore, connection.previewChars, connection.trust) }
    override fun status(): CapabilityStatus = CapabilityStatus.Available
    override fun tools(): List<AgentTool> = tools
    override fun sections(env: PromptEnv): List<String> = emptyList()
}

private class McpBudget(private val limit: Long?) {
    private var used = 0L
    @Synchronized fun charge(units: Long): Boolean {
        if (units < 0 || limit != null && units > limit - used) return false
        used += units
        return true
    }
}

private class McpClientTool(
    override val capability: String,
    private val endpoint: McpEndpoint,
    private val descriptor: McpToolDescriptor,
    private val budget: McpBudget,
    private val resultStore: McpResultStore?,
    private val previewChars: Int,
    trust: McpTrust,
) : AgentTool {
    private val schema = descriptor.inputSchema
    private val approval = mcpApprovalRisk(descriptor.annotations, trust)
    override val def: ToolDef = ToolDef(
        "mcp_${endpoint.serverId.replace(Regex("[^A-Za-z0-9_]"), "_")}__${descriptor.name.replace(Regex("[^A-Za-z0-9_]"), "_")}",
        descriptor.description,
        schema["properties"]?.jsonObject.orEmpty().mapValues { it.value.jsonObject },
        schema["required"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
    )
    override val verb = Verb.Interact
    override val risk = when (approval.level) {
        ActionRisk.Read -> Risk.Read
        ActionRisk.Change -> Risk.Write
        else -> Risk.External
    }
    override val approvalRisk: ApprovalRisk = approval
    override val offScreen = true
    override val logged = risk != Risk.Read

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        if (!budget.charge(descriptor.estimatedCostUnits)) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "mcp_cost_limit")
        })
        val result = endpoint.call(descriptor.name, a.o)
        val long = result.text.length > previewChars
        val ref = if (long) resultStore?.save(endpoint.serverId, descriptor.name, result.text) else null
        if (long && ref == null) return ToolExecution(buildJsonObject {
            put("ok", false); put("error", "mcp_result_too_large")
        })
        val preview = if (long) result.text.take(previewChars) else result.text
        return ToolExecution(buildJsonObject {
            put("ok", !result.isError)
            put("text", Untrusted.wrap(preview))
            ref?.let { put("fullResult", it) }
            if (long) put("truncated", true)
        }, observations = listOf(Observation(ObsKind.Output, preview, source = capability, trust = Trust.Untrusted)))
    }
}
