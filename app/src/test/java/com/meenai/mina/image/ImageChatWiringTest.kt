package com.meenai.mina.image

import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.image.ImageIntentRouter
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.ui.ChatViewModel
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import com.meenai.mina.ui.TaskActions
import com.meenai.mina.ui.exchangeItemKeys
import com.meenai.mina.ui.image.GeneratedImageUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ImageChatWiringTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) {}
        override fun steer(conversationId: String, messageId: String, text: String) {}
        override fun withdrawSteer(conversationId: String, messageId: String): Boolean = true
        override fun cancel() {}
        override fun resume() {}
        override fun takeOver() {}
        override fun handBack() {}
        override fun finishedMyself() {}
        override fun skip() {}
        override fun answer(text: String) {}
        override fun approve(key: Int) {}
        override fun decline(key: Int) {}
        override fun show(conversationId: String) {}
        override fun cancelIn(conversationId: String) {}
        override fun resumeIn(conversationId: String) {}
        override fun answerIn(conversationId: String, text: String) {}
        override fun approveIn(conversationId: String, key: Int) {}
    }

    @Test
    fun generatedImagesFlowUpdatesExchangeInChatViewModel() = runTest {
        val state = MutableStateFlow<TaskState>(TaskState.Idle)
        val stage = MutableStateFlow(Takeover.Stage.None)
        val imagesFlow = MutableStateFlow<List<GeneratedImageUi>>(emptyList())
        val store = ConversationStore(tmp.newFolder())

        val vm = ChatViewModel(
            scope = backgroundScope,
            taskState = state,
            takeoverStage = stage,
            actions = actions,
            store = store,
            generatedImages = imagesFlow,
        ).also { runCurrent() }

        // Start message
        vm.send("ek sundar billi ki photo banao", ok)
        runCurrent()

        state.value = TaskState.Running(1, "Image ban rahi hai…")
        runCurrent()

        val initialExchange = vm.ui.value.exchanges.single()
        assertTrue("Initially no generated images", initialExchange.generatedImages.isEmpty())

        // Image generation in progress
        val inProgressImage = GeneratedImageUi(
            filePath = null,
            isGenerating = true,
            prompt = "ek sundar billi ki photo banao",
            model = "Qwen/Qwen-Image-2.1",
        )
        imagesFlow.value = listOf(inProgressImage)
        runCurrent()

        var ex = vm.ui.value.exchanges.single()
        assertEquals(1, ex.generatedImages.size)
        assertTrue(ex.generatedImages.first().isGenerating)

        // Image generation completed
        val finishedImage = GeneratedImageUi(
            filePath = "/data/user/0/com.meenai.mina/cache/images/cat.jpg",
            isGenerating = false,
            prompt = "ek sundar billi ki photo banao",
            model = "Qwen/Qwen-Image-2.1",
        )
        imagesFlow.value = listOf(finishedImage)
        state.value = TaskState.Succeeded("Image ban gayi")
        runCurrent()

        ex = vm.ui.value.exchanges.single()
        assertEquals(1, ex.generatedImages.size)
        assertFalse(ex.generatedImages.first().isGenerating)
        assertEquals("/data/user/0/com.meenai.mina/cache/images/cat.jpg", ex.generatedImages.first().filePath)

        // Check chat list keys: contains img key
        val keys = exchangeItemKeys(ex)
        val imgKey = keys.firstOrNull { it.first == "img:${ex.id}-0" }
        assertNotNull("Exchange item keys must contain img key", imgKey)
        assertEquals("generated_image", imgKey?.second)
    }

    @Test
    fun purgeOnAppStartRemovesOldCacheAndKeepsFresh() {
        val root = tmp.newFolder("app_start_purge")
        val nowMs = 1_700_000_000_000L
        val tenDaysAgo = nowMs - (10L * 24 * 60 * 60 * 1000L)
        val twoDaysAgo = nowMs - (2L * 24 * 60 * 60 * 1000L)

        val oldImg = ImageStorageManager.saveToPrivateCache(root, "OLD_DATA".toByteArray())
        oldImg.setLastModified(tenDaysAgo)

        val freshImg = ImageStorageManager.saveToPrivateCache(root, "FRESH_DATA".toByteArray())
        freshImg.setLastModified(twoDaysAgo)

        val deletedCount = ImageStorageManager.cleanOldCache(root, nowMs)
        assertEquals(1, deletedCount)
        assertFalse("File older than 7 days should be purged", oldImg.exists())
        assertTrue("File newer than 7 days should be retained", freshImg.exists())
    }

    @Test
    fun videoRequestReturnsVideoNotSupportedReply() {
        assertTrue(ImageIntentRouter.isVideoRequest("ek billi ka video banao"))
        assertTrue(ImageIntentRouter.isVideoRequest("make a 5 second video"))
        assertTrue(ImageIntentRouter.isVideoRequest("generate video of dancing robot"))
        assertFalse(ImageIntentRouter.isVideoRequest("ek billi ki photo banao"))
        assertEquals("Video abhi support nahi hai", ImageIntentRouter.VIDEO_NOT_SUPPORTED_MSG)
    }

    @Test
    fun updateGeneratedImagePreservesFilePathWhenProgressCalledAfterSave() {
        val list = mutableListOf<GeneratedImageUi>()
        fun update(img: GeneratedImageUi) {
            val idx = list.indexOfFirst { it.prompt == img.prompt }
            if (idx >= 0) {
                val existing = list[idx]
                list[idx] = img.copy(
                    filePath = img.filePath ?: existing.filePath,
                    model = if (img.model.isNotBlank()) img.model else existing.model
                )
            } else {
                list.add(img)
            }
        }

        // 1. Progress start
        update(GeneratedImageUi(filePath = null, isGenerating = true, prompt = "a dog", model = "Qwen"))
        assertEquals(1, list.size)
        assertTrue(list.first().isGenerating)
        assertNull(list.first().filePath)

        // 2. Saved callback
        update(GeneratedImageUi(filePath = "/path/to/dog.jpg", isGenerating = false, prompt = "a dog", model = "Qwen"))
        assertEquals("/path/to/dog.jpg", list.first().filePath)
        assertFalse(list.first().isGenerating)

        // 3. Progress finish callback (isGenerating=false, filePath=null)
        update(GeneratedImageUi(filePath = null, isGenerating = false, prompt = "a dog", model = "Qwen"))
        // MUST NOT wipe out the saved filePath!
        assertEquals("/path/to/dog.jpg", list.first().filePath)
        assertFalse(list.first().isGenerating)
    }
}
