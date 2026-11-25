package com.meenai.mina.core.eval

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class EvalCategory {
    @SerialName("read") Read,
    @SerialName("write") Write,
    @SerialName("irreversible") Irreversible,
    @SerialName("impossible") Impossible,
    @SerialName("barrier") Barrier,
}

@Serializable
enum class Expect {
    @SerialName("succeeded") Succeeded,
    @SerialName("failed") Failed,
    @SerialName("confirmation") Confirmation,
    @SerialName("takeover") Takeover,
}

/** Objective check items; distinguished by "type" in JSON. */
@Serializable
sealed interface Check {
    @Serializable @SerialName("answer_contains")
    data class AnswerContains(val all: List<String>) : Check

    @Serializable @SerialName("answer_regex")
    data class AnswerRegex(val pattern: String) : Check

    /** Answer items must all appear in the last few readings of the text. */
    @Serializable @SerialName("answer_grounded")
    data class AnswerGrounded(val minItems: Int = 1) : Check

    /** ), switch to the App on this phone when running.`[packageName]`(see{app:<能力>}), switch to the App on this phone when running.[APP_CAPABILITIES]),跑的时候换成这台手机上的 App。 */
    @Serializable @SerialName("screen_has")
    data class ScreenHas(val packageName: String, val text: String, val deepLink: String? = null) : Check

    /** probe must be the name in [PROBE_ALLOWLIST]. */
    @Serializable @SerialName("probe_equals")
    data class ProbeEquals(val probe: String, val expected: String, val contains: Boolean = false) : Check

    /** At the beginning of this task, at least [min] memories (memory_used recorded in the code) were brought. Used for the second question of memory questions. */
    @Serializable @SerialName("memory_used")
    data class MemoryUsed(val min: Int = 1) : Check

    /**
     * In this task, has the Agent asked the user (ask_user): If [expected] is true, it must have been
     * asked; if false, it must have not been asked once. The question "Should I ask or should I decide
     * by myself?" should be asked: if the target is missing information that only the user knows, it
     * should be asked; if the preference is not stated, it should be determined and explained according
     * to common sense.
     */
    @Serializable @SerialName("asked_user")
    data class AskedUser(val expected: Boolean = true) : Check
    /** After the task is completed, open [url] in a new web page tab and read the entire page to find [text] (ignoring white spaces). */
    @Serializable @SerialName("web_has")
    data class WebHas(val url: String, val text: String) : Check

    @Serializable @SerialName("confirmation_shows")
    data class ConfirmationShows(
        val action: String? = null,
        val target: String? = null,
        val content: String? = null,
        val amount: String? = null,
    ) : Check
}

/**
 * An evaluation question. Strings in goal, marker, and check items can be written`{run}`and
 * `{probe:<名字>}`, before running by[EvalTasks.substitute]replace;`probesBefore`It is "placeholder name →
 * whitelist probe name". Before running the task, execute these probes to get the expected
 * value.`apps`and screen_has package name can be written `{app:<能力>}`(Certain types of apps that come
 * with mobile phones, see[APP_CAPABILITIES]),Depend on[EvalTasks.resolveApps]Replace it with the package
 * name on this phone. The question does not pin the package name of a certain brand.
 *
 * The writing method of `requires` is: `installed:<包名>`; `app:<能力>` (there is an App on this phone to
 * handle this kind of thing); `signedIn:<包名或网站>` (the user has logged in: for the website, see the
 * registration in the settings "Website Login", for the App, see the manual marking; the old way of
 * writing marking is `login:<包名>`, `site:<网站>` Still recognize it).
 */
@Serializable
data class EvalTask(
    val id: String,
    val goal: String,
    val apps: List<String>,
    val category: EvalCategory,
    val requires: List<String> = emptyList(),
    val expect: Expect,
    val checks: List<Check>,
    val maxSteps: Int? = null,
    val timeoutSec: Int? = null,
    val marker: String? = null,
    val answers: Map<String, String> = emptyMap(),
    val probesBefore: Map<String, String> = emptyMap(),
    /** Instructions for the judges (why this question is asked like this), the evaluator does not use it. */
    val note: String? = null,
    /** Matching questions: train (a round of learning skills) or test (a round of comparison); press `-e split` to select the evaluator. */
    val split: String? = null,
    /** Questions in the same session are run in order by ID in the same conversation (memory questions: tell them first, then use them). */
    val session: String? = null,
    /** Variations for playback evaluation: the same thing by changing the method or parameters; when doing `evalVariants on`, first run the original question and write down the route, and then run these. */
    val variants: List<Variant> = emptyList(),
    /** Mandatory channel: "web" or "app"; if it is not empty, add "use web page"/"use app" before the target (for comparison between the two routes). */
    val channel: String? = null,
    /**
     * Question pool: tune (questions read when adjusting prompt words and knowledge) or holdout (results
     * only viewed when deciding to publish, not used for adjustment); press `-e evalSplit` to select the
     * evaluator. It is two different things from [split](配对题的 train/test 两轮).
     */
    val partition: String? = null,
    /**
     * Those that run into these obstacles (visible on the screen, see [RunFacts.takeoverBarrier]) and
     * then handed over to the user are listed separately as "handed over to the user"
     * ([Scoring.handedOver]), and are not counted as successes or other failures. Whether they are
     * counted or not is decided by the person. If it is empty, the default is selected by category
     * ([Scoring.takeoverKinds]): the reading category question is login, and the others are not
     * recognized.
     */
    val acceptTakeoverOn: List<String>? = null,
)

/**
 * "Certain types of Apps that come with the mobile phone" are found according to the Android standard
 * Intent: before digressing, use these Intents to find out the Apps (candidates) that can be processed
 * by this mobile phone. After running, the candidate that has been actually opened by the Agent will be
 * given priority. If there is no standard intent (memo), you can only retrieve the app opened by the
 * agent after running.
 */
data class AppIntent(val action: String, val category: String? = null)

private const val MAIN = "android.intent.action.MAIN"

val APP_CAPABILITIES: Map<String, List<AppIntent>> = mapOf(
    "alarm" to listOf(AppIntent("android.intent.action.SET_ALARM"), AppIntent("android.intent.action.SHOW_ALARMS")),
    "calendar" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALENDAR")),
    "calculator" to listOf(AppIntent(MAIN, "android.intent.category.APP_CALCULATOR")),
    "contacts" to listOf(AppIntent(MAIN, "android.intent.category.APP_CONTACTS")),
    "files" to listOf(AppIntent(MAIN, "android.intent.category.APP_FILES"), AppIntent("android.intent.action.OPEN_DOCUMENT_TREE")),
    "weather" to listOf(AppIntent(MAIN, "android.intent.category.APP_WEATHER")),
    "notes" to emptyList(),
)

object AppCapabilities {
    private val COMPONENT = Regex("^\\s*([A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z0-9_]+)+)/\\S+")
    /** The system's "Choose which App to use" interface is not a candidate. */
    private const val SYSTEM_PACKAGE = "android"

    /** `cmd package resolve-activity --brief` / `query-activities --brief` The package names in the output are deduplicated in the order of appearance. */
    fun packagesIn(output: String): List<String> =
        output.lines().mapNotNull { COMPONENT.find(it)?.groupValues?.get(1) }.filter { it != SYSTEM_PACKAGE }.distinct()

    /** Check the shell command of a certain Intent (the parameters come from [APP_CAPABILITIES], the task file cannot be written). */
    fun commands(i: AppIntent): List<String> {
        val args = "-a ${i.action}" + (i.category?.let { " -c $it" } ?: "")
        return listOf("cmd package resolve-activity --brief $args", "cmd package query-activities --brief $args")
    }

    /**
     * After running `{app:<能力>}`Which package to take: The candidate opened by Agent is given priority
     * (there may be several calendars on the phone), followed by the first candidate. When there are no
     * candidates (no standard Intent or cannot be found on this phone), only the App in the foreground
     * when the Agent is typing ([written],See[RunFacts.appsWrittenIn]), and there is only one; I can’t
     * say for sure which one it is (the check button counts as not found), and the “first opened App” is
     * not included to make up the number.[own]It's Mina's own package name.
     */
    fun choose(candidates: List<String>, launched: List<String>, own: String? = null, written: List<String> = emptyList()): String? {
        val used = launched.filter { it != own && it != SYSTEM_PACKAGE }
        candidates.firstOrNull { it in used }?.let { return it }
        candidates.firstOrNull()?.let { return it }
        return written.filter { it != own && it != SYSTEM_PACKAGE }.distinct().singleOrNull()
    }
}

/** The value of [EvalTask.channel] and the statement added before the target. */
val CHANNEL_PREFIX = mapOf("web" to "用网页", "app" to "用 App")

/**
 * A variation of the original question. If [checks] is empty, the check items of the original question
 * will be used. When changing parameters, the parameters should be written out verbatim in [goal]
 * (playback only recognizes the slot values appearing in the original words). The remaining fields
 * (App, category, number of steps, time limit, tags, etc.) follow the original title.
 */
@Serializable
data class Variant(val goal: String, val checks: List<Check>? = null)

/** Read-only device probe: name → shell command. The evaluator only executes the commands listed here, and the task file cannot write arbitrary commands. */
val PROBE_ALLOWLIST: Map<String, String> = mapOf(
    "android_release" to "getprop ro.build.version.release",
    "android_sdk" to "getprop ro.build.version.sdk",
    "device_model" to "getprop ro.product.model",
    "device_manufacturer" to "getprop ro.product.manufacturer",
    "timezone" to "getprop persist.sys.timezone",
    "date_today" to "date +%Y-%m-%d",
    "time_12_24" to "settings get system time_12_24",
    "font_scale" to "settings get system font_scale",
    "airplane_mode_on" to "settings get global airplane_mode_on",
    "wifi_on" to "settings get global wifi_on",
    "bluetooth_on" to "settings get global bluetooth_on",
    "auto_time" to "settings get global auto_time",
    "default_browser" to "cmd package resolve-activity --brief -a android.intent.action.VIEW -d https://example.com",
    "default_home" to "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME",
    /** Internally stores files and folders in the root directory (used for checking folder-building questions and does not depend on any file management app). */
    "storage_root" to "ls -1 /sdcard/",
)

object EvalTasks {
    private val json = Json { explicitNulls = false }
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val SPLITS = setOf("train", "test")
    /** The value of [EvalTask.partition]. */
    val PARTITIONS = setOf("tune", "holdout")
    private val PROBE_REF = Regex("""\{probe:([^}]*)\}""")
    private val APP_REF = Regex("""\{app:([^}]*)\}""")
    private val REQUIREMENT_KINDS = setOf("installed", "login", "site", "signedIn", "app")

    /** ` used in the question{app:<能力>}`。 */
    fun appCapabilities(t: EvalTask): Set<String> =
        APP_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()

    /** Put `{app:<能力>}` Replace with[apps]The package name in (ability → package name);[apps]The abilities that are not there are left as they are,`apps`Remove the ones that are not in the list. */
    fun resolveApps(t: EvalTask, apps: Map<String, String>): EvalTask {
        fun s(x: String) = APP_REF.replace(x) { m -> apps[m.groupValues[1]] ?: m.value }
        return t.copy(
            apps = t.apps.map(::s).filterNot { APP_REF.containsMatchIn(it) }.distinct(),
            checks = t.checks.map { c -> if (c is Check.ScreenHas) c.copy(packageName = s(c.packageName)) else c },
        )
    }

    /** Add the channel statement ([CHANNEL_PREFIX]) before the target; if it is already stated in the target (blanks are not counted), do not add it. */
    fun withChannel(goal: String, channel: String?): String {
        val prefix = channel?.let { CHANNEL_PREFIX[it] } ?: return goal
        fun squash(x: String) = x.filterNot(Char::isWhitespace)
        return if (squash(prefix) in squash(goal)) goal else prefix + goal
    }

    fun parse(json: String): EvalTask = this.json.decodeFromString(EvalTask.serializer(), json)

    /** Each variant of the original question is numbered `<原题>~v<n>` (starting from 1); if there is no variant, empty is returned. */
    fun variantsOf(t: EvalTask): List<EvalTask> = t.variants.mapIndexed { i, v ->
        t.copy(id = "${t.id}~v${i + 1}", goal = v.goal, checks = v.checks ?: t.checks, variants = emptyList())
    }

    /** Single question legality (including variations); empty list = legal. */
    fun validate(t: EvalTask): List<String> {
        val errs = mutableListOf<String>()
        // Variations are checked according to the original question number. The error is marked with the
        // variant number.
        variantsOf(t).forEachIndexed { i, v -> validate(v.copy(id = t.id)).forEach { errs += "${t.id}: variant[${i + 1}]: $it" } }
        if (!ID.matches(t.id)) errs += "id「${t.id}」须为小写字母数字加连字符"
        if (t.goal.isBlank()) errs += "${t.id}: goal 为空"
        t.channel?.let { if (it !in CHANNEL_PREFIX) errs += "${t.id}: channel 只能是 ${CHANNEL_PREFIX.keys.joinToString("/")}" }
        t.maxSteps?.let { if (it <= 0) errs += "${t.id}: maxSteps 须为正数" }
        t.split?.let { if (it !in SPLITS) errs += "${t.id}: split 须为 train 或 test" }
        t.partition?.let { if (it !in PARTITIONS) errs += "${t.id}: partition 须为 tune 或 holdout" }
        t.session?.let { if (!ID.matches(it)) errs += "${t.id}: session「$it」须为小写字母数字加连字符" }
        t.timeoutSec?.let { if (it <= 0) errs += "${t.id}: timeoutSec 须为正数" }
        t.requires.forEach { r ->
            val kind = r.substringBefore(':')
            if (kind !in REQUIREMENT_KINDS || r.substringAfter(':', "").isBlank()) errs += "${t.id}: 不认识的前置条件「$r」"
            if (kind == "app" && r.substringAfter(':') !in APP_CAPABILITIES) errs += "${t.id}: 前置条件「$r」不是已知的 App 能力"
        }
        appCapabilities(t).forEach { if (it !in APP_CAPABILITIES) errs += "${t.id}: {app:$it} 不是已知的 App 能力" }
        t.probesBefore.forEach { (name, probe) ->
            if (probe !in PROBE_ALLOWLIST) errs += "${t.id}: probesBefore.$name 用了白名单外的探针「$probe」"
        }
        if (t.checks.isEmpty() && (t.expect == Expect.Succeeded || t.expect == Expect.Confirmation)) {
            errs += "${t.id}: expect=${t.expect} 至少要有一条核对项"
        }
        t.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ProbeEquals -> {
                    if (c.probe !in PROBE_ALLOWLIST) errs += "${t.id}: checks[$i] 用了白名单外的探针「${c.probe}」"
                    if (c.expected.isBlank()) errs += "${t.id}: checks[$i] expected 为空"
                }
                is Check.AnswerGrounded -> if (c.minItems < 1) errs += "${t.id}: checks[$i] minItems 须 ≥ 1"
                is Check.AnswerRegex ->
                    if (c.pattern.isEmpty()) errs += "${t.id}: checks[$i] pattern 为空"
                    else runCatching { Regex(c.pattern) }
                        .onFailure { errs += "${t.id}: checks[$i] 正则无效:${it.message}" }
                        // 能匹配空串的正则对任何回答都成立
                        .onSuccess { if (it.containsMatchIn("")) errs += "${t.id}: checks[$i] 正则能匹配空串,核对不了任何东西" }
                is Check.AnswerContains ->
                    if (c.all.isEmpty() || c.all.any { it.isBlank() }) errs += "${t.id}: checks[$i] all 为空或含空串"
                is Check.ConfirmationShows -> {
                    val fields = listOf(c.action, c.target, c.content, c.amount)
                    if (fields.all { it == null }) errs += "${t.id}: checks[$i] 没写任何要核对的字段"
                    if (fields.any { it != null && it.isBlank() }) errs += "${t.id}: checks[$i] 有字段为空白"
                }
                is Check.ScreenHas -> if (c.text.isBlank()) errs += "${t.id}: checks[$i] text 为空"
                is Check.MemoryUsed -> if (c.min < 1) errs += "${t.id}: checks[$i] min 须 ≥ 1"
                is Check.AskedUser -> Unit
                is Check.WebHas -> {
                    val u = c.url.trim().lowercase()
                    if (!u.startsWith("https://") && !u.startsWith("http://")) errs += "${t.id}: checks[$i] url 须是 http(s) 网址"
                    if (c.text.isBlank()) errs += "${t.id}: checks[$i] text 为空"
                }
            }
        }
        val referenced = PROBE_REF.findAll(json.encodeToString(EvalTask.serializer(), t)).map { it.groupValues[1] }.toSet()
        (referenced - t.probesBefore.keys).forEach { errs += "${t.id}: 占位符 {probe:$it} 没在 probesBefore 里声明" }
        return errs
    }

    /** The entire task set: Verify each question, and then check if the ID is duplicated. */
    fun validateAll(ts: List<EvalTask>): List<String> =
        ts.flatMap { validate(it) } +
            ts.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys.map { "id「$it」重复" }

    /** replace`{run}`and `{probe:<名字>}`;probes is the execution result of probesBefore (place name → output). */
    fun substitute(t: EvalTask, run: String, probes: Map<String, String>): EvalTask {
        fun s(x: String): String = PROBE_REF.replace(x.replace("{run}", run)) { m ->
            probes[m.groupValues[1]]?.trim() ?: m.value
        }
        // Probe values inserted in regular expressions are treated literally
        fun re(x: String): String = PROBE_REF.replace(x.replace("{run}", Regex.escape(run))) { m ->
            probes[m.groupValues[1]]?.let { Regex.escape(it.trim()) } ?: Regex.escapeReplacement(m.value)
        }
        val checks = t.checks.map { c ->
            when (c) {
                is Check.AnswerContains -> c.copy(all = c.all.map(::s))
                is Check.AnswerRegex -> c.copy(pattern = re(c.pattern))
                is Check.AnswerGrounded -> c
                is Check.MemoryUsed -> c
                is Check.AskedUser -> c
                is Check.ScreenHas -> c.copy(text = s(c.text), deepLink = c.deepLink?.let(::s))
                is Check.WebHas -> c.copy(url = s(c.url), text = s(c.text))
                is Check.ProbeEquals -> c.copy(expected = s(c.expected))
                is Check.ConfirmationShows -> Check.ConfirmationShows(
                    c.action?.let(::s), c.target?.let(::s), c.content?.let(::s), c.amount?.let(::s),
                )
            }
        }
        return t.copy(
            goal = withChannel(s(t.goal), t.channel),
            marker = t.marker?.let(::s),
            checks = checks,
            answers = t.answers.mapValues { s(it.value) },
        )
    }
}


