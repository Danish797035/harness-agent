package com.meenai.mina.settings

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelEndpoint
import com.meenai.mina.core.model.ModelProtocol
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.model.ModelSession
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import com.meenai.mina.core.usage.UsagePurpose
import com.meenai.mina.core.usage.UsageSource
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.KeyGenerator

class ProviderStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val built = mutableListOf<Pair<ProviderConfig, String?>>()
    private val endpoints = mutableListOf<ModelEndpoint?>()

    private val setups = mutableListOf<ClientSetup>()

    private fun store() = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mina/1.2.3") { cfg, apiKey, endpoint, setup ->
        built += cfg to apiKey
        setups += setup
        endpoints += endpoint
        FakeModel(cfg.capabilities)
    }

    private fun cfg(id: String, tools: Boolean = true, vision: Boolean = false) = ProviderConfig(
        id = id, kind = ProviderConfig.Kind.OpenAiCompat, baseUrl = "https://example.test/v1", model = "m-$id",
        capabilities = Capabilities(toolCalls = tools, vision = vision, contextTokens = 64_000, reasoning = false),
        name = "P $id",
    )

    private val none = Capabilities(toolCalls = false, vision = false, contextTokens = 64_000, reasoning = false)

    /** Save a configuration and make it the main model (configurations no one uses will be cleared). */
    private fun ProviderStore.saveAsMain(c: ProviderConfig, apiKey: String?) {
        save(c, apiKey)
        assertTrue(choose(ModelRole.Main, RoleChoice.Model(c.id)))
    }

    private fun connected(s: ProviderStore, name: String = "P", key: String = "k") =
        s.connect(name, ProviderConfig.Kind.OpenAiCompat, "https://${name.lowercase()}.test/v1", key, listOf(ListedModel("m1"), ListedModel("m2")))

    // ---------- Choice of use ----------

    @Test fun modelContextSurvivesPruningAndIsSharedOnlyWithinTheSameProviderAndModel() {
        val s = store()
        val first = connected(s)
        val second = connected(s, "Other")
        s.use(ModelRole.Main, first.id, "m1", none)
        s.use(ModelRole.Vision, second.id, "m1", none)
        val cfg = s.configFor(ModelRole.Main)!!
        val saved = ModelContextSettings(1_000_000, 600_000)
        assertTrue(s.setModelContext(cfg.id, saved))
        for (role in listOf(ModelRole.Main, ModelRole.Light)) {
            val caps = s.modelFor(role)!!.capabilities
            assertEquals(1_000_000, caps.contextTokens)
            assertEquals(600_000, caps.compactionTriggerTokens)
        }
        assertEquals(64_000, s.modelFor(ModelRole.Vision)!!.capabilities.contextTokens)
        assertNull(s.modelFor(ModelRole.Vision)!!.capabilities.compactionTriggerTokens)
        s.use(ModelRole.Main, first.id, "m2", none)
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertFalse(s.setModelContext(cfg.id, ModelContextSettings()))
        val reloaded = store()
        reloaded.use(ModelRole.Main, first.id, "m1", none)
        assertEquals(saved, reloaded.provider(first.id)!!.contextSettings["m1"])
        assertEquals(600_000, reloaded.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(reloaded.setModelContext(reloaded.configFor(ModelRole.Main)!!.id, ModelContextSettings()))
        assertNull(store().provider(first.id)!!.contextSettings["m1"])
    }

    @Test fun manualTriggerUsesCoreBoundsAndASmallerReselectedWindowKeepsTheStoredValue() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 1_000_000))
        val id = s.configFor(ModelRole.Main)!!.id
        for (value in listOf(500_000, 980_000, 950_000)) {
            assertTrue(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        for (value in listOf(0, 499_999, 980_001)) {
            assertFalse(s.setModelContext(id, ModelContextSettings(triggerTokens = value)))
        }
        assertFalse(s.setModelContext(id, ModelContextSettings(windowTokens = -1)))
        s.use(ModelRole.Main, p.id, "m2", none)
        s.use(ModelRole.Main, p.id, "m1", none.copy(contextTokens = 32_000))
        val saved = store().provider(p.id)!!.contextSettings.getValue("m1")
        assertEquals(950_000, saved.triggerTokens)
        assertEquals(24_000, saved.limits(s.modelFor(ModelRole.Main)!!.capabilities.contextTokens).trigger)
    }

    @Test fun missingContextFieldsUseAutoAndMalformedEntriesDoNotDiscardOtherModels() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val file = File(tmp.root, "providers.json")
        val original = file.readText()
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        val entries = """{"m1":{"windowTokens":1000000},"m2":{"triggerTokens":600000},"bad":{"triggerTokens":{}}}"""
        file.writeText(original.replace("\"services\":[{", "\"services\":[{\"contextSettings\":$entries,"))
        assertEquals(mapOf("m1" to ModelContextSettings(1_000_000), "m2" to ModelContextSettings(triggerTokens = 600_000)),
            store().provider(p.id)!!.contextSettings)
    }

    /** A file that is broken in the middle of writing cannot be turned into an empty configuration: restore from the last good copy, and the key is still there. */
    @Test fun truncatedConfigRecoversFromLastGoodCopy() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        val file = File(tmp.root, "providers.json")
        val good = file.readText()
        assertTrue("backup written", File(tmp.root, "providers.json.bak").isFile)
        file.writeText(good.take(good.length / 2))

        val reopened = store()
        assertEquals(listOf("a"), reopened.list().map { it.id })
        assertEquals(good, file.readText())
        assertEquals("sk-a", reopened.apiKey("a"))
    }

    /** An unreadable configuration remains in the file and cannot be permanently deleted due to writeback. */
    @Test fun unreadableEntryIsLeftOnDiskInsteadOfBeingDropped() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        val file = File(tmp.root, "providers.json")
        val keys = File(tmp.root, "provider-keys.json")
        val broken = file.readText().replace("\"kind\":\"OpenAiCompat\"", "\"kind\":\"NoSuchKind\"")
        val keysBefore = keys.readText()
        file.writeText(broken)

        val reopened = store()
        assertTrue("no provider read", reopened.list().isEmpty())
        assertEquals("file preserved", broken, file.readText())
        assertEquals("keys preserved", keysBefore, keys.readText())
    }

    @Test fun failedContextSaveLeavesBothTheStoredAndActiveModelAloneAndCanBeRetried() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        val id = s.configFor(ModelRole.Main)!!.id
        val saved = ModelContextSettings(triggerTokens = 40_000)
        val blocked = File(tmp.root, "providers.json.tmp")
        assertTrue(blocked.mkdir())
        assertFalse(s.setModelContext(id, saved))
        assertNull(s.modelFor(ModelRole.Main)!!.capabilities.compactionTriggerTokens)
        assertTrue(blocked.delete())
        assertNull(store().provider(p.id)!!.contextSettings["m1"])
        assertTrue(s.setModelContext(id, saved))
        assertEquals(saved, store().provider(p.id)!!.contextSettings["m1"])
    }

    @Test
    fun freshInstallHasNoMainVisionOffAndLightSameAsMain() {
        val s = store()
        assertEquals(RoleChoices(main = null, vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.modelFor(ModelRole.Main))
        assertNull(s.modelFor(ModelRole.Vision))
        assertNull(s.modelFor(ModelRole.Light))
    }

    @Test
    fun lightSameAsMainResolvesToMainAndVisionStaysOffEvenIfMainCanSee() {
        val s = store()
        s.saveAsMain(cfg("a", vision = true), "sk-a")
        assertEquals("a", s.configFor(ModelRole.Light)?.id)
        // There is no longer an implicit rule that "the main model can also view pictures if it can view
        // pictures"
        assertNull(s.configFor(ModelRole.Vision))
        val m = s.modelFor(ModelRole.Main)
        assertEquals("套上计量后能力照旧", cfg("a", vision = true).capabilities, m?.capabilities)
        assertEquals("a" to "sk-a", built.single().first.id to built.single().second)
    }

    @Test
    fun visionSameAsMainUsesMainAndMainItselfSeesUntilSwitchedAway() {
        val s = store()
        s.saveAsMain(cfg("a", vision = false), "sk-a")
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        val v = s.configFor(ModelRole.Vision)!!
        assertEquals("a", v.id)
        assertTrue("用户说主模型能看图,看图这一路照发截图", v.capabilities.vision)
        s.modelFor(ModelRole.Vision)
        assertTrue(built.last().first.capabilities.vision)
        assertEquals("sk-a", built.last().second)
        assertTrue("用户说了主模型能看图,主模型自己办事也直接看截图", s.configFor(ModelRole.Main)!!.capabilities.vision)
        // Reopening is still happening
        assertEquals(RoleChoice.SameAsMain, store().roles.value.vision)
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.Off))
        assertFalse("换走了就回到存着的标记", s.configFor(ModelRole.Main)!!.capabilities.vision)
    }

    @Test
    fun pickingTheMainModelAsVisionModelMakesMainSeeAndSwitchingAwayUndoesIt() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m1", none))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        s.modelFor(ModelRole.Main)
        assertTrue("建出来的主模型附图", built.last().first.capabilities.vision)
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        assertFalse("看图换成别的模型,主模型回到列表和初值说的", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertFalse("没改存着的配置", store().list().first { it.model == "m1" }.capabilities.vision)
    }

    @Test
    fun userSaysModelCanSeeOverridesUnknownAndPersists() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertFalse("列表没写,按初值", s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val reopened = store()
        assertEquals(mapOf("m1" to true), reopened.provider(p.id)!!.visionOverrides)
        assertTrue("重开仍在", reopened.configFor(ModelRole.Main)!!.capabilities.vision)
        // Users can also turn it off: when viewing pictures using the main model, the main model does
        // not attach pictures as the user said, and the process of viewing pictures remains the same.
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertTrue(s.setModelVision(p.id, "m1", false))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.vision)
        assertFalse(s.setModelVision("missing", "m1", true))
    }

    @Test
    fun listingSaysNoVisionStaysNoUnlessTheUserOverrides() {
        val s = store()
        val p = s.connect("P", ProviderConfig.Kind.OpenAiCompat, "https://p.test/v1", "k", listOf(ListedModel("m1", toolCalls = true, vision = false)))
        val o = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertFalse(o.canSee)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", o.capabilities))
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.vision)
        assertTrue(s.setModelVision(p.id, "m1", true))
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.vision)
        val after = ModelOptions.ofProvider(s.provider(p.id)!!, s.list()).single()
        assertTrue(after.canSee)
        assertEquals(true, after.visionByUser)
    }

    @Test
    fun visionSameAsMainWithoutMainHasNoVisionModel() {
        val s = store()
        assertTrue(s.choose(ModelRole.Vision, RoleChoice.SameAsMain))
        assertNull(s.configFor(ModelRole.Vision))
    }

    @Test
    fun choicesTheRoleDoesNotTakeAreRejected() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertFalse(s.choose(ModelRole.Main, RoleChoice.SameAsMain))
        assertFalse(s.choose(ModelRole.Main, RoleChoice.Off))
        assertFalse(s.choose(ModelRole.Light, RoleChoice.Off))
        assertFalse("不存在的配置", s.choose(ModelRole.Main, RoleChoice.Model("zzz")))
        assertEquals(RoleChoices(main = "a"), s.roles.value)
    }

    @Test
    fun explicitChoiceIsNotBlockedByCapabilityFlagsAndMarksThem() {
        val s = store()
        val p = connected(s)
        assertTrue(s.use(ModelRole.Main, p.id, "m1", none))
        assertTrue(s.use(ModelRole.Vision, p.id, "m2", none))
        val main = s.configFor(ModelRole.Main)!!
        val vision = s.configFor(ModelRole.Vision)!!
        assertEquals("m1" to "m2", main.model to vision.model)
        assertTrue("选作主模型就记工具调用", main.capabilities.toolCalls)
        assertTrue("选作看图就算能看图", vision.capabilities.vision)
        // Lightweight does not require any ability
        assertTrue(s.use(ModelRole.Light, p.id, "m2", none))
        assertEquals(vision.id, s.configFor(ModelRole.Light)?.id)
        assertFalse(s.use(ModelRole.Main, "missing", "m1", none))
    }

    @Test
    fun usingTheSameModelReusesItsConfig() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        assertEquals(1, s.list().size)
        assertEquals(s.roles.value.main, (s.roles.value.light as RoleChoice.Model).configId)
        assertEquals("k", s.apiKey(s.roles.value.main!!))
    }

    @Test
    fun lightUsersGetTheLightModelAndNotTheMainOne() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        built.clear()
        s.modelFor(ModelRole.Light)
        assertEquals("m2" to "k", built.single().first.model to built.single().second)
        built.clear()
        s.modelFor(ModelRole.Main)
        assertEquals("m1", built.single().first.model)
    }

    // ---------- Delete configurations that no one uses ----------

    @Test
    fun configsNoRoleUsesAreDeletedButTheProviderKeyStays() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        assertEquals(listOf("m1", "m2"), s.list().map { it.model })
        // Lightly changed back to the main model: m2 no one uses it anymore
        assertTrue(s.choose(ModelRole.Light, RoleChoice.SameAsMain))
        assertEquals(listOf("m1"), s.list().map { it.model })
        // Change the main model: m1 no one uses it anymore
        s.use(ModelRole.Main, p.id, "m2", none)
        assertEquals(listOf("m2"), s.list().map { it.model })
        assertEquals("k", s.providerKey(p.id))
        assertEquals("k", s.apiKey(s.list().single().id))
        assertEquals(listOf("m2"), store().list().map { it.model })
    }

    @Test
    fun visionSameAsMainKeepsTheMainConfigAndNothingElse() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertEquals(listOf("m1"), s.list().map { it.model })
        assertEquals(setOf(s.roles.value.main), s.roles.value.referenced)
    }

    @Test
    fun loadDropsUnusedConfigsAndChoicesPointingNowhere() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("b"), null)
        assertEquals(2, s.list().size)
        val reopened = store()
        assertEquals(listOf("a"), reopened.list().map { it.id })
        // Manually modified files: The picture points to a non-existent configuration and is returned
        // without use.
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace("\"Vision\":{\"use\":\"none\"}", "\"Vision\":{\"use\":\"model\",\"config\":\"gone\"}"))
        assertEquals(RoleChoice.Off, store().roles.value.vision)
    }

    // ---------- Key ----------

    @Test
    fun keyIsNotStoredInPlainPrefs() {
        val secret = "sk-very-secret-0123456789"
        store().saveAsMain(cfg("a"), secret)
        tmp.root.walkTopDown().filter { it.isFile }.forEach { f ->
            assertFalse("plaintext key found in ${f.name}", f.readBytes().toString(Charsets.ISO_8859_1).contains(secret))
        }
        assertEquals(secret, store().apiKey("a"))
    }

    @Test
    fun saveWithNullKeyKeepsExistingKey() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        s.save(cfg("a").copy(model = "other"), null)
        assertEquals("sk-a", s.apiKey("a"))
        assertEquals("other", s.list().single().model)
        assertTrue(s.hasKey(s.list().single().providerId))
    }

    @Test
    fun undecryptableKeyReadsAsMissing() {
        store().saveAsMain(cfg("a"), "sk-a")
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(ProviderStore(tmp.root, AesGcmKeyCipher { otherKey }).apiKey("a"))
    }

    /** The name of the model passed through the trial call is saved with the service provider; it is merged when connecting to the same provider again; it is empty when the old file does not have this item. */
    @Test
    fun verifiedModelsPersist() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), verified = setOf("a"))
        assertEquals(setOf("a"), p.verifiedModels)
        s.markVerified(p.id, "b")
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", null, emptyList(), verified = setOf("c"))
        assertEquals(setOf("a", "b", "c"), store().provider(p.id)?.verifiedModels)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(Regex(",\"verifiedModels\":\\[[^\\]]*\\]"), ""))
        assertFalse(f.readText().contains("verifiedModels"))
        assertEquals(emptySet<String>(), store().provider(p.id)?.verifiedModels)
    }

    @Test
    fun connectedProviderWithoutModelsPersistsWithItsListAndKey() {
        val s = store()
        val listed = listOf(ListedModel("a", displayName = "A", toolCalls = true, contextTokens = 128_000), ListedModel("b", vision = false))
        val p = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", "sk-d", listed)
        assertTrue("连上了还没选模型", s.list().isEmpty())
        assertNull(s.roles.value.main)
        val reopened = store()
        assertEquals(listOf(p), reopened.providers.value)
        assertEquals(listed, reopened.providers.value.single().models)
        assertEquals("sk-d", reopened.providerKey(p.id))
        // Connect to the same company again: update Key and list, do not add repeatedly
        val again = s.connect("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com/", "sk-new", listOf(ListedModel("c")))
        assertEquals(p.id, again.id)
        assertEquals(1, s.providers.value.size)
        assertEquals("sk-new", s.providerKey(p.id))
        assertEquals(listOf("c"), store().providers.value.single().models!!.map { it.id })
        // Those that have not been listed are null, and those that have been listed but are not in the
        // list are empty lists.
        s.rememberModels(p.id, emptyList())
        assertEquals(emptyList<ListedModel>(), store().providers.value.single().models)
    }

    // ---------- Skip the test and save directly ----------

    /** The one saved by skipping the test is marked; re-reading the file is also included (older version of the file does not have this item, it is counted as tested). */
    @Test
    fun untestedProviderPersistsItsFlag() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        assertTrue(p.savedUntested)
        assertTrue("重新读入还在", store().provider(p.id)!!.savedUntested)
        // Those saved without skipping tests do not have this mark
        val other = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", emptyList())
        assertFalse(other.savedUntested)
        assertFalse(store().provider(other.id)!!.savedUntested)
    }

    /** Once a model has been listed or a model has been tested, "not tested" is no longer true. */
    @Test
    fun listingOrVerifyingClearsTheUntestedFlag() {
        val s = store()
        val listed = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.rememberModels(listed.id, listOf(ListedModel("m1")))
        assertFalse("列出来了就算测过", store().provider(listed.id)!!.savedUntested)

        val verified = s.connect("Z", ProviderConfig.Kind.OpenAiCompat, "https://z.test/v1", "k", emptyList(), untested = true)
        assertTrue(s.provider(verified.id)!!.savedUntested)
        s.markVerified(verified.id, "m1")
        assertFalse("核实过就算测过", s.provider(verified.id)!!.savedUntested)
        assertEquals(setOf("m1"), s.provider(verified.id)!!.verifiedModels)
    }

    /** The main model gives that company when it has not passed the test (no list, manual filling, not verified); it does not count when it has been verified or the company has a list. */
    @Test
    fun untestedMainProviderFollowsTheMainModel() {
        val s = store()
        assertNull("没有主模型", s.untestedMainProvider())
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", emptyList(), untested = true)
        s.markTyped(p.id, "m1")
        s.use(ModelRole.Main, p.id, "m1", none)
        assertEquals(p.id, s.untestedMainProvider()?.id)
        s.markVerified(p.id, "m1")
        assertNull("试通过了", s.untestedMainProvider())
        val listed = s.connect("Y", ProviderConfig.Kind.OpenAiCompat, "https://y.test/v1", "k", listOf(ListedModel("m2")))
        s.use(ModelRole.Main, listed.id, "m2", none)
        assertNull("从列表里选的不算", s.untestedMainProvider())
    }

    /** Skip the test and save again the company that has already listed the model: keep the existing list, do not overwrite it with an empty list, and it is still "tested". */
    @Test
    fun savingUntestedAgainKeepsAnExistingModelList() {
        val s = store()
        val p = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listOf(ListedModel("m1")))
        val again = s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k2", emptyList(), untested = true)
        assertEquals(p.id, again.id)
        assertEquals(listOf("m1"), again.models!!.map { it.id })
        assertFalse("已经列过列表的不算没测过", again.savedUntested)
        assertEquals("k2", s.providerKey(p.id))
    }

    @Test
    fun modelsOfOneProviderShareOneKey() {
        val s = store()
        val p = connected(s, key = "k1")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.updateProviderKey(p.id, "k2")
        assertEquals(listOf("k2", "k2"), s.list().map { s.apiKey(it.id) })
        s.modelFor(ModelRole.Main)
        assertEquals("k2", built.last().second)
        // There is only one copy of the Key file (stored by service provider)
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(p.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun localServiceWithoutKeyHasNoKey() {
        val s = store()
        val p = s.connect("local", ProviderConfig.Kind.OpenAiCompat, "http://192.168.1.5:8000/v1", "", emptyList())
        assertFalse(s.hasKey(p.id))
    }

    @Test
    fun configsExposeCurrentList() {
        val s = store()
        s.saveAsMain(cfg("a"), "sk-a")
        assertEquals(s.list(), s.configs.value)
        assertEquals(1, s.configs.value.size)
    }

    // ---------- Delete service provider ----------

    @Test
    fun removeProviderDropsItsModelsAndKeyAndResetsItsRoles() {
        val s = store()
        val p = connected(s, key = "k1")
        s.save(cfg("other"), "sk-o")
        s.choose(ModelRole.Main, RoleChoice.Model("other"))
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.use(ModelRole.Light, p.id, "m1", none)
        s.removeProvider(p.id)
        assertEquals(listOf("other"), s.list().map { it.id })
        assertEquals(RoleChoices(main = "other", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertNull(s.providerKey(p.id))
        assertEquals(listOf("P other"), s.providers.value.map { it.name })
        // Data will be consistent after restarting
        val reopened = store()
        assertEquals(listOf("other"), reopened.list().map { it.id })
        assertEquals(s.roles.value, reopened.roles.value)
        assertNull(reopened.providerKey(p.id))
        assertEquals("sk-o", reopened.apiKey("other"))
    }

    @Test
    fun removingTheMainProviderLeavesMainUnsetAndKeepsOtherRoles() {
        val s = store()
        val a = connected(s, "A")
        val b = connected(s, "B")
        s.use(ModelRole.Main, a.id, "m1", none)
        s.use(ModelRole.Vision, b.id, "m1", none)
        s.removeProvider(a.id)
        assertNull(s.roles.value.main)
        assertEquals(b.id, s.configFor(ModelRole.Vision)?.providerId)
        // When reopening, the remaining one will not be regarded as the main model (that is the rule of
        // the old version)
        assertNull(store().roles.value.main)
        assertEquals(b.id, store().configFor(ModelRole.Vision)?.providerId)
    }

    // ---------- Old version files ----------

    private fun enc(k: String): String = java.util.Base64.getEncoder().encodeToString(AesGcmKeyCipher { key }.encrypt(k.toByteArray()))

    private fun legacy(id: String, name: String, url: String, model: String, vision: Boolean = false) =
        """{"id":"$id","name":"$name","kind":"OpenAiCompat","baseUrl":"$url","model":"$model","toolCalls":true,"vision":$vision,""" +
            """"contextTokens":64000,"reasoning":false,"groundingVerified":false,"promptCaching":"implicit"}"""

    /** Old version of the file: only model configuration, Key is stored according to configuration id, and roles are only remembered if they are explicitly specified. */
    private fun writeLegacyFiles(roles: String = """{"Main":"c2","Vision":"c3"}""") {
        File(tmp.root, "providers.json").writeText(
            """{"providers":[""" +
                legacy("c1", "DeepSeek", "https://api.deepseek.com", "deepseek-chat") + "," +
                legacy("c2", "DeepSeek", "https://api.deepseek.com/", "deepseek-reasoner") + "," +
                legacy("c3", "", "http://192.168.1.5:8000/v1", "local", vision = true) +
                """],"roles":$roles}""",
        )
        File(tmp.root, "provider-keys.json").writeText("""{"c1":"${enc("sk-ds")}","c2":"${enc("sk-ds")}","c3":"${enc("sk-local")}"}""")
    }

    @Test
    fun legacyConfigsAreGroupedIntoProvidersOnLoad() {
        writeLegacyFiles()
        val s = store()
        assertEquals(listOf("DeepSeek", "192.168.1.5"), s.providers.value.map { it.name })
        val ds = s.providers.value[0]
        val local = s.providers.value[1]
        assertNull("还没列过模型", ds.models)
        // Key returned to service provider
        assertEquals("sk-ds", s.providerKey(ds.id))
        assertEquals("sk-local", s.providerKey(local.id))
        // Explicitly specified ones remain as usual; unused c1 is deleted.
        assertEquals(RoleChoices(main = "c2", vision = RoleChoice.Model("c3"), light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c2", "c3"), s.list().map { it.id })
        assertEquals(listOf(ds.id, local.id), s.list().map { it.providerId })
        assertEquals("deepseek-reasoner", s.configFor(ModelRole.Main)?.model)
        s.modelFor(ModelRole.Vision)
        assertEquals("c3" to "sk-local", built.last().first.id to built.last().second)
        // The old Key saved according to the configuration ID has been cleared.
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(setOf(ds.id, local.id), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun legacyDefaultsAreWrittenOutAsTheyWorked() {
        // The main model is not specified: the first configuration is used at that time; it cannot view
        // pictures, so it is not used for viewing pictures; it follows the main model lightly
        writeLegacyFiles(roles = "{}")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
        assertEquals(listOf("c1"), s.list().map { it.id })
        assertTrue(File(tmp.root, "providers.json").readText().contains("\"uses\""))
    }

    @Test
    fun legacyMainThatCanSeeKeepsServingVision() {
        writeLegacyFiles(roles = """{"Main":"c3","Light":"c1"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c3", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("c1")), s.roles.value)
        assertEquals("c3", s.configFor(ModelRole.Vision)?.id)
        assertEquals(listOf("c1", "c3"), s.list().map { it.id })
    }

    @Test
    fun legacyRolesPointingToMissingConfigsFallBackToTheOldDefaults() {
        writeLegacyFiles(roles = """{"Main":"gone","Vision":"gone"}""")
        val s = store()
        assertEquals(RoleChoices(main = "c1", vision = RoleChoice.Off, light = RoleChoice.SameAsMain), s.roles.value)
    }

    @Test
    fun migrationRunsOnceAndRepeatsWithoutSideEffects() {
        writeLegacyFiles(roles = """{"Main":"c3"}""")
        val first = store()
        val providers = first.providers.value
        val configs = first.list()
        val roles = first.roles.value
        val configText = File(tmp.root, "providers.json").readText()
        val keyText = File(tmp.root, "provider-keys.json").readText()
        repeat(2) {
            val again = store()
            assertEquals(providers, again.providers.value)
            assertEquals(configs, again.list())
            assertEquals(roles, again.roles.value)
            assertEquals(configText, File(tmp.root, "providers.json").readText())
            assertEquals(keyText, File(tmp.root, "provider-keys.json").readText())
        }
    }

    @Test
    fun interruptedMigrationIsRedoneWithoutLosingKeys() {
        writeLegacyFiles()
        store()
        // The simulation is interrupted midway: the configuration file returns to the old format, and
        // both the old and new copies of the Key file are present.
        val migratedKeys = File(tmp.root, "provider-keys.json").readText()
        writeLegacyFiles()
        val legacyKeys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
            as kotlinx.serialization.json.JsonObject
        val newKeys = kotlinx.serialization.json.Json.parseToJsonElement(migratedKeys) as kotlinx.serialization.json.JsonObject
        File(tmp.root, "provider-keys.json").writeText(kotlinx.serialization.json.JsonObject(legacyKeys + newKeys).toString())
        val s = store()
        assertEquals(2, s.providers.value.size)
        assertEquals(listOf("sk-ds", "sk-local"), listOf("c2", "c3").map { s.apiKey(it) })
        val keys = kotlinx.serialization.json.Json.parseToJsonElement(File(tmp.root, "provider-keys.json").readText())
        assertEquals(s.providers.value.map { it.id }.toSet(), (keys as kotlinx.serialization.json.JsonObject).keys)
    }

    // ---------- Picture positioning verified ----------

    @Test
    fun groundingVerifiedFollowsTheVisionModelAndPersists() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        // Does not move when the model is not viewed
        s.setVisionGroundingVerified(true)
        assertFalse(s.list().single().capabilities.groundingVerified)
        s.use(ModelRole.Vision, p.id, "m2", none)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        assertFalse(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
        assertTrue(store().configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        // When using the main model to look at the picture, the line on the main model was changed.
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        assertFalse(s.configFor(ModelRole.Vision)!!.capabilities.groundingVerified)
        s.setVisionGroundingVerified(true)
        assertTrue(s.configFor(ModelRole.Main)!!.capabilities.groundingVerified)
    }

    @Test
    fun roleChoicesResolveAndValidate() {
        val r = RoleChoices(main = "a", vision = RoleChoice.SameAsMain, light = RoleChoice.Model("b"))
        assertEquals("a", r.resolve(ModelRole.Vision))
        assertEquals("b", r.resolve(ModelRole.Light))
        assertEquals(setOf("a", "b"), r.referenced)
        assertNull(RoleChoices().resolve(ModelRole.Light))
        assertNull(r.with(ModelRole.Light, RoleChoice.Off))
        assertNull(r.with(ModelRole.Main, RoleChoice.SameAsMain))
        assertEquals(RoleChoices(main = null, vision = RoleChoice.SameAsMain, light = RoleChoice.SameAsMain), r.keepOnly(emptySet()))
    }

    // ---------- Interface specified by model ----------

    private val chat = ModelEndpoint(ModelProtocol.ChatCompletions, "https://x.test/openai/v1", "reasoning_content")
    private val messages = ModelEndpoint(ModelProtocol.Messages, "https://x.test/anthropic/v1")
    private val generate = ModelEndpoint(ModelProtocol.GenerateContent, "https://x.test/google/v1beta")
    private val responses = ModelEndpoint(ModelProtocol.Responses, "https://x.test/openai/v1/")

    @Test
    fun perModelEndpointsPersistWithTheList() {
        val s = store()
        val listed = listOf(
            ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("c", endpoint = generate),
            ListedModel("r", endpoint = responses), ListedModel("u", unsupportedApis = listOf("realtime")), ListedModel("plain"),
        )
        s.connect("X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k", listed)
        assertEquals(listed, store().providers.value.single().models)
    }

    @Test
    fun eachModelIsBuiltWithTheEndpointItsListingNamed() = runTest {
        val s = store()
        val p = s.connect(
            "X", ProviderConfig.Kind.OpenAiCompat, "https://x.test/v1", "k",
            listOf(ListedModel("a", endpoint = chat), ListedModel("b", endpoint = messages), ListedModel("plain")),
        )
        s.use(ModelRole.Main, p.id, "b", none)
        s.use(ModelRole.Light, p.id, "plain", none)
        store().modelFor(ModelRole.Main)
        store().modelFor(ModelRole.Light)
        assertEquals(listOf(messages, null), endpoints)
        assertEquals(listOf("k", "k"), built.map { it.second })
        // Listed again, the address has been changed, use the new one next time when building a model
        val moved = messages.copy(baseUrl = "https://y.test/anthropic/v1")
        s.rememberModels(p.id, listOf(ListedModel("b", endpoint = moved)))
        s.modelFor(ModelRole.Main)
        assertEquals(moved, endpoints.last())
    }

    @Test
    fun adapterAndBaseUrlFollowTheProtocolWithoutRepeatingTheVersionPath() {
        val c = cfg("1")
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://example.test/v1", ProviderStore.adapterFor(c, null))
        assertEquals(ProviderConfig.Kind.OpenAiCompat to "https://x.test/openai/v1", ProviderStore.adapterFor(c, chat))
        assertEquals(ProviderConfig.Kind.Anthropic to "https://x.test/anthropic", ProviderStore.adapterFor(c, messages))
        assertEquals(ProviderConfig.Kind.Gemini to "https://x.test/google", ProviderStore.adapterFor(c, generate))
        assertEquals(ProviderConfig.Kind.OpenAiResponses to "https://x.test/openai/v1", ProviderStore.adapterFor(c, responses))
        val anthropicConfig = c.copy(kind = ProviderConfig.Kind.Anthropic, baseUrl = "https://api.anthropic.com")
        assertEquals(ProviderConfig.Kind.Anthropic to "https://api.anthropic.com", ProviderStore.adapterFor(anthropicConfig, null))
    }

    // ---------- Request header ----------

    /** The service provider that declares the session header: the model built in the same conversation will have the same session number, and it will be changed when the conversation is changed; User-Agent will be included in each one. */
    @Test
    fun sessionHeaderIsStableWithinAConversationAndDiffersAcross() {
        val s = store()
        val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(ListedModel("m1")), sessionHeader = "x-test-session")
        s.use(ModelRole.Main, p.id, "m1", none)
        val a = ModelSession.of("conv-a")
        s.modelFor(ModelRole.Main, a); s.modelFor(ModelRole.Light, a); s.modelFor(ModelRole.Main, ModelSession.of("conv-b"))
        val sent = setups.map { it.headers["x-test-session"] }
        assertEquals(listOf(a.id, a.id, ModelSession.of("conv-b").id), sent)
        assertTrue(sent[0] != sent[2])
        assertTrue(setups.all { it.headers["User-Agent"] == "Mina/1.2.3" })
    }

    /** The list points the model to Responses (only the model of this protocol is mentioned under the same service provider): the real Responses adapter still has the client ID and session number. */
    @Test
    fun responsesRoutedModelCarriesTheSessionHeader() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mina/1.2.3")
            val responsesBase = server.url("/v1").toString()
            val listed = ListedModel("r-only", endpoint = ModelEndpoint(ModelProtocol.Responses, responsesBase))
            val p = s.connect("Go", ProviderConfig.Kind.OpenAiCompat, "https://go.test/v1", "k", listOf(listed), sessionHeader = "x-test-session")
            s.use(ModelRole.Main, p.id, "r-only", none)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"))
            val session = ModelSession.of("conv-a")
            s.modelFor(ModelRole.Main, session)!!.complete("s", emptyList(), emptyList())
            val req = server.takeRequest()
            assertEquals("/v1/responses", req.path)
            assertEquals("Mina/1.2.3", req.getHeader("User-Agent"))
            assertEquals(session.id, req.getHeader("x-test-session"))
            assertEquals("Bearer k", req.getHeader("Authorization"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun noSessionHeaderForProvidersThatDoNotDeclareIt() {
        val s = store()
        val p = connected(s)
        s.use(ModelRole.Main, p.id, "m1", none)
        s.modelFor(ModelRole.Main, ModelSession.of("conv-a"))
        assertEquals(mapOf("User-Agent" to "Mina/1.2.3"), setups.single().headers)
    }

    /** The session header is saved with the service provider; if it is saved in the old version (without this item), fill it in according to the default address that matches, and the default address will be brought. */
    @Test
    fun sessionHeaderPersistsAndIsFilledFromThePresetForOldFiles() {
        val goUrl = ProviderPresets.all.first { it.sessionHeader != null }
        val s = store()
        val p = s.connect(goUrl.label, goUrl.kind, goUrl.baseUrl, "k", emptyList())
        assertEquals(goUrl.sessionHeader, p.sessionHeader)
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        val f = File(tmp.root, "providers.json")
        f.writeText(f.readText().replace(",\"sessionHeader\":\"${goUrl.sessionHeader}\"", ""))
        assertFalse(f.readText().contains("sessionHeader"))
        assertEquals(goUrl.sessionHeader, store().provider(p.id)?.sessionHeader)
        assertTrue("written back", f.readText().contains("sessionHeader"))
    }

    /** The trial call is a real adapter: a session header with client identification and declaration, and a trial tool; the tool call is passed only after the tool call is returned, and the server-side error report is brought back as it is. */
    @Test
    fun probeGoesThroughTheRealAdapterWithHeaders() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mina/1.2.3")
            val url = server.url("/v1").toString()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",""" +
                    """"function":{"name":"get_time","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", "x-test-session", "m", null, none))
            val req = server.takeRequest()
            assertEquals("/v1/chat/completions", req.path)
            assertEquals("Mina/1.2.3", req.getHeader("User-Agent"))
            assertTrue(req.getHeader("x-test-session").orEmpty().isNotEmpty())
            assertEquals("Bearer sk", req.getHeader("Authorization"))
            val body = req.body.readUtf8()
            assertTrue(body, body.contains("\"tools\"") && body.contains("get_time"))
            assertFalse("Chat Completions 不给回复上限", body.contains("max_tokens"))

            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(400).setBody("""{"error":{"type":"MissingSessionID"}}"""))
            val e = s.probe(ProviderConfig.Kind.OpenAiCompat, url, "sk", null, "m", null, none) as ProbeResult.Failed
            assertTrue(e.error.message.orEmpty(), e.error.message.orEmpty().contains("MissingSessionID"))
            assertNull(server.takeRequest().getHeader("x-test-session"))

            // Responses does not give an upper limit for responses; if the function call is returned, it
            // will pass.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"get_time\",\"arguments\":\"{}\"}}\n\n" +
                    "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.OpenAiResponses, url, "sk", null, "m", null, none))
            val responses = server.takeRequest()
            assertEquals("/v1/responses", responses.path)
            assertEquals("Mina/1.2.3", responses.getHeader("User-Agent"))
            val responsesBody = responses.body.readUtf8()
            assertFalse(responsesBody.contains("max_output_tokens"))
            assertTrue(responsesBody.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    /** The interface is fluent and the model only responds without calling tools: it is not a failure, but it must be distinguished. */
    @Test
    fun probeThatGetsOnlyTextReportsNoToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mina/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"role":"assistant","content":"It is noon."},"finish_reason":"stop"}]}"""))
            assertEquals(ProbeResult.NoToolCall, s.probe(ProviderConfig.Kind.OpenAiCompat, server.url("/v1").toString(), "sk", null, "m", null, none))
        } finally {
            server.shutdown()
        }
    }

    /** The upper limit of the reply to Messages is left enough for one tool call (16 tokens cannot be accommodated). */
    @Test
    fun probeLeavesRoomForAToolCall() = runTest {
        val server = okhttp3.mockwebserver.MockWebServer().also { it.start() }
        try {
            val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, userAgent = "Mina/1.2.3")
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"content":[{"type":"tool_use","id":"t1","name":"get_time","input":{}}],"stop_reason":"tool_use","usage":{"input_tokens":1,"output_tokens":1}}""",
            ))
            assertEquals(ProbeResult.ToolCalled, s.probe(ProviderConfig.Kind.Anthropic, server.url("/").toString(), "sk", null, "m", null, none))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body, body.contains("\"max_tokens\":1024"))
            assertTrue(body.contains("get_time"))
        } finally {
            server.shutdown()
        }
    }

    // ---------- Dosage ----------

    @Test
    fun everyModelHandedOutIsMeteredUnderTheRoleItWasAskedFor() = runTest {
        val seen = mutableListOf<Pair<UsageSource, Usage?>>()
        val s = ProviderStore(tmp.root, AesGcmKeyCipher { key }, usage = { src, u -> seen += src to u }) { cfg, _, _, _ -> FakeModel(cfg.capabilities) }
        val p = connected(s, name = "甲")
        s.use(ModelRole.Main, p.id, "m1", none)
        s.choose(ModelRole.Vision, RoleChoice.SameAsMain)
        s.use(ModelRole.Light, p.id, "m2", none)
        s.modelFor(ModelRole.Main)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Vision)!!.complete("s", emptyList(), emptyList())
        s.modelFor(ModelRole.Light)!!.complete("s", emptyList(), emptyList())
        assertEquals(
            listOf(
                UsageSource(p.id, "甲", "m1", UsagePurpose.Main),
                UsageSource(p.id, "甲", "m1", UsagePurpose.Vision),
                UsageSource(p.id, "甲", "m2", UsagePurpose.Light),
            ),
            seen.map { it.first },
        )
        assertEquals(Usage(3, 2), seen.first().second)
    }

    private class FakeModel(override val capabilities: Capabilities) : ChatModel {
        override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
            ModelReply(emptyList(), StopReason.EndTurn, Usage(3, 2))
    }
}
