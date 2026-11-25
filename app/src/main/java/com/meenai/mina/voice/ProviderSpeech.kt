package com.meenai.mina.voice

import com.meenai.mina.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The minimum interface of the recorder: [PcmRecorder] Implement it and replace the test with a fake one. */
interface Recorder {
    val level: StateFlow<Float>
    fun start(): Boolean
    fun stop(): ShortArray
    fun cancel()
}

/**
 * The service provider’s approach: record when pressed, and only send [SpeechEvent.Level]; release
 * [SpeechEvent.Converting], give the entire recording to [transcribe] (WAV in, text out, [ModelError] on
 * failure), and give Final or Failed (Provider). Start recording during construction; if the recording
 * is insufficient, [MIN_MS], if you can’t hear it clearly, don’t call the service provider. The maximum
 * transcribing method is [timeoutMs] (default [TRANSCRIBE_TIMEOUT_MS]). When the network is unavailable,
 * it will be Failed (Network) after timeout, and the interface will not keep spinning.
 */
class ProviderSpeech(
    private val recorder: Recorder,
    private val transcribe: suspend (wav: ByteArray) -> String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val timeoutMs: Long = TRANSCRIBE_TIMEOUT_MS,
) : SpeechSession {
    private val out = SessionEvents()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = false

    private var levels: Job? = null
    private var work: Job? = null
    private var released = false

    init {
        if (!recorder.start()) {
            out.finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        } else {
            levels = scope.launch { recorder.level.collect { out.send(SpeechEvent.Level(it)) } }
        }
    }

    @Synchronized
    override fun release() {
        if (released || out.finished || out.cancelled) return
        released = true
        levels?.cancel()
        // stop will wait for the read loop to exit (briefly blocked) and put it on the background
        // thread. Don't block the main thread.
        work = scope.launch {
            val pcm = recorder.stop()
            if (pcm.size < Wav.RATE * MIN_MS / 1000) {
                out.finish(SpeechEvent.Final(""))
                return@launch
            }
            out.send(SpeechEvent.Converting)
            val wav = Wav.encode(pcm)
            // The connection never returns (the client only has a long read timeout and no overall
            // timeout): it won't wait until the point is reached. Errors are caught in the timeout block
            // and handed over as they are (does not cross the coroutine boundary and will not be
            // replaced by a copy)
            val result = withTimeoutOrNull(timeoutMs) {
                try {
                    SpeechEvent.Final(transcribe(wav))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ModelError) {
                    SpeechEvent.Failed(SpeechFailure.Provider(e))
                } catch (e: Exception) {
                    SpeechEvent.Failed(SpeechFailure.Provider(ModelError.BadResponse(e.message.orEmpty(), e)))
                }
            } ?: SpeechEvent.Failed(SpeechFailure.Network)
            out.finish(result)
        }
    }

    @Synchronized
    override fun cancel() {
        if (!out.cancel()) return
        levels?.cancel()
        work?.cancel()
        recorder.cancel()
    }

    companion object {
        /** Recordings shorter than this are most likely caused by accidental contact, and are simply treated as if they were not heard clearly. */
        const val MIN_MS = 500

        /** The upper limit of waiting for the service provider to transcribe after releasing it; a recording of up to 60 seconds will normally be transcribed within a few seconds. */
        const val TRANSCRIBE_TIMEOUT_MS = 30_000L
    }
}
