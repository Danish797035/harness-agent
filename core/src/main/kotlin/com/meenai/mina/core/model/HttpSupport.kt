package com.meenai.mina.core.model

import com.meenai.mina.core.net.NetProxy
import com.meenai.mina.core.net.ProxyFailure
import com.meenai.mina.core.trace.TraceCall
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class RawResponse(val status: Int, val retryAfter: String?, val body: String, private val onPacket: ((String) -> Unit)? = null) {
    /** Some chat endpoints send a buffered SSE body even when streaming was not requested. */
    fun replay(events: List<String>, accept: (String) -> Unit) {
        for (event in events) {
            onPacket?.invoke(event)
            accept(event)
        }
    }
}

/** HTTP execution (enqueue + coroutine cancellation) and Retry-After resolution shared by each adapter. */
internal object HttpSupport {
    suspend fun execute(http: OkHttpClient, req: Request): RawResponse =
        observe(req) { attempt -> executeRaw(http, req, attempt) }.also(::requireProxyAccepted)

    /** HTTP 407 will only mean that the agent on the way asks for the account password (or refuses to give it), not a reply from the service provider. */
    private fun requireProxyAccepted(raw: RawResponse) {
        if (raw.status == 407) throw ModelError.Proxy(ProxyFailure.AuthFailed)
    }

    /** Connection failure: If the proxy is gone and the error is in the proxy section, it is said to be a proxy problem. Otherwise, it is still considered a network failure. */
    fun networkError(http: OkHttpClient, req: Request, e: IOException): ModelError =
        NetProxy.failure(http, req.url, e)?.let { ModelError.Proxy(it, e) } ?: ModelError.Network(e)

    private suspend inline fun observe(req: Request, send: (TraceCall.Attempt?) -> RawResponse): RawResponse {
        val wire = req.tag(InferenceRequest::class.java) ?: return send(null)
        val call = currentCoroutineContext()[TraceCall] ?: return send(null)
        val attempt = call.sending(wire) ?: return send(null)
        val response = send(attempt)
        if (response.status !in 200..299) {
            call.finish("error", null)
        } else if (response.body.isNotEmpty()) {
            attempt.packet(response.body)
            return response.copy(onPacket = attempt::packet)
        }
        return response
    }

    private suspend fun executeRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?): RawResponse = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    attempt?.httpStatus(it.code)
                    try {
                        val raw = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                        if (cont.isActive) cont.resume(raw)
                    } catch (e: IOException) {
                        if (cont.isActive) cont.resumeWithException(ModelError.Network(e))
                    }
                }
            }
        })
    }

    /**
     * SSE is delivered on an event basis; canceling a coroutine also cancels the HTTP request. When the
     * stream is read to the end, the caller will judge whether there is a normal ending according to the
     * respective protocol, and the ending calculation will be [ModelError.StreamCut]. A successful
     * response that does not have an event and is not in event stream format (for example, the address
     * points to a web page) counts as [ModelError.BadResponse].
     */
    suspend fun stream(http: OkHttpClient, req: Request, onEvent: (String) -> Unit): RawResponse =
        observe(req) { attempt -> streamRaw(http, req, attempt, onEvent) }.also(::requireProxyAccepted)

    private suspend fun streamRaw(http: OkHttpClient, req: Request, attempt: TraceCall.Attempt?, onEvent: (String) -> Unit): RawResponse = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(networkError(http, req, e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    attempt?.httpStatus(it.code)
                    try {
                        if (it.code !in 200..299) {
                            val error = RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty())
                            if (cont.isActive) cont.resume(error)
                            return
                        }
                        if (it.header("Content-Type")?.contains("application/json", ignoreCase = true) == true) {
                            if (cont.isActive) cont.resume(RawResponse(it.code, it.header("retry-after"), it.body?.string().orEmpty()))
                            return
                        }
                        val source = it.body?.source() ?: throw ModelError.BadResponse("流式响应没有内容")
                        val data = mutableListOf<String>()
                        var events = 0
                        fun dispatch() {
                            if (data.isNotEmpty()) {
                                events++
                                val event = data.joinToString("\n")
                                attempt?.packet(event)
                                onEvent(event)
                                data.clear()
                            }
                        }
                        while (cont.isActive) {
                            val line = source.readUtf8Line() ?: break
                            when {
                                line.isEmpty() -> dispatch()
                                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
                            }
                        }
                        if (cont.isActive) {
                            dispatch()
                            val eventStream = it.header("Content-Type")?.contains("event-stream", ignoreCase = true) == true
                            if (events == 0 && !eventStream) throw ModelError.BadResponse("返回的不是流式内容(Content-Type: ${it.header("Content-Type").orEmpty()})")
                            cont.resume(RawResponse(it.code, it.header("retry-after"), ""))
                        }
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(if (e is IOException) ModelError.Network(e) else e)
                    }
                }
            }
        })
    }

    fun retryAfterMs(header: String?): Long? = header?.trim()?.toDoubleOrNull()?.let { (it * 1000).toLong() }
}
