package com.meenai.mina.core

import com.meenai.mina.core.assembly.knownToolNames
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Documents and code are not disconnected: the file path, Kotlin class name, and tool name enclosed in
 * backticks in the document must actually exist in the warehouse. Scope: README.md, all.md under docs/
 * (including README in each language in docs/readme/), eval/README.md, tools/sandbox/README.md. In
 * addition, for the number of variables in the same documents that are not hard-coded (how many built-in
 * knowledge, tools, service providers, and evaluation questions are there), see
 * [docsDoNotHardCodeCounts].
 *
 * Only check the writing method "The entire backtick content is an identifier"; the following are not
 * checked (rules, not whitelists):
 * - - The fenced code block (```) is not read as a whole; the spaces in the backticks in the line are
 * commands or code fragments;
 * - - Contains < > *... { } ( ) =: " ' $,; | # are placeholders, wildcards, calls, key values, and
 * sample values;
 * - - The one starting with / ~ is the device or system path, and the one containing:// is the URL;
 * - - Path: with /, only the first paragraph is a directory or file that actually exists at the root of
 * the warehouse (`files/sandbox/rootfs`, `read/system`, these are runtime product or category names);
 * Without /, only code extensions (kt, py, xml, etc.) are searched, and json, md, txt and the like are
 * often runtime products; when searching: they exist directly in the root directory, or are the
 * abbreviation of "module/package path/file" (`app/task/X.kt`), or are omitted with... in the middle
 * (`core/.../X.kt`), or the short file name is anywhere in the warehouse;
 * - - Class name: big camel case and at least two capital letters (words like `Mina` are not appropriate
 * class names); there is class/object/interface/typealias in the source code of core and app (including
 * tests)
 * Statement or file with the same name; source code imported from packages outside this repository
 * (Android, JDK, library classes) are not managed by this repository;
 * - - Tool name: only search the snake_case "the first paragraph is the first paragraph of the existing
 * tool name" (parameter names such as `max_seconds` are not searched), compare with [knownToolNames],
 * That's a list from the Tool Registry, not a gold sample snapshot.
 */
class DocsConsistencyTest {
    private val root = File("..").canonicalFile
    private val skipDirs = setOf("build", ".gradle", ".git", ".kotlin", ".idea", "reference", "dist", ".claude", ".omc", "node_modules")

    private val repoFiles: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }
    private val repoDirs: List<String> by lazy {
        root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isDirectory && it != root }
            .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    private val sourceFiles: List<File> by lazy {
        listOf("core/src", "app/src").map { File(root, it) }.filter { it.isDirectory }
            .flatMap { d -> d.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }
    }
    private val sourceText: String by lazy { sourceFiles.joinToString("\n") { it.readText() } }
    private val sourceNames: Set<String> by lazy { sourceFiles.map { it.nameWithoutExtension }.toSet() }

    private fun docs(): List<File> {
        val list = mutableListOf(File(root, "README.md"), File(root, "eval/README.md"), File(root, "tools/sandbox/README.md"))
        File(root, "docs").takeIf { it.isDirectory }?.walkTopDown()?.filter { it.isFile && it.extension == "md" }?.let { list += it }
        return list.filter { it.isFile }
    }

    /** After removing the fenced code block, take the content within the backticks in all lines. */
    private fun spans(text: String): List<String> {
        var fenced = false
        val prose = text.lines().filter { line ->
            if (line.trimStart().startsWith("```")) { fenced = !fenced; false } else !fenced
        }.joinToString("\n")
        return Regex("`([^`\n]+)`").findAll(prose).map { it.groupValues[1].trim() }.toList()
    }

    private val notIdentifier = Regex("""[\s<>*…{}()=:"'$,;|#]""")
    private val pathExtensions = setOf("kt", "kts", "java", "md", "json", "xml", "txt", "yml", "yaml", "gradle", "toml", "properties", "py", "sh", "js", "html", "pro", "aidl", "cpp", "h")
    private val tool = Regex("""[a-z][a-z0-9]*(_[a-z0-9]+)+""")
    private val classLike = Regex("""[A-Z][A-Za-z0-9]*""")

    private val toolNames: Set<String> by lazy { knownToolNames() }
    private val toolPrefixes: Set<String> by lazy { toolNames.map { it.substringBefore('_') }.toSet() }

    private fun isPath(s: String): Boolean =
        !s.startsWith("/") && !s.startsWith("~") && "://" !in s &&
            ('/' in s || s.substringAfterLast('.', "").let { it in pathExtensions && s.length > it.length + 1 })

    /** What is written in the document are things that exist only when the App is running (directories and files on the device) and are not in the warehouse; explain why one by one. */
    private val runtimeOnly = mapOf(
        "eval/logged-in.txt" to "评测的人工登录标记,放在设备上 App 的外部文件目录,不进仓库",
    )

    /** Only the first-level directory or the path at the beginning of the file that exists under the warehouse root, as well as the short file name of the code class, refer to the things in the warehouse; the rest are runtime products or relative notations. */
    private val topLevel: Set<String> by lazy { File(root, ".").list().orEmpty().filter { it !in skipDirs }.toSet() }
    private val codeExtensions = setOf("kt", "kts", "java", "py", "sh", "gradle", "xml", "aidl", "cpp", "h")

    private fun pathExists(rel: String): Boolean {
        val clean = rel.removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean in runtimeOnly) return true
        val dir = rel.endsWith("/")
        // Use... in the middle to omit the writing: the beginning is the real directory prefix, and the
        // end is the end of a certain file path (can be without extension)
        if ("..." in clean) {
            val head = clean.substringBefore("...").trimEnd('/')
            val tail = clean.substringAfterLast("...").trimStart('/')
            return repoFiles.any { f -> f.startsWith(head) && (f.endsWith("/$tail") || f.substringBeforeLast('.').endsWith("/$tail")) }
        }
        if ('/' !in clean) {
            return if (clean.substringAfterLast('.', "") in codeExtensions) repoFiles.any { it == clean || it.endsWith("/$clean") } else true
        }
        val first = clean.substringBefore('/')
        if (first !in topLevel) return true
        if (File(root, clean).exists()) return true
        // The abbreviation of "module/package path/file" (such as app/task/X.kt refers to
        // app/src/main/java/.../task/X.kt): any path under the module can end with the following part
        val rest = clean.substringAfter('/')
        return if (dir) repoDirs.any { it.startsWith("$first/") && (it.endsWith("/$rest")) }
        else repoFiles.any { it.startsWith("$first/") && it.endsWith("/$rest") }
    }

    private fun classExists(name: String): Boolean =
        name in sourceNames || Regex("""\b(class|object|interface|typealias)\s+$name\b""").containsMatchIn(sourceText)

    private fun importedFromOutside(name: String): Boolean =
        Regex("""(?m)^import\s+(?!com\.wangzan101\.mina\.)[\w.]+\.$name\b""").containsMatchIn(sourceText)

    private fun problems(doc: File): List<String> {
        val rel = doc.relativeTo(root).invariantSeparatorsPath
        return spans(doc.readText()).distinct().mapNotNull { s ->
            if (notIdentifier.containsMatchIn(s)) return@mapNotNull null
            when {
                isPath(s) -> if (pathExists(s)) null else "$rel: 路径 `$s` 在仓库里找不到"
                tool.matches(s) -> if (s.substringBefore('_') !in toolPrefixes || s in toolNames) null else "$rel: 工具名 `$s` 不在现有工具里"
                classLike.matches(s) && s.count { it.isUpperCase() } >= 2 && s.any { it.isLowerCase() } ->
                    if (classExists(s) || importedFromOutside(s)) null else "$rel: 类名 `$s` 在 core/app 的源码里找不到"
                else -> null
            }
        }
    }

    /**
     * Don’t write down the exact number of things that will change: “18 apps”, “more than 60 tools”,
     * “128 review questions” (in English, “18 apps”, “60+ tools”), this type of writing will expire as
     * soon as the content is added. To make a list, use the paragraph (`./gradlew updateReadme`)
     * generated in the README; add [countOk] at the end of the line where you really want to write the
     * number and explain why.
     */
    private val hardCount = Regex(
        """\d+\s*多?\s*(份|个|道|家)\s*(内置)?\s*(App|app|网站|站点|技能|工具|服务商|预设|(真机)?评测题)""" +
            """|\b\d+\+?\s+(built-in\s+)?(apps?|websites?|sites?|skills?|tools?|providers?|presets?|(on-device\s+)?eval\s+tasks?)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val countOk = "<!-- count-ok -->"

    @Test fun docsDoNotHardCodeCounts() {
        val bad = docs().flatMap { doc ->
            val rel = doc.relativeTo(root).invariantSeparatorsPath
            doc.readLines().withIndex().filter { (_, line) -> countOk !in line && hardCount.containsMatchIn(line) }
                .map { (i, line) -> "$rel:${i + 1}: 「${hardCount.find(line)!!.value}」" }
        }
        assertTrue("文档里写死了会变的个数,改成不带个数的说法(清单用 README 的生成段落):\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun countPatternCatchesTypicalWording() {
        listOf("18 份 App", "12 份网站", "4 份技能", "60 多个工具", "128 道真机评测题", "7 家服务商",
            "18 apps", "12 websites", "4 skills", "60 tools", "60+ tools", "7 providers", "18 built-in apps", "128 eval tasks").forEach {
            assertTrue(it, hardCount.containsMatchIn(it))
        }
        listOf("最多 3 条知识", "一次最多 9 个", "每个任务最多 3 份,关联的技能",
            "Android 13 and later", "python3 tools/x.py", "up to 3 notes", "arm64 devices").forEach {
            assertTrue(it, !hardCount.containsMatchIn(it))
        }
    }

    @Test fun inputsAreFound() {
        assertTrue("找不到仓库根目录(工作目录应为 core/)", File(root, "settings.gradle.kts").isFile)
        assertTrue("没找到文档", docs().size >= 5)
        assertTrue(toolNames.contains("knowledge_read"))
        assertTrue(classExists("ToolDef"))
        assertTrue(spans(docs().joinToString("\n") { it.readText() }).size > 50)
    }

    @Test fun docsMentionOnlyThingsThatExist() {
        val bad = docs().flatMap(::problems)
        assertTrue("文档里提到了仓库里没有的东西:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
