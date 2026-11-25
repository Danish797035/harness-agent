package com.meenai.mina.settings

import com.meenai.mina.core.docs.ReadmeSections
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The service provider's preset sections in README are read one by one according to the source code; here we compare it with the real object. If the source code writing method has changed and the reading method cannot keep up, it will fail. */
class ReadmePresetsTest {
    @Test fun sourceReadingMatchesProviderPresets() {
        val source = File("../${ReadmeSections.PRESETS_SOURCE}").readText()
        assertEquals(
            ProviderPresets.all.map { ReadmeSections.Preset(it.label, it.kind.name, it.baseUrl) },
            ReadmeSections.presets(source),
        )
    }
}
