package com.meenai.mina.core.eval

import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.verify.EvidenceLedger
import com.meenai.mina.core.observation.InputHints
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.observation.PlaceKind
import com.meenai.mina.core.record.Redactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reconstruct the evidence ledger from running records. Each incoming observation in the new record is
 * recorded as observed (with number): put back as it is ([EvidenceLedger.restore]), the same as the
 * running ledger. The old records (without numbered observed) are reconstructed according to the fields
 * recorded in the ledger by each tool at that time: the element text of the reading interface (the old
 * screen_text, which has been changed to observed numbered 0 when reading) and the fields read in the
 * tool results. The prompts, instructions, and errors given to the working model in the results are not
 * counted; things written by the assistant (commands run, content read back after writing into the file)
 * are not used as evidence, the commands are placed in the source, and the lines (echo) that appear in
 * the command as they are in the output are not recorded. Both records are filled with the words entered
 * by the assistant into the input box according to the calling parameters.
 */
internal object RecordedEvidence {
    fun ledger(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger =
        if (events.any { it is RunEvent.Observed && it.id > 0 }) restored(events, tools) else legacy(events, tools)

    private fun restored(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger {
        val l = EvidenceLedger()
        for (e in events) when (e) {
            is RunEvent.ModelReply -> typed(e, tools)?.let(l::noteTyped)
            is RunEvent.State -> if (e.name == TAKEOVER_STATE) l.noteTakeover()
            is RunEvent.Observed -> if (e.id > 0) l.restore(e.observation())
            else -> Unit
        }
        return l
    }

    /** This time, the words typed into the input box will be replied to (the words typed in the running record will not be counted). */
    private fun typed(e: RunEvent.ModelReply, tools: ToolIndex): String? =
        if (e.acted && tools.verb(e.tool) == Verb.TypeText) obj(e.argsJson.orEmpty())?.s("text")?.takeIf { it != Redactor.MASK } else null

    private fun legacy(events: List<RunEvent>, tools: ToolIndex): EvidenceLedger {
        val l = EvidenceLedger()
        val args = mutableMapOf<String, JsonObject>()
        val labels = mutableMapOf<String, String>()
        val written = mutableSetOf<String>()
        var site: String? = null
        // The title of the recently opened and read webpage: when there is no possible page change
        // action, the screen reader will read this page.
        var title: Pair<String, Int>? = null
        for (e in events) when (e) {
            is RunEvent.ModelReply -> if (e.acted) {
                val a = obj(e.argsJson.orEmpty()) ?: JsonObject(emptyMap())
                e.tool?.let { args[it] = a }
                if (tools.verb(e.tool) == Verb.TypeText) a.s("text")?.let(l::noteTyped)
            }
            is RunEvent.State -> if (e.name == TAKEOVER_STATE) l.noteTakeover()
            is RunEvent.Observed -> {
                val onSite = e.place?.kind == PlaceKind.Site
                if (onSite) site = e.place?.name
                val head = title?.takeIf { onSite }?.first
                l.add(e.observation().copy(title = head), e.step, e.atMs)
            }
            is RunEvent.ToolResult -> {
                val r = obj(e.resultJson) ?: continue
                when (tools.verb(e.tool)) {
                    Verb.Navigate, Verb.ReadPage, Verb.Tabs -> title = r.s("title")?.takeIf { r["ok"] == JsonPrimitive(true) }?.let { it to e.step }
                    // After clicking, returning, pressing keys, typing, or changing apps, the page you
                    // read may not be the same.
                    in PAGE_CHANGING -> title = null
                    else -> Unit
                }
                note(l, e, tools, r, args[e.tool] ?: JsonObject(emptyMap()), labels, written, site)
            }
            else -> Unit
        }
        return l
    }

    private fun note(
        l: EvidenceLedger, e: RunEvent.ToolResult, tools: ToolIndex, r: JsonObject, a: JsonObject,
        labels: MutableMap<String, String>, written: MutableSet<String>, site: String?,
    ) {
        val ok = r["ok"] == JsonPrimitive(true)
        // The tool name before the name change in the old record is recorded according to the current
        // name, which is the same as when running.
        val name = tools[e.tool]?.name ?: e.tool
        fun add(kind: ObsKind, text: String, place: Place? = null, authored: String? = null, title: String? = null) =
            l.add(Observation(kind, text, place = place, authored = authored, title = title), e.step, e.atMs)
        fun page() = add(ObsKind.Fact, "$name:网址 ${r.s("url")},网页标题「${r.s("title").orEmpty()}」" + (r.s("httpStatus")?.let { ",HTTP 状态 $it" } ?: ""))
        when (tools.verb(e.tool)) {
            Verb.Navigate -> if (ok) page()
            Verb.ReadPage -> if (ok) {
                val links = r.list("links").joinToString("\n") { "链接「${it.s("text")}」${it.s("url")}" }
                val images = r.list("images").filter { !it.s("alt").isNullOrBlank() }.joinToString("\n") { "图片「${it.s("alt")}」${it.s("url")}" }
                val read = listOf(r.s("text").orEmpty(), links, images).filter { it.isNotBlank() }.joinToString("\n")
                if (read.isNotBlank()) add(ObsKind.Read, "网页正文(${r.s("url")},从第 ${a.s("offset") ?: "0"} 字起)\n$read", r.s("url")?.let(Place::page), title = r.s("title"))
                page()
            }
            Verb.Tabs -> if (ok) {
                val tabs = r.list("tabs").ifEmpty { listOf(r) }.filter { it.s("title") != null }
                if (tabs.isNotEmpty()) add(ObsKind.Fact, tabs.joinToString("\n", "$name:\n") { "标签 ${it.s("tabId")}:网址 ${it.s("url")},网页标题「${it.s("title")}」" })
            }
            Verb.EvaluateJs -> if (ok) r.s("result")?.let { add(ObsKind.Read, "网页数据脚本的结果(${site ?: "当前网页"})\n$it", site?.let(Place::site)) }
            Verb.SiteCommand -> if (ok) r.s("result")?.let { add(ObsKind.Read, "站点命令 ${a.s("name")} 的结果(${site ?: "当前网页"})\n$it", site?.let(Place::site)) }
            Verb.Script -> r.s("result")?.takeIf { it != "null" }?.let { add(ObsKind.Read, "页面脚本的结果(${site ?: "当前网页"})\n$it", site?.let(Place::site)) }
            Verb.Downloads -> if (ok) r.list("downloads").takeIf { it.isNotEmpty() }?.let { list ->
                add(ObsKind.Read, list.joinToString("\n", "下载列表(文件名来自网页):\n") { d ->
                    listOf("file", "status", "bytes", "error").mapNotNull { k -> d.s(k)?.let { "$k=$it" } }.joinToString(" ")
                })
            }
            Verb.Capture -> if (ok) r.s("body")?.let { add(ObsKind.Read, "网络响应(${r.s("url")},HTTP ${r.s("status")})\n$it", r.s("url")?.let(Place::page)) }
            Verb.RunCommand -> r.s("output")?.let { add(ObsKind.Output, "退出码 ${r.s("exitCode")}:\n$it", Place.command("run_command「${a.s("command")?.take(200)}」"), a.s("command")) }
            Verb.RunBundledScript -> r.s("output")?.let { add(ObsKind.Output, "退出码 ${r.s("exitCode")}:\n$it", Place.command("run_bundled_script ${a.s("source") ?: a.s("skill")}/${a.s("script")}"), a["args"]?.toString()) }
            Verb.ReadFile -> if (ok && r.s("path") !in written) r.s("content")?.let { add(ObsKind.Output, it, Place.file("read_file ${r.s("path")}")) }
            Verb.WriteFile -> if (ok) {
                r.s("path")?.let { written += it }
                add(ObsKind.Output, "write_file 写入 ${r.s("path")}(${r.s("bytesWritten")} 字节,文件现为 ${r.s("size")} 字节)")
            }
            Verb.SearchFiles -> if (ok) r.list("matches").let { m ->
                add(ObsKind.Output, m.joinToString("\n") { "${it.s("file")}:${it.s("line")}: ${it.s("text")}" }, Place.command("search_files「${a.s("pattern")?.take(100)}」"))
            }
            Verb.ShareFile -> if (ok) add(ObsKind.Output, "share_file 已存到 ${r.s("savedAs")}")
            Verb.ListApps -> if (ok) r.list("apps").forEach { app -> app.s("package")?.let { p -> app.s("label")?.let { labels[p] = it } } }
            Verb.LaunchApp -> if (ok) a.s("package")?.let { p -> add(ObsKind.Fact, "$name 打开了 $p" + (labels[p]?.let { "(App 名字「$it」)" } ?: "")) }
            Verb.ShareText -> if (ok) a.s("package")?.let { p -> add(ObsKind.Fact, "$name 打开了 $p 的分享页(还没发出)" + (labels[p]?.let { "(App 名字「$it」)" } ?: "")) }
            else -> Unit
        }
    }

    private const val TAKEOVER_STATE = "waiting_takeover"
    private val PAGE_CHANGING = setOf(Verb.Tap, Verb.Back, Verb.PressKey, Verb.TypeText, Verb.Swipe, Verb.Interact, Verb.Script, Verb.OpenLink, Verb.LaunchApp, Verb.ShareText,
        Verb.ShowAlarms, Verb.AddCalendarEvent, Verb.SetWheel)

    private fun obj(s: String): JsonObject? = runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.list(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
}

