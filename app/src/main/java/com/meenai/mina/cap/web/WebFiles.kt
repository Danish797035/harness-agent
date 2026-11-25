package com.meenai.mina.cap.web

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import com.meenai.mina.core.workspace.WorkspaceFileGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * A task for files on a web page: downloads fall into the downloads/ of [workspaceDir] (this
 * conversation workspace), and only files in this workspace are uploaded. Uploading does not hand over
 * the workspace file itself: it is copied to the app's private staging area ([UploadStaging]) through
 * the gateway, and the FileProvider only opens the staging area. After the selection box is removed, the
 * copy will remain [keepMs] (The page will be read only when the form is submitted), which will be
 * deleted later or at the end of the task; the ones not taken away will be deleted immediately.
 */
class WebFiles(
    private val context: Context,
    val workspaceDir: File,
    val downloads: WebDownloads,
    gate: WorkspaceFileGate,
    private val scope: CoroutineScope,
    private val keepMs: Long = 10 * 60_000L,
) {
    private val staging = UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate)
    private val live = mutableMapOf<UploadStaging.Staged, Job?>()

    sealed interface Upload {
        class Ok(val uris: List<Uri>, val names: List<String>, val staged: UploadStaging.Staged) : Upload
        /** [error] is file_not_found, path_outside_workspace, linked_file, or file_too_large. */
        class Refused(val error: String, val path: String) : Upload
    }

    fun prepareUpload(paths: List<String>): Upload {
        val staged = when (val r = staging.stage(workspaceDir, paths)) {
            is UploadStaging.Result.Refused -> return Upload.Refused(r.error, r.path)
            is UploadStaging.Result.Ok -> r.staged
        }
        val uris = try {
            staged.files.map { FileProvider.getUriForFile(context, "${context.packageName}.webfiles", it) }
        } catch (e: IllegalArgumentException) {
            staging.delete(staged)
            return Upload.Refused("file_not_found", paths.first())
        }
        synchronized(live) { live[staged] = null }
        return Upload.Ok(uris, staged.files.map { it.name }, staged)
    }

    /** The upload ends: if the selection box ([consumed]) is removed, it will be deleted after [keepMs]. If it is not removed, it will be deleted immediately. */
    fun release(staged: UploadStaging.Staged, consumed: Boolean) {
        if (!consumed) return drop(staged)
        val job = scope.launch {
            delay(keepMs)
            drop(staged)
        }
        synchronized(live) { if (staged in live) live[staged] = job else job.cancel() }
    }

    private fun drop(staged: UploadStaging.Staged) {
        synchronized(live) { live.remove(staged) }?.cancel()
        staging.delete(staged)
    }

    /** Mission ends: All remaining copies will be deleted. */
    fun close() {
        val all = synchronized(live) { live.keys.toList() }
        all.forEach(::drop)
    }

    companion object {
        /** Clear the copy left by the last process when starting. */
        fun clearStale(context: Context, gate: WorkspaceFileGate) {
            UploadStaging(File(context.cacheDir, UploadStaging.DIR), gate).clear()
        }
    }
}
