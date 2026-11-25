package com.meenai.mina.core.model

import okhttp3.Request
import java.util.UUID

/**
 * The external session number of a conversation: Each request of the same conversation (main model,
 * picture viewing, lightweight, title creation, verification, intention judgment, explanation after
 * stopping) uses the same one. The service provider routes the request to one place accordingly and
 * reuses the prompt word cache. Deduced from the conversation id (UUID v3, irreversible), the
 * conversation id itself is not sent out. Requests that do not belong to any conversation (connection
 * tests, column models) use a new number of [fresh] each time.
 */
@JvmInline
value class ModelSession(val id: String) {
    companion object {
        fun of(conversationId: String): ModelSession =
            ModelSession(UUID.nameUUIDFromBytes("mina-conversation:$conversationId".toByteArray()).toString())

        fun fresh(): ModelSession = ModelSession(UUID.randomUUID().toString())
    }
}

/** Each request sent to the model service carries a header; the Key header is added by each adapter. */
object ClientHeaders {
    /**
     * [userAgent] indicates which version of Mina it is (given by App, core does not write the version
     * number); [sessionHeader] is the first name declared by the service provider to put the session
     * number. If there is no declaration (null or empty), the session number will not be issued.
     */
    fun of(userAgent: String, sessionHeader: String?, session: ModelSession): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        if (!sessionHeader.isNullOrBlank()) put(sessionHeader.trim(), session.id)
    }
}

internal fun Request.Builder.withHeaders(headers: Map<String, String>): Request.Builder =
    apply { headers.forEach { (k, v) -> header(k, v) } }
