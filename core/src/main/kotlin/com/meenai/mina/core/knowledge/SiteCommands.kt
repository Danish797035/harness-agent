package com.meenai.mina.core.knowledge

import com.meenai.mina.core.script.JsSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** An argument to the site command; [type] is a string/integer/number/boolean. */
@Serializable
data class CommandArg(val name: String, val type: String, val required: Boolean = false, val description: String = "")

/** Declaration of site command (script header); [file] is the relative path `scripts/<name>.js` under the site directory. */
@Serializable
data class SiteCommandDecl(val name: String, val description: String, val args: List<CommandArg> = emptyList(), val returns: String, val file: String)

/** An executable site command: the site it belongs to, the statement, and the script body (after the header). */
data class SiteCommand(val site: String, val decl: SiteCommandDecl, val body: String)

/**
 * Site commands: `scripts/<name>.js` in the site knowledge directory (those not in
 * scripts/manifest.json), run read-only in the reading world of the current page. The file starts with a
 * block comment: the first line is [MARK], followed by one line per line until the end of the comment,
 * for example
 * - `name: list_results`
 * - `description: List items on the current results page`
 * - `arg: limit integer - maximum number of items`
 * - `arg: query string required - as long as this word is in the title`
 * - `returns: [{title, url}]`
 *
 * name is the same as the file name; arg can have multiple lines, written as "name type [required] [-
 * description]"; after the header is the async function body, and the parameters are in `args`.
 */
object SiteCommandFile {
    const val MARK = "/* mina-command"
    const val DESCRIPTION_LIMIT = 120
    const val BODY_LIMIT = 8_000
    val NAME = Regex("[a-z][a-z0-9_]{0,39}")
    val TYPES = listOf("string", "integer", "number", "boolean")
    private val ARG = Regex("""^([a-z][A-Za-z0-9_]{0,39})\s+(string|integer|number|boolean)(\s+required)?(?:\s+-\s+(.+))?$""")

    fun isCommand(text: String): Boolean = clean(text).startsWith(MARK)

    /** [decl] is available when there are no errors; [body] is the script after the header. */
    class Parsed(val decl: SiteCommandDecl?, val body: String, val errors: List<String>)

    private fun clean(text: String) = text.removePrefix("﻿").replace("\r\n", "\n").trimStart()

    /** [file] is the relative path (`scripts/<name>.js`) under the site directory. */
    fun parse(file: String, text: String): Parsed {
        val t = clean(text)
        if (!t.startsWith(MARK)) return Parsed(null, "", listOf("头部要以 `$MARK` 开头"))
        val end = t.indexOf("*/")
        if (end < 0) return Parsed(null, "", listOf("头部没有用 */ 结束"))
        val body = t.substring(end + 2).trim()
        val errors = mutableListOf<String>()
        var name: String? = null
        var description: String? = null
        var returns: String? = null
        val args = mutableListOf<CommandArg>()
        for (raw in t.substring(MARK.length, end).lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val i = line.indexOf(':')
            if (i <= 0) { errors += "头部这一行看不懂:$line"; continue }
            val value = line.substring(i + 1).trim()
            when (val key = line.substring(0, i).trim()) {
                "name" -> name = value
                "description" -> description = value
                "returns" -> returns = value
                "arg" -> ARG.matchEntire(value)?.let { m ->
                    args += CommandArg(m.groupValues[1], m.groupValues[2], m.groupValues[3].isNotEmpty(), m.groupValues[4].trim())
                } ?: run { errors += "arg 写作「名字 类型 [required] [- 说明]」,类型是 ${TYPES.joinToString("/")}:$value" }
                else -> errors += "头部有不认得的键 $key"
            }
        }
        val base = file.substringAfterLast('/').removeSuffix(".js")
        when {
            name == null -> errors += "缺少 name"
            !NAME.matches(name) -> errors += "name 只能用小写字母、数字和下划线,以字母开头,至多 40 个字符"
            name != base -> errors += "name($name)必须与文件名($base.js)一致"
        }
        if (description.isNullOrEmpty()) errors += "缺少 description"
        else if (description.codePointCount(0, description.length) > DESCRIPTION_LIMIT) errors += "description 超过 $DESCRIPTION_LIMIT 字"
        if (returns.isNullOrEmpty()) errors += "缺少 returns"
        args.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { errors += "arg $it 重复" }
        if (body.isEmpty()) errors += "脚本正文为空"
        if (body.length > BODY_LIMIT) errors += "脚本正文 ${body.length} 字,超过 $BODY_LIMIT"
        // 动态 import 会在读取世界的全局作用域里跑模块,绕开只读包装
        if (JsSource.usesImport(body)) errors += "脚本正文不能用 import(字符串、注释之外不能出现这个词)"
        val decl = if (errors.isEmpty()) SiteCommandDecl(name!!, description!!, args, returns!!, file) else null
        return Parsed(decl, body, errors)
    }

    /** The parameters given by the model are checked according to the declaration: if they are not declared, required fields are missing, or the type is wrong, a problem description will be returned; if there is no problem, it will be null. */
    fun checkArgs(decl: SiteCommandDecl, args: JsonObject): String? {
        val declared = decl.args.associateBy { it.name }
        args.keys.firstOrNull { it !in declared }?.let {
            return "'$it' is not an argument of ${decl.name}; it takes " + (decl.args.joinToString { a -> a.name }.ifEmpty { "no arguments" })
        }
        for (a in decl.args) {
            val v = args[a.name]
            if (v == null || v is JsonNull) {
                if (a.required) return "${decl.name} needs '${a.name}' (${a.type})"
                continue
            }
            val p = v as? JsonPrimitive ?: return "'${a.name}' must be a ${a.type}"
            val fits = when (a.type) {
                "string" -> p.isString
                "boolean" -> !p.isString && p.booleanOrNull != null
                "integer" -> !p.isString && p.longOrNull != null
                else -> !p.isString && p.doubleOrNull != null
            }
            if (!fits) return "'${a.name}' must be a ${a.type}"
        }
        return null
    }

    /** A row listed in the site knowledge block. */
    fun line(d: SiteCommandDecl): String {
        val sig = d.args.joinToString(", ") { it.name + (if (it.required) "" else "?") + ": " + it.type }
        val notes = d.args.filter { it.description.isNotEmpty() }.joinToString("; ") { "${it.name} ${it.description}" }
        return "- ${d.name}($sig): ${d.description}. Returns ${d.returns}" + (if (notes.isEmpty()) "" else ". Arguments: $notes")
    }
}

