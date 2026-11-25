package com.meenai.mina.core.tool

import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.prompt.Prompts

/**
 * The tools for this task: [all] are all the tools that may be provided by each capability and core (in
 * the order of delivery to the model). The tools that can be called by this task are filtered by
 * configuration. Every time it is called, the tool is found by name and then executed. The distributor
 * does not know the specific tool. [groupOf] gives the group to which the tool belongs (see
 * [ToolGroups]; always null).
 */
internal class ToolRegistry(private val ctx: ToolContext, all: List<AgentTool>, private val groupOf: (AgentTool) -> String? = { null }) {
    private val declared: Map<String, AgentTool> = all.associateBy { it.name }
    private val tools: Map<String, AgentTool> = all.filter { it.available(ctx) }.associateBy { it.name }

    init {
        check(declared.size == all.size) { "duplicate tool names" }
    }

    operator fun get(name: String): AgentTool? = tools[name]

    /** Search the statement of the tool by name (risks, determination goals...). If this task does not provide it, you can also find it; if you don't know it, it will be null. */
    fun declared(name: String): AgentTool? = declared[name]

    /**
     * The tool definition of this Run (full text, short description of the entry, and the group it
     * belongs to) is frozen when the directory is fetched for the first time; subsequent port status
     * changes can only affect the execution, and the prompt word prefix cannot be changed. The short
     * description of the entry is in prompts/entries/<tool name>.md, and the parameters are the same as the
     * full text.
     */
    private val frozen: List<ToolGroups.Slot> by lazy {
        tools.values.filter { it.offered(ctx) }.map { t ->
            val d = t.def(ctx)
            ToolGroups.Slot(d.spec(), t.entry?.let { ToolDef(d.name, Prompts.entry(d.name), d.props, d.required).spec() }, groupOf(t))
        }
    }

    /** All tools provided for this task (expanded, full text for each group). */
    fun definitions(): List<ToolSpec> = frozen.map { it.full }

    /** Tools sent to the model at this time: Expand only the groups in [expanded] (see [ToolGroups.layout]). */
    fun layered(expanded: Set<String>, extra: Map<String, List<ToolSpec>>): List<ToolSpec> = ToolGroups.layout(frozen, expanded, extra)

    /** Group [group] contains the entry tool issued to the model. */
    fun hasEntry(group: String): Boolean = frozen.any { it.group == group && it.entry != null }

    /** Tool names assigned to the model in group [group] (in the order of registration). */
    fun members(group: String): List<String> = frozen.filter { it.group == group }.map { it.full.name }

    /** Tool names that can be put into do_steps (those not provided in this task are also listed), in the order of registration. */
    fun batchable(): List<String> = declared.values.filter { it.batchable }.map { it.name }

    /** The names listed in the description of "Unknown Tools": those that are available at any time (the group that does not move the screen to see if the operating environment is ready, is not listed). */
    fun listedNames(): List<String> = tools.values.filter { !it.offScreen }.map { it.name }
}
