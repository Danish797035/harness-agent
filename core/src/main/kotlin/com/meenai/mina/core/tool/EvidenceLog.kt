package com.meenai.mina.core.tool

import com.meenai.mina.core.observation.ObsForm
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.verify.EvidenceLedger
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val RECENT_READS = 3
internal const val MAX_READ_CHARS = EvidenceLedger.READ_CHARS
private const val MAX_ACTION_LOG = 30
/** Facts provided by the user: the most recent ones, each truncated from beginning to end. */
private const val USER_FACTS = 6
private const val USER_FACT_CHARS = EvidenceLedger.OUTPUT_CHARS

/**
 * Materials to be handed to the verifier before completion. Everything read is observation
 * ([Observation]: read interface and web page, sandbox output and read file, facts in tool results),
 * which are given by the ability in the tool result. The distributor records [observe] in the evidence
 * ledger [ledger], bringing the steps and when; what the user said ([ObsKind.UserSaid]: memory retrieval
 * results, answers to questions) is also recorded in [userFacts]. There is also a list of actions and
 * the ending of each takeover. Each tool decides by itself what to read ([AgentTool.observations]) and
 * what to write in the action list ([AgentTool.logTarget]).
 */
internal class EvidenceLog(private val clock: () -> Long = System::currentTimeMillis) {
    val ledger = EvidenceLedger()
    /** Now is the step (set by the scheduler before each execution of the tool), which is recorded in the ledger. */
    var step: Int = 0
    /** The text of the interface status read several times recently (interface reading and reuse). */
    val recentReads = ArrayDeque<String>()
    val actionLog = ArrayDeque<String>()
    /** Facts given by the user: memory retrieval results, answers to ask_user in this task. */
    val userFacts = ArrayDeque<String>()
    /** The App name given by list_apps is included in the fact of launch_app. */
    val appLabels = mutableMapOf<String, String>()
    /** The ending of each takeover; no limit to the number of entries, no getting out like an action list. */
    val takeovers = mutableListOf<String>()
    /** Files written by the assistant using write_file: What is read back by read_file later is written by the assistant himself and does not provide evidence for the answer. */
    val writtenPaths = mutableSetOf<String>()

    /**
     * Note an observation: what the user said goes into [userFacts] (truncated by head and tail), and
     * the rest goes into the ledger (desensitize, truncate, and remove the echo, see
     * [EvidenceLedger.add]). Returns the entry recorded in the ledger; the entry that is not included in
     * the ledger (the one stated by the user and is empty after truncation) is null.
     */
    fun observe(o: Observation): Observation? {
        if (o.kind == ObsKind.UserSaid) {
            noteUserFact(o.text)
            return null
        }
        if (o.form == ObsForm.Interface) {
            recentReads.addLast(o.text)
            while (recentReads.size > RECENT_READS) recentReads.removeFirst()
        }
        return ledger.add(o, step, clock())
    }

    /** The words entered by the assistant into the input box (not the password box): the echo in the input box cannot prove the answer. */
    fun noteTyped(text: String) = ledger.noteTyped(text)

    private fun noteUserFact(text: String) {
        userFacts.addLast(if (text.length <= USER_FACT_CHARS) text else text.take(USER_FACT_CHARS * 3 / 4) + "\n…(中间省略)…\n" + text.takeLast(USER_FACT_CHARS / 4))
        while (userFacts.size > USER_FACTS) userFacts.removeFirst()
    }

    fun noteTakeover(line: String) {
        ledger.noteTakeover()
        takeovers += line
        actionLog.addLast(line)
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }

    /**
     * A line in the action list: [label] is the "Nth step", [target] is the object of the action,
     * [typed] is the input word (all given by the tool), [wide] makes the object longer (web address,
     * command, path).
     */
    fun logAction(label: String, name: String, target: String?, typed: String?, r: ToolExecution, wide: Boolean) {
        val effect = when {
            r.content["ok"] != JsonPrimitive(true) -> (r.content["error"] as? JsonPrimitive)?.content
                ?: (r.content["exitCode"] as? JsonPrimitive)?.let { "退出码 ${it.content}" } ?: "失败"
            r.changed == false -> "画面没变化"
            else -> "生效"
        }
        val what = listOfNotNull(target?.take(if (wide) 120 else 40), typed).joinToString(" ")
        actionLog.addLast("$label $name($what) $effect")
        while (actionLog.size > MAX_ACTION_LOG) actionLog.removeFirst()
    }
}

internal fun JsonObject.argStr(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
