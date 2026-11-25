package com.meenai.mina.core.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The evaluation task set in the warehouse (eval/tasks/<category>/.../<id>.json). */
class TaskSetTest {
    private val evalDir = listOf("eval", "../eval").map(::File).first { File(it, "tasks").isDirectory }
    private val tasksDir = File(evalDir, "tasks")
    private val files = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.sortedBy { it.path }.toList()
    private val tasks = files.map { it to EvalTasks.parse(it.readText()) }

    /** The file is relative to the tasks directory, such as read/content. */
    private fun group(f: File) = f.parentFile.relativeTo(tasksDir).invariantSeparatorsPath

    /** Special question sets (matching questions, memory questions) are placed in a separate directory and are not divided into categories. */
    private fun suite(f: File) = group(f).substringBefore('/').takeIf { it in SUITES }

    @Test fun allTasksParseAndValidate() {
        assertTrue(files.isNotEmpty())
        assertEquals(emptyList<String>(), EvalTasks.validateAll(tasks.map { it.second }))
        tasks.forEach { (f, t) ->
            // The web page question sheets are placed in the web directory: the categories are
            // different, and apps can be empty when the apps on the mobile phone are not touched.
            if (group(f) == WEB) {
                assertTrue("${t.id}: 网页题的 id 以 web- 开头", t.id.startsWith("web-"))
                t.apps.forEach { assertTrue("${t.id}: 缺 installed:$it", pinnedOk(t, it)) }
                return@forEach
            }
            // The category directory is consistent with the category in the question: the evaluator
            // selects the question according to the directory
            if (suite(f) == null) assertEquals(f.path, t.category.name.lowercase(), group(f).substringBefore('/'))
            // For questions that cannot be done in the first place and do not require an app (lunar
            // temperature), you can skip apps.
            assertTrue("${t.id}: apps 为空", t.apps.isNotEmpty() || t.category == EvalCategory.Impossible)
            t.apps.forEach { assertTrue("${t.id}: 缺 installed:$it", pinnedOk(t, it)) }
            if (t.category == EvalCategory.Impossible) assertEquals(t.id, Expect.Failed, t.expect)
            if (t.category == EvalCategory.Barrier) assertEquals(t.id, Expect.Takeover, t.expect)
            if (t.category == EvalCategory.Read) assertEquals(t.id, Expect.Succeeded, t.expect)
        }
    }

    /** For third-party apps, write installed:; for mobile phones, write {app:<capability>}, which does not require installed:. */
    private fun pinnedOk(t: EvalTask, app: String) = APP_TOKEN.matches(app) || "installed:$app" in t.requires

    @Test fun idsUniqueAndMatchFileNames() {
        tasks.forEach { (f, t) -> assertEquals(f.path, f.nameWithoutExtension, t.id) }
        assertEquals(tasks.size, tasks.map { it.second.id }.toSet().size)
    }

    @Test fun compositionMeetsMinimums() {
        assertTrue("总数 ${tasks.size}", tasks.size >= 60)
        val count = tasks.filter { suite(it.first) == null }.groupingBy { group(it.first) }.eachCount()
        val minimums = mapOf(
            "read/system" to 10,
            "write" to 10,
            "read/content" to 15,
            "read/shopping" to 10,
            "irreversible" to 8,
            "impossible" to 5,
            "barrier" to 2,
            WEB to 10,
        )
        minimums.forEach { (g, min) -> assertTrue("$g 只有 ${count[g] ?: 0} 题", (count[g] ?: 0) >= min) }
        assertEquals("有题不在规定的目录里", minimums.keys, count.keys)
    }

    @Test fun skillPairsAreTrainTest() {
        val skills = tasks.filter { suite(it.first) == "skills" }.map { it.second }
        assertEquals(20, skills.size)
        val groups = skills.groupBy { it.id.substringBeforeLast('-') }
        assertEquals(10, groups.size)
        groups.forEach { (g, ts) ->
            assertEquals(g, setOf("train", "test"), ts.map { it.split }.toSet())
            ts.forEach { assertEquals(it.id, "$g-${it.split}", it.id) }
            assertEquals("$g: 一组两题用同一个 App", 1, ts.map { it.apps }.toSet().size)
            assertEquals("$g: 一组两题同一类", 1, ts.map { it.category }.toSet().size)
            assertTrue("$g: 两题参数要不同", ts[0].goal != ts[1].goal)
        }
        assertTrue(skills.none { it.session != null })
    }

    @Test fun memorySessionsTellThenUse() {
        val mem = tasks.filter { suite(it.first) == "memory" }.map { it.second }
        val sessions = mem.groupBy { it.session }
        assertEquals(5, sessions.size)
        sessions.forEach { (session, ts) ->
            assertTrue(session != null)
            val (tell, use) = ts.sortedBy { it.id }.also { assertEquals(2, it.size) }
            assertTrue("${tell.id}: 第一题要让 Mina 记住", "记住" in tell.goal)
            assertTrue("${tell.id}: 第一题不核对记忆", tell.checks.none { it is Check.MemoryUsed })
            assertTrue("${use.id}: 第二题要核对用到了记忆", use.checks.any { it is Check.MemoryUsed })
            assertTrue("${use.id}: 第二题不再提「记住」", "记住" !in use.goal)
        }
        assertTrue(mem.none { it.split != null })
    }

    @Test fun partitionsAreStratified() {
        // Each question is divided into a tuning pool or a retention pool; the retention pool accounts
        // for about 30%, and is stratified by directory. Each directory has reserved questions.
        tasks.forEach { (f, t) -> assertTrue("${f.path}: 缺 partition", t.partition in EvalTasks.PARTITIONS) }
        val holdout = tasks.count { it.second.partition == "holdout" }
        assertTrue("保留池 $holdout / ${tasks.size}", holdout * 100 in tasks.size * 25..tasks.size * 35)
        tasks.groupBy { group(it.first) }.forEach { (g, ts) ->
            val h = ts.count { it.second.partition == "holdout" }
            assertTrue("$g: 保留 $h / ${ts.size}", h >= 1 && h * 100 in ts.size * 20..ts.size * 40)
        }
        // A set of matching questions and a memory session are in the same pool and will not be
        // separated.
        tasks.filter { suite(it.first) == "skills" }.map { it.second }.groupBy { it.id.substringBeforeLast('-') }
            .forEach { (g, ts) -> assertEquals(g, 1, ts.map { it.partition }.toSet().size) }
        tasks.filter { suite(it.first) == "memory" }.map { it.second }.groupBy { it.session }
            .forEach { (s, ts) -> assertEquals("$s", 1, ts.map { it.partition }.toSet().size) }
    }

    @Test fun askingTasksCanContinue() {
        // Questions that require asking first must have automatic answers. Otherwise, no one will answer
        // the questions in the review, and you will have to wait until timeout.
        tasks.map { it.second }.filter { t -> t.checks.any { it is Check.AskedUser && it.expected } }
            .also { assertTrue(it.isNotEmpty()) }
            .forEach { assertTrue("${it.id}: 要问的题缺 answers", it.answers.isNotEmpty()) }
    }

    @Test fun writeTasksHaveMarker() {
        val writes = tasks.filter { suite(it.first) != "memory" }.map { it.second }.filter { it.category == EvalCategory.Write }
        assertTrue(writes.isNotEmpty())
        writes.forEach { t ->
            val marker = t.marker
            assertTrue("${t.id}: 没有 marker", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目标里没写 marker", marker!! in t.goal)
            // The created data must be retrieved by the evaluator itself
            assertTrue("${t.id}: 没有按 marker 核对", t.checks.any { (it is Check.ScreenHas && marker in it.text) || (it is Check.ProbeEquals && marker in it.expected) })
        }
    }

    /** When asking, only the step that costs money should be asked first: the ordering questions will stop on the confirmation card; the free questions such as sending and deleting will be completed directly without asking first. */
    @Test fun irreversibleTasksAskOnlyBeforeSpending() {
        val irreversible = tasks.map { it.second }.filter { it.category == EvalCategory.Irreversible }
        assertTrue(irreversible.isNotEmpty())
        val (spending, free) = irreversible.partition { it.expect == Expect.Confirmation }
        assertTrue(spending.isNotEmpty() && free.isNotEmpty())
        spending.forEach { t ->
            val card = t.checks.filterIsInstance<Check.ConfirmationShows>()
            // The card should show where you are, what you do, and how much it costs.
            assertTrue("${t.id}: 只有下单要确认", card.isNotEmpty() && card.all { it.action == "下单" })
            assertTrue("${t.id}: 下单要核对对象和金额", card.any { it.target != null && it.amount != null })
            // It will cost real account money: it will only run when the user is present and the account
            // is manually marked as logged in.
            assertTrue("${t.id}: 缺 signedIn: 前置条件", t.apps.all { "signedIn:$it" in t.requires })
            assertTrue("${t.id}: 缺说明", !t.note.isNullOrBlank())
        }
        free.forEach { t ->
            assertEquals(t.id, Expect.Succeeded, t.expect)
            assertTrue("${t.id}: 不花钱的题不该有确认卡片", t.checks.none { it is Check.ConfirmationShows })
            // Once the user has made it clear what they want to do, they should not ask again; they
            // should only ask questions that only the user knows, such as which item to delete.
            val asked = t.checks.filterIsInstance<Check.AskedUser>()
            assertTrue("${t.id}: 要核对问没问用户", asked.size == 1)
            // Irreversible things are only done on the marked data of this question.
            val marker = t.marker
            assertTrue("${t.id}: 要有含 {run} 的 marker", marker != null && "{run}" in marker)
            assertTrue("${t.id}: 目标里要写 marker", marker!! in t.goal)
            // Questions sent to real accounts: only run when the user is present and the account is
            // manually marked as logged in. Check in the App after sending.
            if (t.apps.any { !it.startsWith("{app:") }) {
                assertTrue("${t.id}: 缺 signedIn: 前置条件", t.apps.all { "signedIn:$it" in t.requires })
                assertTrue("${t.id}: 缺说明", !t.note.isNullOrBlank())
                assertTrue("${t.id}: 要按 marker 核对发出去了", t.checks.any { it is Check.ScreenHas && marker in it.text })
            }
        }
    }

    @Test fun systemAppsAreNotPinned() {
        // The package names of Apps that "come with your mobile phone" vary depending on the model:
        // write the ability in the question, and when running, use the standard Intent to find the one
        // on this phone, and the package name will not be fixed for verification.
        val system = tasks.map { it.second }.filter { "手机自带" in it.goal }
        assertTrue(system.isNotEmpty())
        system.forEach { t ->
            t.apps.forEach { assertTrue("${t.id}: 手机自带的 App 写 {app:<能力>},不写包名:$it", APP_TOKEN.matches(it)) }
            t.checks.filterIsInstance<Check.ScreenHas>().forEach { assertTrue("${t.id}: screen_has 不钉包名", APP_TOKEN.matches(it.packageName)) }
            t.requires.forEach { assertFalse("${t.id}: 手机自带的 App 不写 installed:", it.startsWith("installed:")) }
        }
    }

    private companion object {
        val SUITES = setOf("skills", "memory")
        val APP_TOKEN = Regex("^\\{app:[a-z]+\\}$")
        /** Directory of web page titles. */
        const val WEB = "web"
    }

    @Test fun noLocalPaths() {
        val scanned = evalDir.walkTopDown().filter { it.isFile }.toList()
        assertTrue(scanned.size >= files.size)
        scanned.forEach { f ->
            assertFalse("${f.path} mentions a local path", f.readText().contains("/Users/"))
        }
    }
}
