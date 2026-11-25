package com.meenai.mina.core.cap.web

import com.meenai.mina.core.knowledge.SiteCommandFile
import com.meenai.mina.core.net.UrlHost
import com.meenai.mina.core.observation.Untrusted
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.failCode
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.meenai.mina.core.uistate.lastSnapshot

private fun timeoutProp() = int("Time limit, 1000-$READ_SCRIPT_MAX_TIMEOUT_MS ms (default $READ_SCRIPT_DEFAULT_TIMEOUT_MS).", 1000, READ_SCRIPT_MAX_TIMEOUT_MS)

/** Reading scripts (read data scripts, site commands) run on the port; the results come from web pages, are marked as untrustworthy when submitted to the model, and are also submitted for verification as observations. */
private suspend fun readScript(ctx: ToolContext, code: String, args: JsonObject?, timeoutMs: Int, what: String): ToolExecution {
    val r = try {
        ctx.web!!.evaluate(code, timeoutMs.toLong(), args)
    } catch (e: WebTabCrashed) {
        return failCode("tab_crashed")
    } catch (e: WebTabHandedOver) {
        return failCode("handed_over")
    } catch (e: WebDialogOpen) {
        return failCode(DIALOG_OPEN)
    }
    val url = ctx.lastSnapshot?.web?.url
    return when (r) {
        is EvalJs.Failed -> fail(r.error, r.message)
        is EvalJs.Ok -> ok {
            put("result", Untrusted.wrap(r.json))
            put("truncated", r.truncated)
            if (r.fresh) put("state", "new")
        }.observing(webRead("$what(${url ?: "当前网页"})\n${r.json}", url))
    }
}

/** Read data script: Run read-only in the reading world of the current page (real DOM, same-origin GET); use the action tool to move the page. */
internal object EvaluateJs : WebTool {
    override val verb = Verb.EvaluateJs
    override val label = "Read page data"
    override val risk = Risk.Read
    override val def = ToolDef("web_evaluate_js",
        Prompts.tool("web_evaluate_js", "result_max" to EVAL_RESULT_MAX_CHARS, "default_seconds" to READ_SCRIPT_DEFAULT_TIMEOUT_MS / 1000),
        mapOf(
            "script" to str("JavaScript, 1-$EVAL_SCRIPT_MAX_CHARS characters: one expression (e.g. `document.title`), or the body of an async function that returns the result."),
            "timeoutMs" to timeoutProp(),
        ),
        listOf("script"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val script = a.str("script", max = EVAL_SCRIPT_MAX_CHARS)!!
        PageScriptProtocol.codeProblem(script)?.let { throw ArgError("script", it) }
        val timeout = a.int("timeoutMs", 1000, READ_SCRIPT_MAX_TIMEOUT_MS) ?: READ_SCRIPT_DEFAULT_TIMEOUT_MS
        return readScript(ctx, script, null, timeout, "网页数据脚本的结果")
    }
}

/**
 * Site command: `scripts/<name>.js` declared in the current site knowledge, runs read-only in the
 * reading world, and the parameters are verified as declared. It is only provided if there are site
 * commands in the built-in knowledge; only the commands of the site to which the current page belongs
 * are recognized.
 */
internal object RunSiteCommand : WebTool {
    override val verb = Verb.SiteCommand
    override val label = "Run site command"
    override val risk = Risk.Read
    override fun available(ctx: ToolContext) = ctx.web != null && ctx.config.skills?.hasSiteCommands() == true
    override val def = ToolDef("web_run_site_command",
        Prompts.tool("web_run_site_command", "result_max" to EVAL_RESULT_MAX_CHARS),
        mapOf(
            "name" to str("Command name, as listed for the current site."),
            "args" to buildJsonObject {
                put("type", "object"); put("description", "Arguments the command declares, name to value (omit when it takes none).")
            },
            "timeoutMs" to timeoutProp(),
        ),
        listOf("name"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val name = a.str("name", max = 40)!!.trim()
        val args = when (val v = a.o["args"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> v
            else -> throw ArgError("args", "must be an object")
        }
        val timeout = a.int("timeoutMs", 1000, READ_SCRIPT_MAX_TIMEOUT_MS) ?: READ_SCRIPT_DEFAULT_TIMEOUT_MS
        val url = currentUrl(ctx) ?: return failCode("no_tab_open")
        val host = UrlHost.of(url) ?: return fail("unknown_command", "The current page ($url) is not on a site with commands.")
        val commands = ctx.config.skills?.siteCommands(host).orEmpty()
        val command = commands.firstOrNull { it.decl.name == name }
            ?: return fail("unknown_command",
                if (commands.isEmpty()) "$host has no site commands; read the page with web_read_page or web_evaluate_js."
                else "$host has no command '$name'. Its commands: ${commands.joinToString { it.decl.name }}.")
        SiteCommandFile.checkArgs(command.decl, args)?.let { throw ArgError("args", it) }
        return readScript(ctx, command.body, args, timeout, "站点命令 ${command.site}/${command.decl.name} 的结果")
    }

    /** The URL of the current tag: According to the tag list, if it cannot be read, the most recent screen reading will be used. */
    private suspend fun currentUrl(ctx: ToolContext): String? {
        val tabs = try { ctx.web!!.tabs() } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
        return tabs.firstOrNull { it.current }?.url?.takeIf { it.isNotBlank() } ?: ctx.lastSnapshot?.web?.url
    }
}
