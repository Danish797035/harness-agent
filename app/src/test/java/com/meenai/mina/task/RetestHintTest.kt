package com.meenai.mina.task

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.Provider
import org.junit.Assert.assertEquals
import org.junit.Test

class RetestHintTest {
    private val untested = Provider("p1", "Local", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:8000/v1", models = emptyList())
    private val hint = { name: String -> "retest $name" }

    /** The model failed and the main model failed to pass the test: add a new line after the reason to point to that company's "retest". */
    @Test fun modelFailureWithUntestedMainPointsToRetest() {
        val end = RetestHint.apply(TaskState.Failed("找不到该模型。", FailKind.Model), untested, hint)
        assertEquals(TaskState.Failed("找不到该模型。\nretest Local", FailKind.Model), end)
    }

    /** The main model has been tested, or it is not a problem with the model: return as is. */
    @Test fun otherEndsStayAsTheyAre() {
        val model = TaskState.Failed("找不到该模型。", FailKind.Model)
        assertEquals(model, RetestHint.apply(model, null, hint))
        val guard = TaskState.Failed("已安全停止。", FailKind.Guard)
        assertEquals(guard, RetestHint.apply(guard, untested, hint))
        assertEquals(TaskState.Cancelled, RetestHint.apply(TaskState.Cancelled, untested, hint))
    }
}
