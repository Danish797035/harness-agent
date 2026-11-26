package com.meenai.mina.core.cap.sandbox

import com.meenai.mina.core.capability.Capability
import com.meenai.mina.core.capability.CapabilityKey
import com.meenai.mina.core.capability.CapabilityStatus
import com.meenai.mina.core.capability.PromptEnv
import com.meenai.mina.core.guard.OffScreenGuard
import com.meenai.mina.core.knowledge.ScriptManifest
import com.meenai.mina.core.knowledge.ScriptState
import com.meenai.mina.core.knowledge.BundledScripts
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.workspace.SandboxPaths

/**
 * Linux sandbox: commands, skill scripts, workspace files. The tool is provided when the device supports
 * it (the tool call will wait for it when the running environment is not ready); it is not a screen
 * operation, but a lock screen level, and does not enter the screen guard, and is handled separately by
 * [SandboxGuard].
 */
internal class SandboxCapability(
    private val port: SandboxPort?,
    /** The workspace (conversation ID) of this task. */
    workspace: () -> String,
    visionEnabled: Boolean,
    onCommand: (String) -> Unit,
    onProgress: (String) -> Unit,
    onPreparing: () -> Unit,
    onScript: ((String) -> Unit)?,
    visionCheck: (() -> Boolean)? = null,
) : Capability {
    override val id = SANDBOX

    val runner = port?.let { SandboxTools(it, workspace, visionEnabled, onCommand = onCommand, onProgress = onProgress, onPreparing = onPreparing, onScript = onScript, visionCheck = visionCheck) }

    /** The device supports sandboxing (tools are provided and may be in preparation). */
    fun available(): Boolean = runner?.available() == true

    override val folds = true

    override fun status(): CapabilityStatus = when (val s = port?.status()) {
        null -> CapabilityStatus.Unavailable("no sandbox")
        is SandboxStatus.Ready -> CapabilityStatus.Available
        is SandboxStatus.NotInstalled -> CapabilityStatus.Degraded("not installed")
        is SandboxStatus.Installing -> CapabilityStatus.Degraded(s.step)
        is SandboxStatus.Unsupported -> CapabilityStatus.Unavailable(s.reason)
        is SandboxStatus.Broken -> CapabilityStatus.Unavailable(s.reason)
    }

    override fun tools(): List<AgentTool> = SandboxGroup.tools

    /** This time, with the files in the workspace (the attachment line says the path under /workspace/): expand the sandbox group at the beginning. */
    override fun preload(task: String): Boolean = available() && "${SandboxPaths.WORKSPACE}/" in task

    /** The sandbox group will not be given while they are being kept. */
    override fun sections(env: PromptEnv): List<String> {
        if (!env.isOpen(id) || SandboxGroup.tools.none { it.name in env.offered }) return emptyList()
        val p = port?.takeIf { available() }
        return listOf(env.gate(section(p?.status() ?: SandboxStatus.NotInstalled, p?.networkAllowed() == true)))
    }

    override val scripts = object : BundledScripts {
        override fun offered() = available()

        override suspend fun ready(): Boolean {
            val p = port ?: return false
            if (p.status() !is SandboxStatus.Ready) return false
            p.prepare()
            return true
        }

        override fun scripts(skillDir: String): List<ScriptState> {
            val network = (port?.status() as? SandboxStatus.Ready)?.network == true
            return port?.scriptManifest(skillDir).orEmpty().map { e ->
                val (run, reason) = ScriptManifest.effectiveRunnable(e, network)
                ScriptState(e.file, run, reason)
            }
        }
    }

    override fun onTaskStart(ctx: ToolContext) {
        ctx[KEY] = this
    }

    override fun guard(): OffScreenGuard = SandboxGuard()

    companion object {
        val KEY = CapabilityKey<SandboxCapability>(SANDBOX)

        /** Sandbox paragraph: Indicate whether you are connected to the Internet at the moment ([network]: user's networking settings); add one more sentence when the running environment is not ready yet, and you will wait for it when using the tool for the first time. */
        fun section(status: SandboxStatus, network: Boolean): String {
            val s = Prompts.capability(SANDBOX).replace("{{network}}", if (network) NETWORK_ON else NETWORK_OFF)
            return if (status is SandboxStatus.Ready) s else "$s\n- $PREPARING"
        }

        const val PREPARING = "The environment is still being set up in the background. The first tool call waits for it (up to about two minutes); if it is still not ready, the tool says so."

        const val NETWORK_ON = "on. The user allowed the sandbox to use the network; anything you send out leaves the phone, so only fetch what the task needs."
        const val NETWORK_OFF = "off. Commands cannot reach the internet (no pip/npm/apk installs, no downloads). If the task really needs the network, tell the user they can turn on \"Allow scripts to go online\" (允许脚本联网) in Harness Agent's settings."
    }
}

/** The sandbox for execution of this task; null if there is no sandbox. */
internal val ToolContext.sandboxRunner: SandboxTools? get() = this[SandboxCapability.KEY]?.runner

internal fun ToolContext.sandboxAvailable(): Boolean = this[SandboxCapability.KEY]?.available() == true
