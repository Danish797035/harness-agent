package com.meenai.mina.core.agent

import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.memory.MemoryItem
import com.meenai.mina.core.memory.MemoryPersistence
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.memory.UserUtterances
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.cap.sandbox.FakeSandbox
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.testing.PromptSurface
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.vision.JvmTestImageOps
import com.meenai.mina.core.vision.VisionService
import com.meenai.mina.core.vision.says
import com.meenai.mina.core.cap.web.FakeWebPort
import kotlinx.coroutines.runBlocking
import org.junit.Test
import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.knowledge.KnowledgeStore

/**
 * All hint surfaces that the model can see: complete system hints for each capability combination,
 * definitions of each tool (name, description, parameter schema), hints for internal roles. The golden
 * sample is in core/src/test/resources/prompt-surface/; it must be verbatim before and after
 * reconstruction. Regenerate:./gradlew:core:test --tests '*PromptSurface*' -PupdateSnapshots
 */
class PromptSurfaceTest {
    private class Mem : MemoryPersistence {
        override fun load(): List<MemoryItem> = emptyList()
        override fun save(items: List<MemoryItem>) {}
    }

    private class Combo(val tools: () -> ToolDispatcher, val flags: SpeedFlags = SpeedFlags.OFF, val memory: MemoryStore? = null)

    private val bundled = KnowledgeStore(KnowledgeStore.loadBundled())
    private val LAYERS = linkedMapOf("chat" to emptyList(), "phone" to listOf("phone"), "web" to listOf("web"), "all" to listOf("phone", "web", "sandbox"))
    private val combos: Map<String, Combo> = linkedMapOf(
        "phone" to Combo({ toolDispatcher(FakeScreen()) }),
        "phone-inline-batch" to Combo({ toolDispatcher(FakeScreen(), inlineScreen = true) }, SpeedFlags(inlineScreen = true, batchSteps = true)),
        "phone-web" to Combo({ toolDispatcher(FakeScreen(), web = FakeWebPort()) }),
        "phone-sandbox-offline" to Combo({ toolDispatcher(FakeScreen(), sandbox = FakeSandbox(), workspace = { "w" }) }),
        "phone-web-sandbox-online" to Combo({
            toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(SandboxStatus.Ready(network = true)), workspace = { "w" })
        }),
        "phone-sandbox-preparing" to Combo({ toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.NotInstalled), workspace = { "w" }) }),
        "everything" to MemoryStore(Mem(), clock = { 1L }, ids = { "m" }).let { mem ->
            Combo({
                toolDispatcher(
                    FakeScreen(), visionEnabled = true, skills = GuideCatalog.loadBundled(), vision = VisionService(says(), JvmTestImageOps),
                    web = FakeWebPort(), inlineScreen = true, deepLinks = true,
                    memory = mem, utterances = UserUtterances(), sandbox = FakeSandbox(SandboxStatus.Ready(network = false)), workspace = { "w" },
                    personalSkills = com.meenai.mina.core.skills.InMemoryPersonalSkills(),
                    // Records of subtasks and previous rounds: history_read is available only when there
                    // has been a previous run.
                    runSpec = com.meenai.mina.core.run.RunSpec(com.meenai.mina.core.run.RunId("t"), "t", "thread",
                        priorTurns = listOf(Turn("之前", "好", "succeeded", runId = "r1"))),
                    childHost = com.meenai.mina.core.child.ChildRunHost { spec, _ ->
                        com.meenai.mina.core.child.ChildResult(spec.id, com.meenai.mina.core.child.ChildPhase.Completed, "") },
                    runRecords = NoRecords,
                )
            }, SpeedFlags(inlineScreen = true, batchSteps = true, deepLinks = true), mem)
        },
    )

    /** When [open], three groups were used in the previous round: they were all unfolded at the beginning (provided by this task); otherwise, they were all closed, with only the entrance. */
    private fun offered(c: Combo, open: Boolean = true): FakeRequestCapture = runBlocking {
        val m = FakeRequestCapture()
        AgentLoop(m, c.tools(), SystemPrompt.identity(), flags = c.flags, memory = c.memory)
            .run("t", if (open) listOf(Turn("之前", "好", "succeeded", groups = listOf("phone", "web", "sandbox"))) else emptyList())
        m
    }

    /** The system prompts when each group of each combination is expanded (see layeredSurfaces for the collapsed state). */
    @Test fun systemPromptPerCombination() {
        for ((name, c) in combos) {
            val m = offered(c)
            PromptSurface.check("system/$name", PromptSurface.system(m.system!!, m.tools))
        }
    }

    @Test fun toolDefinitions() {
        // The tool with the same name has several screens and may have several definitions (one more
        // surface parameter) according to this task. There is a short description when the entry tool is
        // closed: each type is listed.
        val all = combos.values.flatMap { offered(it).tools + offered(it, open = false).tools }
            .distinctBy { it.name + it.description + it.parametersJsonSchema }.sortedBy { it.name }
        PromptSurface.check("tools", PromptSurface.tools(all))
    }

    /**
     * The full picture of the first request in each situation after layering (system prompt word + the
     * full text of each tool's definition), the combination is everything, and no confirmation tools are
     * given according to the product path: chat only chats (all are collected), phone / web which group
     * was used in the previous round (expanded at the start), and all three groups are expanded. Sizes
     * are written separately in layers/sizes.
     */
    @Test fun layeredSurfaces() {
        val c = combos.getValue("everything")
        val sizes = StringBuilder()
        for ((name, groups) in LAYERS) {
            val m = FakeRequestCapture()
            val prior = if (groups.isEmpty()) emptyList() else listOf(Turn("之前", "好", "succeeded", groups = groups))
            runBlocking { AgentLoop(m, c.tools(), SystemPrompt.identity(), flags = c.flags, memory = c.memory, confirmations = null).run("t", prior) }
            PromptSurface.check("layers/$name", PromptSurface.layer(m.system!!, m.tools))
            val system = m.system!!.toByteArray().size
            val tools = m.tools.sumOf { (it.name + it.description + it.parametersJsonSchema).toByteArray().size }
            val tokens = TokenEstimate.of(listOf(com.meenai.mina.core.model.Message(com.meenai.mina.core.model.Role.User,
                listOf(Part.Text(m.system!! + m.tools.joinToString("") { it.name + it.description + it.parametersJsonSchema })), com.meenai.mina.core.model.Author.Mina)))
            sizes.appendLine("$name: system $system bytes, ${m.tools.size} tools $tools bytes, total ${system + tools} bytes, ~$tokens tokens")
        }
        print(sizes)
        PromptSurface.check("layers/sizes", sizes.toString())
    }

    @Test fun rolePrompts() {
        PromptSurface.check("roles", PromptSurface.roles())
    }

    @Test fun feedbackFromTools() {
        PromptSurface.check("feedback-tools", runBlocking { PromptSurfaceFeedback.tools() })
    }

    @Test fun feedbackFromLoop() {
        PromptSurface.check("feedback-loop", PromptSurfaceFeedback.loop())
    }

    private object NoRecords : com.meenai.mina.core.record.RunRecords {
        override suspend fun summary(runId: String): com.meenai.mina.core.record.RunSummary? = null
        override suspend fun events(runId: String) = emptyList<com.meenai.mina.core.record.RunEvent>()
    }

    /** Only one finish: write down the system prompt words and tools for the request to start work. */
    private class FakeRequestCapture : com.meenai.mina.core.model.ChatModel {
        override val capabilities = com.meenai.mina.core.model.Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false)
        var system: String? = null
        var tools: List<com.meenai.mina.core.model.ToolSpec> = emptyList()
        override suspend fun complete(system: String, messages: List<com.meenai.mina.core.model.Message>, tools: List<com.meenai.mina.core.model.ToolSpec>): ModelReply {
            if (this.system == null) { this.system = system; this.tools = tools }
            return com.meenai.mina.core.testing.answerReply("ok")
        }
    }
}
