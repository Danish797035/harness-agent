package com.meenai.mina.about

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import com.meenai.mina.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class DownloadPhase { Idle, Downloading, Paused, Ready, Failed }

data class ApkDownloadState(
    val phase: DownloadPhase = DownloadPhase.Idle,
    val bytes: Long = 0,
    val total: Long = -1,
) {
    val percent: Int? get() = if (total > 0) ((bytes.toDouble() / total) * 100).toInt().coerceIn(0, 100) else null
}

/**
 * The system is responsible for background downloading; only the task number is saved, and it can still
 * be queried, canceled or installed after the process is restarted. The method is called on the IO
 * thread. The files are placed in the external exclusive directory of this application (other apps
 * cannot read or write), and only one is left at a time; they are cleared after changing versions,
 * deleting, and installing. When a network proxy is set up (Settings › Network Proxy), the system
 * download service does not use it. Instead, Mina downloads it through the proxy in the process
 * ([ProxiedApkDownload]).
 */
class ApkUpdater(private val context: Context, private val net: NetProxy = NetProxy.shared) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("apk-update", Context.MODE_PRIVATE)
    private val directory: File get() = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
        ?: error("external files unavailable"), "updates")
    private val file: File? get() = prefs.getString("file", null)?.let { File(directory, it) }
    /** This one was downloaded by Mina herself through an agent (not a system download service). */
    private val viaApp: Boolean get() = prefs.getBoolean("viaApp", false)

    fun state(release: ReleaseInfo): ApkDownloadState =
        if (prefs.getString("url", null) != release.apkUrl) ApkDownloadState() else recorded()

    /** Note the current status of the download task; if the task cannot be found in the system, it is considered a failure. */
    private fun recorded(): ApkDownloadState {
        if (viaApp) return file?.let(proxied::state) ?: ApkDownloadState(DownloadPhase.Failed)
        val id = prefs.getLong("id", -1)
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (cursor.moveToFirst()) {
                fun number(column: String) = cursor.getLong(cursor.getColumnIndexOrThrow(column))
                val phase = when (number(DownloadManager.COLUMN_STATUS).toInt()) {
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING -> DownloadPhase.Downloading
                    DownloadManager.STATUS_PAUSED -> DownloadPhase.Paused
                    DownloadManager.STATUS_SUCCESSFUL -> if (file?.isFile == true) DownloadPhase.Ready else DownloadPhase.Failed
                    else -> DownloadPhase.Failed
                }
                return ApkDownloadState(phase, number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR), number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            }
        }
        return ApkDownloadState(DownloadPhase.Failed)
    }

    fun download(release: ReleaseInfo) {
        val url = requireNotNull(release.apkUrl)
        ApkIntegrity.requireOfficialUrl(url)
        if (prefs.getString("url", null) == url && state(release).phase in
            listOf(DownloadPhase.Downloading, DownloadPhase.Paused, DownloadPhase.Ready)) return
        cancel()
        val folder = directory
        check(folder.mkdirs() || folder.isDirectory)
        // The old files left behind when the process was killed and the records were lost are also
        // cleared, leaving only this time in the directory.
        folder.listFiles()?.forEach { it.delete() }
        // The background cleanup of remove may be later than the next download, and the file name of the
        // old task cannot be reused.
        val target = File(folder, "update-${UUID.randomUUID()}.apk")
        if (net.proxyFor(Uri.parse(url).host.orEmpty()) != null) {
            check(prefs.edit().putBoolean("viaApp", true).putString("url", url).putString("file", target.name).commit()) { "could not save download" }
            proxied.start(url, target)
            return
        }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("Harness Agent ${release.version}")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationUri(Uri.fromFile(target))
        val id = manager.enqueue(request)
        if (!prefs.edit().putBoolean("viaApp", false).remove("handled_id").putLong("id", id).putString("url", url).putString("file", target.name).commit()) {
            manager.remove(id)
            error("could not save download")
        }
    }

    fun cancel() {
        if (viaApp) proxied.cancel()
        val id = prefs.getLong("id", -1)
        if (id != -1L) manager.remove(id)
        val target = file
        check(target == null || !target.exists() || target.delete())
        check(prefs.edit().clear().commit())
    }

    /** After it has been installed (or a newer version is installed), the downloaded installation package is useless: clear it at startup. Downloading, paused, or failed downloads will be handled by the About page. */
    fun dropInstalled() {
        val target = file ?: return
        if (recorded().phase != DownloadPhase.Ready) return
        val archive = archiveInfo(target, 0)
        if (archive != null && archive.longVersionCode > installedInfo(0).longVersionCode) return
        cancel()
    }

    /**
     * Only accept complete attachments of this application, higher versions, and the same signature; if
     * they fail, delete the file and throw [ApkRejected]. The system installer will then re-verify the
     * signature and ask the user for confirmation.
     */
    fun installIntent(release: ReleaseInfo): Intent {
        check(state(release).phase == DownloadPhase.Ready)
        ApkIntegrity.requireOfficialUrl(requireNotNull(release.apkUrl))
        val target = requireNotNull(file)
        try {
            ApkIntegrity.verify(target, release.apkSize, release.apkDigest)
            // Signing flags ke saath kuch devices (v2-only APK par) PackageInfo hi null dete hain,
            // jis se install tap download ko hamesha reject karta tha. Bina signing flags padh lo:
            // signature ki pakki jaanch phir bhi system installer khud karta hai.
            val archive = archiveInfo(target, SIGNING_FLAGS) ?: archiveInfo(target, 0)
                ?: throw ApkRejected(ApkRejected.Reason.Corrupt)
            val installed = installedInfo(SIGNING_FLAGS)
            if (archive.packageName != context.packageName || archive.longVersionCode <= installed.longVersionCode) {
                throw ApkRejected(ApkRejected.Reason.NotNewer)
            }
            // Some devices can't read v2/v3-only signatures from an archive at all (empty set here).
            // The system installer still enforces the same-signature rule on every install, so only
            // pre-check when the archive's signer was actually readable; otherwise proceed.
            val archiveSigners = archive.signers()
            if (archiveSigners.isNotEmpty()) {
                ApkIntegrity.requireSameSigner(installed.signers(), archiveSigners, archive.signerHistory())
            }
        } catch (e: ApkRejected) {
            // Sirf corrupt file hatao. Signer/NotNewer par download rakho: file theek hai,
            // reject hone se pura download delete karke download loop shuru nahi hona chahiye.
            if (e.reason == ApkRejected.Reason.Corrupt) cancel()
            throw e
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updatefiles", target)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun openInstaller(release: ReleaseInfo) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } else {
            val intent = installIntent(release).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(target: File, flags: Int): PackageInfo? =
        context.packageManager.getPackageArchiveInfo(target.path, flags)

    @Suppress("DEPRECATION")
    private fun installedInfo(flags: Int): PackageInfo = context.packageManager.getPackageInfo(context.packageName, flags)

    /** Current signing certificate; some systems do not provide signingInfo when reading the installation package, and return the old field signatures. */
    @Suppress("DEPRECATION")
    private fun PackageInfo.signers(): Set<String> =
        (signingInfo?.apkContentsSigners ?: signatures).orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()

    /** Rotated signed packages carry certificate history; multiple signers have no history. */
    private fun PackageInfo.signerHistory(): Set<String> {
        val info = signingInfo ?: return emptySet()
        if (info.hasMultipleSigners()) return emptySet()
        return info.signingCertificateHistory.orEmpty().map { ApkIntegrity.sha256(it.toByteArray()) }.toSet()
    }

    private companion object {
        /** There is only one share in the process placed by the agent. */
        val proxied by lazy {
            ProxiedApkDownload(NetProxy.shared.client(OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS)))
        }

        @Suppress("DEPRECATION")
        const val SIGNING_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
    }
}
