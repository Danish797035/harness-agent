package com.meenai.mina.core.knowledge

import com.meenai.mina.core.cap.sandbox.SANDBOX_PACKAGES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The scripts mentioned in the packaged Knowledge and Skills documentation match the `scripts/manifest.json` in their respective directories. */
class BundledScriptsManifestTest {
    /** Resource root directory (underneath are knowledge/apps, knowledge/sites, skills). */
    private val root = File(GuideCatalog::class.java.classLoader.getResource(KnowledgeIndex.RESOURCE)!!.toURI()).parentFile.parentFile

    /** The entry directory where the file is located, such as `knowledge/sites/zhihu.com`, `skills/research`. */
    private fun entryDir(rel: String): String = rel.split('/').let { p -> p.take(if (p[0] == "knowledge") 3 else 2).joinToString("/") }

    private fun entryFiles(): Sequence<File> = sequenceOf("knowledge/apps", "knowledge/sites", "skills")
        .flatMap { File(root, it).walkTopDown().filter(File::isFile) }
    private val ref = Regex("scripts/[A-Za-z0-9_./-]+\\.(py|sh|js|ts|mjs)")

    /** Entry Directory → List; this will fail if the list file cannot be read. */
    private val manifests: Map<String, List<ScriptEntry>> by lazy {
        entryFiles().filter { it.name == "manifest.json" && it.parentFile.name == "scripts" }.associate { f ->
            val dir = f.parentFile.parentFile.relativeTo(root).invariantSeparatorsPath
            val text = f.readText()
            val entries = ScriptManifest.parse(text)
            assertTrue("$dir: manifest is empty or unreadable", entries.isNotEmpty())
            // parse will drop unsafe paths; none of the requirements here are lost
            assertEquals("$dir: every entry must be a safe relative path", Regex("\"file\"").findAll(text).count(), entries.size)
            dir to entries
        }
    }

    /** Entry directory → Scripts mentioned in the document (license files starting with LICENSE are not counted). */
    private fun references(): Map<String, Set<String>> {
        val out = mutableMapOf<String, MutableSet<String>>()
        entryFiles().filter { it.extension == "md" && !it.name.startsWith("LICENSE") }.forEach { f ->
            val dir = entryDir(f.relativeTo(root).invariantSeparatorsPath)
            ref.findAll(f.readText()).forEach { out.getOrPut(dir) { mutableSetOf() } += it.value }
        }
        return out
    }

    @Test fun everyReferencedScriptHasManifestEntry() {
        val refs = references()
        // The directory where the script is mentioned is the same directory as the directory where the
        // manifest is located.
        assertEquals(manifests.keys, refs.keys)
        for ((dir, scripts) in refs) {
            val listed = manifests[dir]?.map { it.file }.orEmpty()
            for (s in scripts) assertTrue("$dir mentions $s but its scripts/manifest.json does not list it", s in listed)
        }
        // On the other hand: the scripts listed in the list are all mentioned in the documentation.
        for ((dir, entries) in manifests) {
            for (e in entries) assertTrue("$dir lists ${e.file} that no document mentions", e.file in refs[dir].orEmpty())
        }
    }

    @Test fun runnableScriptsExist() {
        val runnable = manifests.flatMap { (dir, es) -> es.filter { it.runnable }.map { dir to it } }
        // The script files that come with the package (the attachments under each directory scripts/ in
        // the index except the manifest) are exactly those marked as runnable in the manifest.
        val shipped = GuideCatalog.loadBundled().rows.flatMap { r ->
            r.files.filter { it.startsWith("scripts/") && it != "scripts/manifest.json" }.map { "${KnowledgeIndex.dirOf(r.kind)}/${r.name}" to it }
        }.toSet()
        assertEquals(shipped, runnable.map { (dir, e) -> dir to e.file }.toSet())
        for ((dir, e) in runnable) {
            val f = File(root, "$dir/${e.file}")
            assertTrue("$dir/${e.file} is marked runnable but missing", f.isFile)
            assertNotNull("$dir/${e.file}: no interpreter for this type", ScriptManifest.interpreterFor(e.file))
            val head = f.readLines().take(3).joinToString("\n")
            assertTrue("$dir/${e.file} needs an Apache-2.0 header", "SPDX-License-Identifier: Apache-2.0" in head)
            assertTrue("$dir/${e.file} needs --help", "Usage:" in f.readText())
        }
        // If it cannot be run, give the reason; ask for a unified explanation of account authorization.
        for ((dir, es) in manifests) for (e in es.filter { !it.runnable }) {
            assertTrue("$dir/${e.file} has no reason", !e.reason.isNullOrBlank())
            if ("auth" in e.needs) assertEquals("$dir/${e.file}", AUTH_REASON, e.reason)
        }
        // There are only manifests and files marked as runnable in the script directory.
        for ((dir, es) in manifests) {
            val files = File(root, "$dir/scripts").listFiles()!!.map { "scripts/${it.name}" }.toSet()
            assertEquals(dir, es.filter { it.runnable }.map { it.file }.toSet() + "scripts/manifest.json", files)
        }
    }

    /** Each packaged script has a test to actually run it: both its directory (the source of run_bundled_script) and the script path appear in a test file. */
    @Test fun everyShippedScriptHasATest() {
        val catalog = GuideCatalog.loadBundled()
        val tests = listOf("src/test", "../app/src/test", "../app/src/androidTest").map(::File)
            .flatMap { d -> d.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "BundledScriptsManifestTest.kt" }.toList() }
            .map { it.readText() }
        assertTrue("no test sources found", tests.isNotEmpty())
        val shipped = catalog.runnableScripts()
        assertEquals(shipped, catalog.scriptFiles().keys.filter { !it.endsWith("manifest.json") }.toSet())
        for (path in shipped) {
            val script = "scripts/" + path.substringAfter("/scripts/")
            val skill = path.substringBefore("/scripts/")
            assertTrue("$path has no test that runs it", tests.any { "\"$skill\"" in it && "\"$script\"" in it })
        }
    }

    @Test fun manifestNeedsAreInstalled() {
        for ((dir, es) in manifests) for (e in es) {
            // Networking or not: the net and network fields in needs are consistent (run_bundled_script
            // is blocked by network)
            assertEquals("$dir/${e.file}: needs 'net' must match network", "net" in e.needs, e.network)
            if (!e.runnable) continue
            for (n in e.needs) {
                assertTrue("$dir/${e.file}: runnable scripts may only need apk: packages or net, not $n", n == "net" || n.startsWith("apk:"))
                if (n.startsWith("apk:")) assertTrue("$dir/${e.file} needs $n, which SANDBOX_PACKAGES lacks", n.removePrefix("apk:") in SANDBOX_PACKAGES)
            }
        }
    }

    private companion object {
        const val AUTH_REASON = "需要账号授权,Mina 暂不支持"
    }
}
