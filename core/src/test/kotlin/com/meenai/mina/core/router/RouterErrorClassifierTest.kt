package com.meenai.mina.core.router

import com.meenai.mina.core.model.ModelError
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RouterErrorClassifierTest {

    @Test
    fun classifiesRateLimitAndServerErrorsAsSwitch() {
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(ModelError.RateLimited(60_000L), 429))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(ModelError.QuotaExceeded(), 402))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(ModelError.Server(500), 500))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(ModelError.Server(502), 502))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(ModelError.Server(503), 503))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(ModelError.StreamCut()))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(FirstTokenTimeoutException()))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(SocketTimeoutException("Read timed out")))
        assertEquals(RouterAction.SWITCH, RouterErrorClassifier.classify(IOException("Connection reset by peer")))
    }

    @Test
    fun classifiesAuthErrorsAsKeyBad() {
        assertEquals(RouterAction.KEY_BAD, RouterErrorClassifier.classify(ModelError.Auth(), 401))
        assertEquals(RouterAction.KEY_BAD, RouterErrorClassifier.classify(ModelError.Auth(), 403))
        assertEquals(RouterAction.KEY_BAD, RouterErrorClassifier.classify(RuntimeException(), 401, """{"error":{"message":"Invalid API key"}}"""))
        assertEquals(RouterAction.KEY_BAD, RouterErrorClassifier.classify(RuntimeException(), 403, "Permission denied for this key"))
    }

    @Test
    fun classifiesContextLengthErrorsAsContext() {
        assertEquals(RouterAction.CONTEXT, RouterErrorClassifier.classify(ModelError.ContextTooLong(), 413))
        assertEquals(RouterAction.CONTEXT, RouterErrorClassifier.classify(RuntimeException(), 400, "maximum context length is 32768 tokens, however your messages resulted in 35000 tokens"))
        assertEquals(RouterAction.CONTEXT, RouterErrorClassifier.classify(RuntimeException(), 400, "context_length_exceeded"))
    }

    @Test
    fun classifiesSchemaAndFormatErrorsAsFixThenSwitch() {
        assertEquals(RouterAction.FIX_THEN_SWITCH, RouterErrorClassifier.classify(
            RuntimeException(), 400, "Invalid schema for function declaration: additionalProperties not allowed"
        ))
        assertEquals(RouterAction.FIX_THEN_SWITCH, RouterErrorClassifier.classify(
            RuntimeException(), 400, "tool parameters validation failed: missing required property"
        ))
        assertEquals(RouterAction.FIX_THEN_SWITCH, RouterErrorClassifier.classify(
            ModelError.BadResponse("Cannot parse tool arguments")
        ))
    }

    @Test
    fun classifiesSafetyAndRefusalAsStop() {
        assertEquals(RouterAction.STOP, RouterErrorClassifier.classify(ModelError.ContentRefused()))
        assertEquals(RouterAction.STOP, RouterErrorClassifier.classify(
            RuntimeException(), 200, "The model refused: content violates safety policy"
        ))
        assertEquals(RouterAction.STOP, RouterErrorClassifier.classify(
            RuntimeException(), 400, "Prompt blocked by safety filters"
        ))
    }
}
