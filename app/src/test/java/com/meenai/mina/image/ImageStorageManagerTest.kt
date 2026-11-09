package com.meenai.mina.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageStorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun saveToPrivateCacheWritesBytesToPrivateFolder() {
        val root = tempFolder.newFolder("cache")
        val sampleBytes = "IMAGE_JPG_HEADER_AND_BYTES".toByteArray()

        val savedFile = ImageStorageManager.saveToPrivateCache(root, sampleBytes)

        assertTrue(savedFile.exists())
        assertEquals(root.resolve(ImageStorageManager.CACHE_DIR_NAME).absolutePath, savedFile.parentFile?.absolutePath)
        assertTrue(savedFile.readBytes().contentEquals(sampleBytes))
    }

    @Test
    fun cleanOldCachePurgesFilesOlderThanSevenDays() {
        val root = tempFolder.newFolder("cache")
        val nowMs = 1_700_000_000_000L
        val eightDaysAgo = nowMs - (8L * 24 * 60 * 60 * 1000L)
        val oneDayAgo = nowMs - (1L * 24 * 60 * 60 * 1000L)

        val oldFile = ImageStorageManager.saveToPrivateCache(root, "OLD_IMAGE".toByteArray())
        oldFile.setLastModified(eightDaysAgo)

        val freshFile = ImageStorageManager.saveToPrivateCache(root, "FRESH_IMAGE".toByteArray())
        freshFile.setLastModified(oneDayAgo)

        val deletedCount = ImageStorageManager.cleanOldCache(root, nowMs)

        assertEquals("Should delete exactly 1 old file", 1, deletedCount)
        assertFalse("Old file (> 7 days) must be deleted", oldFile.exists())
        assertTrue("Fresh file (< 7 days) must be retained", freshFile.exists())
    }

    @Test
    fun binaTapKeGalleryMeinKuchSaveNahi() {
        val root = tempFolder.newFolder("cache")
        val sampleBytes = "PRIVATE_CACHE_ONLY_DATA".toByteArray()

        val savedFile = ImageStorageManager.saveToPrivateCache(root, sampleBytes)

        // Verifies the file is only created in the app's internal private directory
        assertTrue(savedFile.absolutePath.contains(ImageStorageManager.CACHE_DIR_NAME))
        // File must not be in external storage or gallery paths
        assertFalse(savedFile.absolutePath.contains("/DCIM"))
        assertFalse(savedFile.absolutePath.contains("/Pictures"))
    }
}
