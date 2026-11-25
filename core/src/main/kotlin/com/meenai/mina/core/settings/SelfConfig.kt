package com.meenai.mina.core.settings

import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Concurrency
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * The model is changed to the only entrance set by Mina (App implementation, the core only recognizes
 * these keys).
 *
 * What can be changed is determined by the App: Models and Keys, and deletion of data are not allowed -
 * the former is equivalent to letting the model replace its own brain and cost the user money, while the
 * latter is irreversible. All given are reversible preference items.
 *
 * When the user does not turn on "Allow Mina to change its own settings", this tool is not available at
 * all, so the model cannot touch it.
 */
interface SelfConfig {
    /** Which items can be changed now and what are their respective values. */
    fun options(): List<Option>

    /** Change one item; [value] must be in [Option.choices]. Returns null if successful, otherwise a reason. */
    fun set(key: String, value: String): String?

    /** A setting; [label] is the name that people can see, [value] is the current value, [choices] is several optional values (empty means free text). */
    data class Option(val key: String, val label: String, val value: String, val choices: List<String> = emptyList())
}

const val SETTINGS_LIST = "settings_list"
const val SETTINGS_SET = "settings_set"

private fun port(ctx: ToolContext): SelfConfig =
    ctx.config.selfConfig ?: error("settings tools are only offered when the user allows it")

/** List the settings that Mina can change: keys, visible names, current values, and optional values. Read only. */
internal object SettingsList : AgentTool {
    override val verb = Verb.SettingsList
    override val label = "Read settings"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_LIST, Prompts.tool(SETTINGS_LIST), emptyMap(), emptyList())

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = ok {
        putJsonArray("settings") {
            port(ctx).options().forEach { o ->
                addJsonObject {
                    put("key", o.key); put("label", o.label); put("value", o.value)
                    if (o.choices.isNotEmpty()) putJsonArray("choices") { o.choices.forEach { add(it) } }
                }
            }
        }
    }
}

/** Change one of Mina's own settings; both keys and values must come from [SettingsList]. */
internal object SettingsSet : AgentTool {
    override val verb = Verb.SettingsSet
    override val label = "Change a setting"
    override val risk = Risk.Write
    /** Don't move the screen: What is changed is Mina's own setting, which has nothing to do with the screen of the current task. */
    override val offScreen = true

    override fun available(ctx: ToolContext) = ctx.config.selfConfig != null

    override val def = ToolDef(SETTINGS_SET, Prompts.tool(SETTINGS_SET), mapOf(
        "key" to str("The setting key from settings_list."),
        "value" to str("The new value, one of that setting's choices."),
    ), listOf("key", "value"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val key = a.str("key") ?: return fail("missing_key", "Give the setting key from settings_list.")
        val value = a.str("value") ?: return fail("missing_value", "Give the new value.")
        val problem = port(ctx).set(key, value)
        if (problem != null) return fail("bad_value", problem)
        return ok {
            put("key", key)
            put("value", port(ctx).options().firstOrNull { it.key == key }?.value ?: value)
        }
    }
}
