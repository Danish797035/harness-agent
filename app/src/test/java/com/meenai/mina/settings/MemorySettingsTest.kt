package com.meenai.mina.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemorySettingsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun learnSkillsOnByDefault() {
        assertTrue(MemorySettings(File(tmp.root, "memory.json")).switches.value.learnSkills)
    }

    @Test fun olderSettingsUpgradeOnceThenUserChoiceSticks() {
        val file = File(tmp.root, "memory.json")
        // Saved from the old version: it was turned off by default at that time
        file.writeText("""{"useMemory":true,"autoRemember":false,"learnSkills":false}""")
        val settings = MemorySettings(file)
        assertTrue(settings.switches.value.learnSkills)
        assertFalse(settings.switches.value.autoRemember)
        settings.update { it.copy(learnSkills = false) }
        assertFalse(MemorySettings(file).switches.value.learnSkills)
    }
}
