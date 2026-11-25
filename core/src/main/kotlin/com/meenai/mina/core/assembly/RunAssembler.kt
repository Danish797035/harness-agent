package com.meenai.mina.core.assembly

import com.meenai.mina.core.capability.Capability
import com.meenai.mina.core.agent.CoreSections
import com.meenai.mina.core.general.GeneralGroup
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolConfig
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.uistate.Interfaces

/** The Provider opens the capabilities of each Run at the start; the Provider captures its own ports and resources without entering the core loop. */
internal interface CapabilityProvider {
    val id: String
    fun open(spec: RunSpec): Capability?
}

internal class RunAssembler(private val providers: List<CapabilityProvider>) {
    fun assemble(spec: RunSpec, config: ToolConfig, stableRefs: Boolean,
        formerPrefix: Map<String, String>, renamed: Map<String, String>,
        retired: Map<String, String>, moved: Map<String, List<String>>): ToolDispatcher {
        check(providers.map { it.id }.distinct().size == providers.size) { "duplicate capability provider ids" }
        val child = spec.provenance.origin == RunOrigin.Child
        val readOnly = spec.readOnly || child
        val capabilities = providers.filter { (spec.capabilities == null || it.id in spec.capabilities) &&
            (!child || it.id in setOf("web", "sandbox")) }
            .mapNotNull { it.open(spec) }
        val general = GeneralGroup.tools.filterNot { spec.provenance.attendance == Attendance.Unattended &&
            it.verb in setOf(Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser, Verb.ScheduleTask, Verb.ProfileEdit, Verb.SkillSave) }
            .filterNot { spec.provenance.origin == RunOrigin.Child && it.verb in setOf(Verb.SpawnAgent, Verb.MemorySave, Verb.ProfileEdit, Verb.ScheduleTask, Verb.SkillSave,
                Verb.AskUser, Verb.RequestConfirmation, Verb.HandToUser) }
            .filter { !readOnly || it.risk == Risk.Read }
        val capabilityTools = capabilities.flatMap { it.tools() }.filter { !readOnly || it.risk == Risk.Read ||
            it.capability == "web" && it.verb == Verb.Navigate }
        return ToolDispatcher(capabilities, general + capabilityTools,
            listOf(CoreSections) + capabilities, config, { ctx -> Interfaces.attach(ctx, capabilities.mapNotNull { it.surface }, stableRefs) },
            formerPrefix, renamed, retired, moved)
    }
}
