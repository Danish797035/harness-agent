package com.meenai.mina.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Record 16k mono 16-bit PCM. [level] (0..1, normalized by RMS per block) is updated every about 50ms
 * after start; stop returns all PCM so far; [maxMs] automatically stops when the recording is full, and
 * then stops still returns the recorded content. The recording permission is checked first by the
 * caller. If the microphone cannot be obtained here, false will be returned. stop will block briefly
 * (waiting for the read loop to exit), so don't call it on the main thread.
 */
class PcmRecorder(private val maxMs: Int = 60_000, private val streaming: Boolean = false) : StreamingRecorder {
    // At most five seconds while a connection is opening. Never silently drop a sample.
    private val audio = Channel<ShortArray>(100)
    override val chunks: ReceiveChannel<ShortArray> = audio
    private val _level = MutableStateFlow(0f)
    override val level: StateFlow<Float> = _level

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var record: AudioRecord? = null
    private var job: Job? = null
    private var pcm = ShortArray(0)
    private var size = 0

    @SuppressLint("MissingPermission") // Permissions are checked by the caller before start
    override fun start(): Boolean {
        synchronized(lock) {
            if (record != null) return false
            val minBuf = AudioRecord.getMinBufferSize(Wav.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) return false
            val chunk = Wav.RATE / 20 // 50ms per piece
            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC, Wav.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, chunk * 2 * 4),
                )
            } catch (_: SecurityException) {
                return false
            } catch (_: IllegalArgumentException) {
                return false
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                return false
            }
            try {
                rec.startRecording()
            } catch (_: IllegalStateException) {
                rec.release()
                return false
            }
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                rec.release()
                return false
            }
            pcm = ShortArray(Wav.RATE * maxMs / 1000)
            size = 0
            _level.value = 0f
            record = rec
            job = scope.launch { readLoop(rec, chunk) }
            return true
        }
    }

    private fun readLoop(rec: AudioRecord, chunk: Int) {
        val buf = ShortArray(chunk)
        while (scope.isActive) {
            val n = rec.read(buf, 0, chunk)
            if (n <= 0) {
                synchronized(lock) {
                    if (streaming && record === rec) audio.close(IOException("Audio capture failed"))
                }
                break // Read failed or has been stopped
            }
            var sum = 0.0
            for (i in 0 until n) sum += buf[i].toDouble() * buf[i]
            val lvl = (sqrt(sum / n) / 8000.0).coerceIn(0.0, 1.0).toFloat()
            val full = synchronized(lock) {
                if (record !== rec) return // Has been stopped/cancel
                // In the lock, write it after confirming that it has not been stopped, so as to avoid
                // cancel being covered by the volume of this piece as soon as it returns to zero.
                _level.value = lvl
                val take = minOf(n, pcm.size - size)
                System.arraycopy(buf, 0, pcm, size, take)
                size += take
                if (streaming && audio.trySend(buf.copyOf(take)).isFailure) {
                    audio.close(IOException("Audio stream buffer exceeded"))
                    return@synchronized true
                }
                size >= pcm.size
            }
            if (full) break
        }
        // Automatically stop when the recording is full: release the microphone and leave the recorded
        // content for subsequent stops.
        synchronized(lock) {
            if (record === rec) {
                release(rec)
                record = null
                _level.value = 0f // No more recording. Don’t stop the waveform at the last volume.
            }
        }
        if (streaming) audio.close()
    }

    /** Stop and return to all PCMs recorded so far; it is safe even if you are not recording (including automatically stopped). */
    override fun stop(): ShortArray {
        val (rec, j) = synchronized(lock) {
            val r = record
            record = null
            if (r != null) release(r)
            r to job.also { job = null }
        }
        // Wait for the read loop to exit to ensure that the PCM obtained later will not change.
        if (rec != null) runBlocking { j?.join() }
        if (streaming) audio.close()
        return synchronized(lock) {
            val out = pcm.copyOf(size)
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            out
        }
    }

    /** Abandon this recording and discard the recorded content; it can be called repeatedly. */
    override fun cancel() {
        synchronized(lock) {
            record?.let { release(it) }
            record = null
            job?.cancel()
            job = null
            pcm = ShortArray(0)
            size = 0
            _level.value = 0f
            audio.cancel()
        }
    }

    private fun release(rec: AudioRecord) {
        try { rec.stop() } catch (_: IllegalStateException) {}
        rec.release()
    }
}
