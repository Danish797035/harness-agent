package com.meenai.mina.core.record

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.meenai.mina.core.tool.Verb

/**
 * Desensitization of running records: The words entered in the password box and the string in the shape
 * of API Key will be recorded as `***`. When the caller is not sure whether the target is a password
 * box, it should be treated as yes (it is better to remember less).
 */
object Redactor {
    const val MASK = "***"

    private val SK_KEY = Regex("""\bsk-[A-Za-z0-9_\-]{16,}""")
    private val BEARER = Regex("""\bBearer\s+[A-Za-z0-9._~+/=\-]{8,}""")
    private val GOOGLE_KEY = Regex("""\bAIza[0-9A-Za-z_\-]{30,}""")
    /** Google's new format authentication Key starts with: `AQ.`, followed by a long string of base64url characters. */
    private val GOOGLE_AUTH_KEY = Regex("""\bAQ\.[0-9A-Za-z_\-]{20,}""")
    private val GITHUB_TOKEN = Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}""")
    private val GROQ_KEY = Regex("""\bgsk_[A-Za-z0-9_\-]{16,}""")
    private val PAN = Regex("""\b[A-Z]{5}[0-9]{4}[A-Z]\b""")
    private val UPI = Regex("""\b[a-zA-Z0-9._\-]+@[a-zA-Z0-9_\-]+(?!\.[a-zA-Z])\b""")
    private val AADHAAR = Regex("""(?<!\d)(?:\d{12}|(?<!\d[\s\-]|\d)\d{4}[\s\-]\d{4}[\s\-]\d{4}(?![\s\-]?\d))(?!\d)""")
    private val INDIAN_MOBILE = Regex("""(?<![\w+])(?:\+91[\s\-]*|0[\s\-]*)?[6-9](?:\d{9}|\d{4}[\s\-]\d{5})(?!\d)""")

    private val dynamicSecrets = java.util.concurrent.CopyOnWriteArraySet<String>()

    /** Register sensitive strings that require desensitization throughout the entire process (such as passwords in Password Vault). If the length is too short (less than 4 characters), it will not be registered to avoid accidental injury. */
    fun registerSecret(secret: String) {
        if (secret.length >= 4) {
            dynamicSecrets.add(secret)
        }
    }

    fun unregisterSecret(secret: String) {
        dynamicSecrets.remove(secret)
    }

    fun clearSecrets() {
        dynamicSecrets.clear()
    }

    /** [secretField]: The port reports that the target of this input is the password box. */
    /** [verb]: The action performed by the called tool (null for unknown tools). */
    fun args(verb: Verb?, argsJson: String, secretField: Boolean): String {
        if (verb == Verb.MemorySave) return maskFields(argsJson, MEMORY_ARGS)
        if (verb == Verb.Script) return script(argsJson)
        if (verb == Verb.PhoneCommand) return maskFields(argsJson, setOf("command"))
        if (verb == Verb.PhoneRun) return phoneRun(argsJson)
        val masked = maskSecretFields(argsJson)
        if (verb != Verb.TypeText || !secretField) return text(masked)
        val o = try {
            Json.parseToJsonElement(masked) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK // If you can't parse it, don't remember the entire paragraph. You'd rather lose the information.
        val withText = if ("text" in o) JsonObject(o + ("text" to JsonPrimitive(MASK))) else o
        return text(withText.toString())
    }

    private fun maskSecretFields(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return argsJson
        var changed = false
        val newMap = o.mapValues { (k, v) ->
            if (secretName(k) && v is JsonPrimitive && v.isString) {
                changed = true
                JsonPrimitive(MASK)
            } else v
        }
        return if (changed) JsonObject(newMap).toString() else argsJson
    }

    /** The input content in the conditional mobile command may be a password, and the entire paragraph is coded by field. */
    private fun phoneRun(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val commands = o["commands"] as? JsonArray ?: return text(argsJson)
        val hidden = JsonArray(commands.map { command ->
            val item = command as? JsonObject ?: return@map command
            if ("text" !in item || item["op"] != JsonPrimitive("type")) item
            else JsonObject(item + ("text" to JsonPrimitive(MASK)))
        })
        return text(JsonObject(o + ("commands" to hidden)).toString())
    }

    /**
     * The source code of web_script: The words to be input in the script may be written in any form
     * (give it to mina.type, store it in a variable first, and splice it). Like type_text, we don’t know
     * whether it is a password box or not, so we treat it as such: every string literal in the source
     * code is coded, leaving only the type of quotation marks (template strings leave the position of
     * `${…}`).
     */
    private fun script(argsJson: String): String {
        val o = try { Json.parseToJsonElement(argsJson) as? JsonObject } catch (e: SerializationException) { null } ?: return MASK
        val code = (o["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return text(argsJson)
        val masked = STRING_LITERAL.replace(code) { m ->
            val q = m.value.first()
            val body = m.value.substring(1, m.value.length - 1)
            if (body.isEmpty()) m.value
            else if (q == '`') "`" + TEMPLATE_HOLE.findAll(body).joinToString("") { h -> MASK + STRING_LITERAL.replace(h.value) { "'$MASK'" } } + MASK + "`"
            else "$q$MASK$q"
        }
        return text(JsonObject(o + ("code" to JsonPrimitive(masked))).toString())
    }

    /** Before the tool results are entered into the running record: the contents of the memory and the notes written by the user when returning are not recorded (the number and type are remembered). */
    fun result(verb: Verb?, content: JsonObject): JsonObject = when (verb) {
        Verb.MemorySearch -> (content["memories"] as? JsonArray)?.let { arr ->
            JsonObject(content + ("memories" to JsonArray(arr.map { m ->
                (m as? JsonObject)?.let { if ("content" in it) JsonObject(it + ("content" to JsonPrimitive(MASK))) else it } ?: JsonPrimitive(MASK)
            })))
        } ?: content
        Verb.HandToUser -> if ("userNote" in content) JsonObject(content + ("userNote" to JsonPrimitive(MASK))) else content
        Verb.PhoneCommand -> if ("output" in content) JsonObject(content + ("output" to JsonPrimitive(MASK))) else content
        else -> content
    }

    private fun maskFields(argsJson: String, fields: Set<String>): String {
        val o = try {
            Json.parseToJsonElement(argsJson) as? JsonObject
        } catch (e: SerializationException) {
            null
        } ?: return MASK
        return text(JsonObject(o.mapValues { (k, v) -> if (k in fields) JsonPrimitive(MASK) else v }).toString())
    }

    private val STRING_LITERAL = Regex("""'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`""")
    /** ${...} in the template string: the expression is not a literal, the position is reserved, and the words before and after are coded. */
    private val TEMPLATE_HOLE = Regex("""\$\{[^}]*\}""")
    private val MEMORY_ARGS = setOf("content", "quote", "keywords")

    /** 一次性码一类的词。 */
    private val CODE_WORD = Regex("""验证|校验码|动态码|动态密码|确认码|安全码|短信|口令|(?i:\b(?:code|otp|passcode|pin|2fa|one[- ]time password|verification code)\b)""")
    /** 4–8 位数字,中间可以隔单个空格或连字符(「4 8 2 9 1 3」「482-913」)。 */
    private val CODE_DIGITS = Regex(
        """(?<![\d.¥$€£])(?<!\d,)(?<![¥$€£] )\d(?:[ \-]?\d){3,7}(?!\d)(?!\.\d)(?!,\d{3}(?!\d))""" +
            // What follows the unit, year, and currency is the quantity, not the code.
            """(?!\s*(?:年|元|块|角|分钟|小时|秒|天|月|日|号|次|个|人|件|条|米|公里|千米|克|公斤|千克|斤|吨|度|℃|°|%|‰|[¥$€£]|(?i:km|kg|mb|gb|kb|ms|cm|mm|mah|rmb|usd|cny)\b))""",
    )
    /** There must be at most a few characters between the code and this type of word (whichever side counts). */
    private const val CODE_WINDOW = 24

    /**
     * The materials in the evidence ledger (reading screens, web pages, command output) pass through the
     * key recognized by [text] before entering the account, and the one-time code is also covered - 4-8
     * digits containing words such as "verification", "text message" and "code" within the characters
     * [CODE_WINDOW] before and after. The words in the password box are not included in the screen
     * reading text.
     */
    fun evidence(s: String): String {
        val t = text(s)
        val words = CODE_WORD.findAll(t).map { it.range }.toList()
        if (words.isEmpty()) return t
        return CODE_DIGITS.replace(t) { m ->
            val near = words.any { w -> m.range.first - w.last in 1..CODE_WINDOW + 1 || w.first - m.range.last in 1..CODE_WINDOW + 1 }
            if (near) MASK else m.value
        }
    }

    fun text(s: String): String {
        var out = s
        for (re in listOf(SK_KEY, GOOGLE_KEY, GOOGLE_AUTH_KEY, GITHUB_TOKEN, GROQ_KEY, UPI, PAN, INDIAN_MOBILE, AADHAAR)) out = re.replace(out, MASK)
        for (secret in dynamicSecrets) {
            if (out.contains(secret)) {
                out = out.replace(secret, MASK)
            }
        }
        return BEARER.replace(out, "Bearer $MASK")
    }

    // ---------- Web page network data ----------

    data class Network(val url: String?, val body: String?, val headers: Map<String, String>?)

    /**
     * Web page network data (address, response text, headers) are all passed through it before leaving
     * the network record: they are all handed over to the model, entered for verification evidence, and
     * entered into the operation record. Query parameters with names like credentials, JSON/form fields,
     * and header values are recorded as `***` (judged by words: author and keyword are not counted);
     * code and state in the OAuth bounce are also covered; in addition, JWT, long and complex strings
     * (uppercase and lowercase letters plus numbers), and Keys recognized by [text] are also covered.
     */
    fun network(url: String? = null, body: String? = null, headers: Map<String, String>? = null): Network = Network(
        url = url?.let { values(maskUrl(it), slash = false) },
        body = body?.let { values(maskBody(it), slash = true) },
        headers = headers?.mapValues { (k, v) -> if (secretName(k)) MASK else values(v, slash = true) },
    )

    /**
     * Read the data (JSON) read from the page by scripts and site commands: the same set of rules as the
     * network response body (fields based on credential names, JWT, long and complicated strings, Key),
     * except that long strings are not recognized across slashes (there are often URLs in the results,
     * and each path segment is viewed separately).
     */
    fun pageData(s: String): String = values(maskBody(s), slash = false)

    private val SECRET_WORDS = setOf(
        "token", "tokens", "secret", "password", "passwd", "pwd", "passcode", "session", "sessionid", "sessid", "sid",
        "auth", "authorization", "csrf", "xsrf", "signature", "sig", "otp", "cookie", "credential", "credentials",
        "apikey", "jwt", "bearer", "pin", "privatekey",
    )
    private val SECRET_COMPOUNDS = setOf("apikey", "accesskey", "secretkey", "privatekey", "clientsecret", "sessionkey", "authkey")
    private val CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")
    private val WORD_SPLIT = Regex("[^A-Za-z0-9]+")

    /** The name is split into words (camel case, underscore, hyphen) followed by words like credentials. */
    internal fun secretName(name: String): Boolean {
        val words = name.split(WORD_SPLIT).flatMap { it.split(CASE_BOUNDARY) }.map { it.lowercase() }.filter { it.isNotEmpty() }
        return words.any { it in SECRET_WORDS } || words.joinToString("") in SECRET_COMPOUNDS
    }

    private val OAUTH_MARKERS = setOf("state", "redirect_uri", "client_id", "code_verifier")
    private val OAUTH_RESULTS = setOf("code", "state")

    /** a=b&c=d in the query string or fragment: the value of the credential name is covered; like OAuth bounce (with state, etc., and with code or credentials), the code and state are also covered. [bareKey]: A single key is also considered a credential. */
    private fun maskParams(q: String, bareKey: Boolean): String {
        val parts = q.split('&')
        val names = parts.map { it.substringBefore('=').lowercase() }
        val oauth = names.any { it in OAUTH_MARKERS } && (names.contains("code") || names.any { secretName(it) })
        return parts.joinToString("&") { p ->
            val eq = p.indexOf('=')
            if (eq <= 0) return@joinToString p
            val name = p.substring(0, eq)
            val n = name.lowercase()
            val hide = secretName(name) || (bareKey && n == "key") || (oauth && n in OAUTH_RESULTS)
            if (hide) "$name=$MASK" else p
        }
    }

    private fun maskUrl(u: String): String {
        val hash = u.indexOf('#')
        val beforeHash = if (hash >= 0) u.substring(0, hash) else u
        val fragment = if (hash >= 0) u.substring(hash + 1) else null
        val q = beforeHash.indexOf('?')
        val base = if (q >= 0) beforeHash.substring(0, q + 1) + maskParams(beforeHash.substring(q + 1), bareKey = true) else beforeHash
        return if (fragment == null) base else base + "#" + if ('=' in fragment) maskParams(fragment, bareKey = true) else fragment
    }

    private val JSON_PAIR = Regex(""""((?:[^"\\]|\\.){1,100})"(\s*:\s*)("(?:[^"\\]|\\.)*"|-?\d[\d.eE+\-]*|true|false|null)""")
    private val FORM = Regex("""^[^\s{}\[\]"<>]+=[^\s]*$""")

    private fun maskBody(b: String): String {
        if (FORM.matches(b)) return maskParams(b, bareKey = false)
        return JSON_PAIR.replace(b) { m ->
            if (secretName(m.groupValues[1])) "\"${m.groupValues[1]}\"${m.groupValues[2]}\"$MASK\"" else m.value
        }
    }

    private val JWT = Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}""")
    private val LONG_WITH_SLASH = Regex("""[A-Za-z0-9_\-+/=]{32,}""")
    private val LONG = Regex("""[A-Za-z0-9_\-+=]{32,}""")

    /** JWT in the value, long and complex string, Key in known format. Slashes are not recognized in the address (each segment of the path is viewed separately). */
    private fun values(s: String, slash: Boolean): String {
        var out = JWT.replace(s, MASK)
        out = (if (slash) LONG_WITH_SLASH else LONG).replace(out) { m ->
            val v = m.value
            if (v.any(Char::isUpperCase) && v.any(Char::isLowerCase) && v.any(Char::isDigit)) MASK else v
        }
        return text(out)
    }
}
