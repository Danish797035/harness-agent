package com.meenai.mina.image

import com.meenai.mina.core.image.ImageRole
import com.meenai.mina.settings.FeatureFlagsCapsuleTest.MemoryPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSettingsTest {

    @Test
    fun defaultSettingsAreAutoAndPaidOff() {
        val prefs = MemoryPrefs()
        val settings = ImageSettings(prefs)

        // Paid switch is default OFF
        assertFalse(settings.isPaidAllowed())
        assertFalse(settings.allowPaid.value)

        // Both roles default to Auto
        assertTrue(settings.isAuto(ImageRole.Image))
        assertTrue(settings.isAuto(ImageRole.ImageEdit))
        assertNull(settings.getPinnedModel(ImageRole.Image))
        assertNull(settings.getPinnedModel(ImageRole.ImageEdit))

        // Today's active models default to respective top ModelScope models
        assertEquals("Qwen/Qwen-Image-2.1", settings.todayModel(ImageRole.Image))
        assertEquals("Qwen/Qwen-Image-Edit", settings.todayModel(ImageRole.ImageEdit))
    }

    @Test
    fun pinningModelUpdatesTodayModelAndLeavesAuto() {
        val prefs = MemoryPrefs()
        val settings = ImageSettings(prefs)

        settings.setPinnedModel(ImageRole.Image, "Qwen/Qwen-Image")
        assertFalse(settings.isAuto(ImageRole.Image))
        assertEquals("Qwen/Qwen-Image", settings.getPinnedModel(ImageRole.Image))
        assertEquals("Qwen/Qwen-Image", settings.todayModel(ImageRole.Image))

        // Reset back to Auto
        settings.setPinnedModel(ImageRole.Image, null)
        assertTrue(settings.isAuto(ImageRole.Image))
        assertNull(settings.getPinnedModel(ImageRole.Image))
        assertEquals("Qwen/Qwen-Image-2.1", settings.todayModel(ImageRole.Image))
    }

    @Test
    fun allowPaidSwitchTogglesAndPersistsAcrossInstances() {
        val prefs = MemoryPrefs()
        val settings1 = ImageSettings(prefs)

        settings1.setAllowPaid(true)
        assertTrue(settings1.isPaidAllowed())
        assertTrue(settings1.allowPaid.value)

        // New instance with same prefs must read persisted values
        val settings2 = ImageSettings(prefs)
        assertTrue(settings2.isPaidAllowed())
        assertTrue(settings2.allowPaid.value)

        // Switch back to OFF
        settings2.setAllowPaid(false)
        assertFalse(settings2.isPaidAllowed())
        assertFalse(settings2.allowPaid.value)

        val settings3 = ImageSettings(prefs)
        assertFalse(settings3.isPaidAllowed())
    }

    @Test
    fun pinnedModelPersistsAcrossInstances() {
        val prefs = MemoryPrefs()
        val settings1 = ImageSettings(prefs)

        settings1.setPinnedModel(ImageRole.Image, "black-forest-labs/flux-1-schnell")
        settings1.setPinnedModel(ImageRole.ImageEdit, "Qwen/Qwen-Image-Edit")

        val settings2 = ImageSettings(prefs)
        assertEquals("black-forest-labs/flux-1-schnell", settings2.getPinnedModel(ImageRole.Image))
        assertEquals("Qwen/Qwen-Image-Edit", settings2.getPinnedModel(ImageRole.ImageEdit))
        assertEquals("black-forest-labs/flux-1-schnell", settings2.todayModel(ImageRole.Image))
        assertEquals("Qwen/Qwen-Image-Edit", settings2.todayModel(ImageRole.ImageEdit))
    }
}
