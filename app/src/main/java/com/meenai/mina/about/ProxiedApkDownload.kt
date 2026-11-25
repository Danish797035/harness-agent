package com.meenai.mina.about

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Update download when a network proxy is set up: The system download service does not use the App's own
 * proxy. Instead, Mina uses the proxy's [http] in the process. First write to `<target>.part`, then download
 * it completely and then rename it to the target file, so "the target file is here" means it is
 * downloaded. There is only one copy in the process (only one is downloaded at the same time); if the
 * process is killed and the download is not completed, it will be considered a failure, and the user can
 * try again.
 */
class ProxiedApkDownload(
    private val http: OkHttpClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    @Volatile private var job: Job? = null
    @Volatile private var call: Call? = null
    @Volatile private var target: File? = null
    @Volatile private var bytes = 0L
    @Volatile private var total = -1L

    fun start(url: String, file: File) {
        cancel()
        target = file
        bytes = 0
        total = -1
        job = scope.launch {
            runCatching { fetch(http, url, file, onCall = { call = it }) { done, size -> bytes = done; total = size } }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    /** [file] The status of this copy: is being downloaded, has been downloaded, or failed (not being downloaded, and there is no complete file). */
    fun state(file: File): ApkDownloadState = when {
        job?.isActive == true && target == file -> ApkDownloadState(DownloadPhase.Downloading, bytes, total)
        file.isFile -> ApkDownloadState(DownloadPhase.Ready, file.length(), file.length())
        else -> ApkDownloadState(DownloadPhase.Failed)
    }

    fun cancel() {
        job?.cancel()
        // Cut off the connection together and wait until you finish reading this section.
        call?.cancel()
        job = null
        call = null
        target?.let { File(it.path + PART).delete() }
    }

    companion object {
        private const val PART = ".part"

        /** Download [url] to [file]: report the progress while downloading (downloaded, total, not sure the total is -1); if it is not 2xx, or the download is not complete, an error will be thrown, and the target file will not be left. */
        suspend fun fetch(http: OkHttpClient, url: String, file: File, onCall: (Call) -> Unit = {}, progress: (Long, Long) -> Unit) {
            val part = File(file.path + PART)
            try {
                val call = http.newCall(Request.Builder().url(url).build())
                onCall(call)
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("empty body")
                    val size = body.contentLength()
                    var done = 0L
                    progress(0, size)
                    body.byteStream().use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                coroutineContext.ensureActive()
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                progress(done, size)
                            }
                        }
                    }
                    if (size >= 0 && done != size) throw IOException("incomplete download")
                }
                if (!part.renameTo(file)) throw IOException("cannot move download")
            } finally {
                part.delete()
            }
        }
    }
}
