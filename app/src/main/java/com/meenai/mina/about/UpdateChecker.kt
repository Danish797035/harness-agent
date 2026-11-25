package com.meenai.mina.about

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import com.meenai.mina.core.net.NetProxy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** The manual check updates the result this time; the automatic check doesn't change it (and doesn't bother if it fails). */
enum class CheckStatus { Idle, Checking, Done, Failed }

/**
 * Check the status of updates. [latest] is the latest version found (used for updating records), and
 * [update] is the newer one (only if it has a red dot). [manualRounds] adds one each time a manual check
 * is completed, and the page will give prompts such as "already up to date" and "unable to connect"
 * accordingly.
 */
data class UpdateState(
    val latest: ReleaseInfo? = null,
    val update: ReleaseInfo? = null,
    val status: CheckStatus = CheckStatus.Idle,
    val manualRounds: Int = 0,
) {
    val hasUpdate: Boolean get() = update != null
}

/**
 * It will automatically check every time you open the app, and only blocks consecutive ones:
 * [MIN_GAP_MS] (switch back and forth a few times, rotate the screen) that is less than the last time it
 * was automatically checked will not be checked again. If the last time is later than now (the system
 * time has been changed), it is also considered expired.
 */
object UpdateSchedule {
    const val MIN_GAP_MS = 6 * 60 * 60 * 1000L

    fun due(lastAttempt: Long?, now: Long): Boolean = lastAttempt == null || now - lastAttempt >= MIN_GAP_MS || now < lastAttempt
}

/**
 * Check for updates. If you don’t have your own server: just ask for the GitHub release page interface;
 * if you can’t connect, have limited traffic, or can’t read it, it won’t be found this time. The version
 * found and the time of the last automatic check exist [file], and the red dot is still there after the
 * App is reopened; after installing the new version, the current version is no longer lagging behind,
 * and the red dot disappears naturally. Only the version is checked here; user-initiated downloading and
 * installation are handled by [ApkUpdater].
 */
class UpdateChecker(
    private val file: File,
    private val currentVersion: String,
    private val client: OkHttpClient = defaultClient(),
    private val releaseApi: String = HarnessLinks.LATEST_RELEASE_API,
    private val now: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    /** The ABI (Build.SUPPORTED_ABIS) supported by this machine is used to select the appropriate APK in the release; when it is empty, the full architecture package is obtained. */
    private val deviceAbis: List<String> = emptyList(),
    private val currentVersionCode: Long = 0,
) {
    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var lastAttempt: Long? = null
    private var loaded = false

    /** Every time you open the app: it will check in the background once it expires, and if it fails, it will make no sound. */
    fun autoCheckInBackground() {
        scope.launch { autoCheck() }
    }

    /** The user clicked "Check for Updates". */
    fun checkInBackground() {
        scope.launch { check(manual = true) }
    }

    /** Read out the saved results (only read once), the red dots do not need to wait for the Internet connection. */
    suspend fun load() = mutex.withLock { loadLocked() }

    /**
     * Check it once when it is due, and return whether you really checked it this time. Write down the
     * time before starting the check: the second time you come in at the same time (before the check is
     * finished), you will see the time you just recorded and will not check again.
     */
    suspend fun autoCheck(): Boolean {
        val due = mutex.withLock {
            loadLocked()
            UpdateSchedule.due(lastAttempt, now()).also { if (it) lastAttempt = now() }
        }
        if (due) check(manual = false)
        return due
    }

    suspend fun check(manual: Boolean) {
        mutex.withLock {
            loadLocked()
            if (manual) _state.update { it.copy(status = CheckStatus.Checking) }
        }
        val found = withContext(Dispatchers.IO) { fetchLatest() }
        mutex.withLock {
            if (found != null) {
                _state.update { it.withLatest(found) }
            }
            if (manual) _state.update { it.copy(status = if (found != null) CheckStatus.Done else CheckStatus.Failed, manualRounds = it.manualRounds + 1) }
            save()
        }
    }

    /** Latest releases on GitHub; empty if not found. */
    internal fun fetchLatest(): ReleaseInfo? = runCatching { Releases.fromGitHub(get(releaseApi), deviceAbis) }.getOrNull()

    private fun get(url: String): String {
        val req = Request.Builder().url(url)
            .header("User-Agent", "HarnessAgent/$currentVersion")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("empty body")
        }
    }

    private fun UpdateState.withLatest(r: ReleaseInfo?): UpdateState =
        copy(latest = r, update = r?.takeIf { isNewerRelease(it) })

    private fun isNewerRelease(r: ReleaseInfo): Boolean {
        val tagToUse = if (r.tag.isNotEmpty()) r.tag else ("v" + r.version)
        return AppVersion.isUpdateAvailable(tagToUse, currentVersion, currentVersionCode)
    }

    private fun loadLocked() {
        if (loaded) return
        loaded = true
        val o = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return
        lastAttempt = (o["lastAttempt"] as? JsonPrimitive)?.longOrNull
        val latest = (o["latest"] as? JsonObject)?.let(Releases::fromJson)
        _state.update { it.withLatest(latest) }
    }

    private fun save() {
        val o = buildJsonObject {
            lastAttempt?.let { put("lastAttempt", it) }
            _state.value.latest?.let { put("latest", Releases.toJson(it)) }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(o.toString())
                tmp.delete()
            }
        }
    }

    companion object {
        /** Some networks are sometimes fast and sometimes slow when connecting to GitHub: shorten the timeout and switch to a backup source if it fails. Go to Settings › Network Proxy. */
        fun defaultClient(): OkHttpClient = NetProxy.shared.client(OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS))
    }
}
