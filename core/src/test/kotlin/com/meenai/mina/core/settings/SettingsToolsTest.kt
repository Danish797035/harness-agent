package com.meenai.mina.core.settings

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The setting is read-only, and the setting is writable. If the user does not allow it, neither will appear. */
class SettingsToolsTest {
    private val values = mutableMapOf("appearance" to "system")
    private val port = object : SelfConfig {
        override fun options() = listOf(SelfConfig.Option("appearance", "外观", values.getValue("appearance"), listOf("system", "light", "dark")))
        override fun set(key: String, value: String): String? {
            if (key != "appearance" || value !in listOf("system", "light", "dark")) return "no"
            values[key] = value; return null
        }
    }

    @Test fun notOfferedUnlessTheUserAllowsIt() {
        val names = toolDispatcher(FakeScreen()).definitions().map { it.name }
        assertFalse(names.any { it.startsWith("settings_") })
        val allowed = toolDispatcher(FakeScreen(), selfConfig = port).definitions().map { it.name }
        assertTrue(SETTINGS_LIST in allowed && SETTINGS_SET in allowed)
    }

    @Test fun listingIsAReadAndChangingIsNot() = runTest {
        val d = toolDispatcher(FakeScreen(), selfConfig = port)
        val list = d.executeRead(Part.ToolCall("l", SETTINGS_LIST, "{}")).content.toString()
        assertTrue(list, list.contains("\"key\":\"appearance\"") && list.contains("\"dark\""))
        val set = d.executeRead(Part.ToolCall("s", SETTINGS_SET, """{"key":"appearance","value":"dark"}""")).content.toString()
        assertTrue(set, set.contains("not_a_read"))
        assertEquals("system", values["appearance"])
    }
}
