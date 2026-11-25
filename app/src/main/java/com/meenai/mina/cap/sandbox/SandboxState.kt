package com.meenai.mina.cap.sandbox

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.content.Context
import android.os.Build
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

enum class InstallStep { Check, Extract, Configure, Skills, Done }

/**
 * Sandbox status. The installation progress is recorded in `files/sandbox/state.json` (the entire
 * directory will be deleted when resetting); the networking switch is a user setting, recorded in
 * `files/sandbox-settings.json`, and will be retained after reset.
 *
 * It is considered ready only if "Complete to Done" is recorded and there is /bin/sh in the root file
 * system; if it is halfway through the installation (including when the process is killed during
 * installation), it will always be Broken. Next time, continue from the recorded step.
 */
class SandboxState internal constructor(
    private val dirs: SandboxDirs,
    private val settingsFile: File,
    private val abis: () -> List<String>,
) {
    constructor(context: Context) : this(
        SandboxDirs(context), File(context.filesDir, "sandbox-settings.json"), { Build.SUPPORTED_ABIS.toList() },
    )

    private val recordFile get() = File(dirs.root, "state.json")
    private var installing: SandboxStatus.Installing? = null
    private val _status = MutableStateFlow(read())
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    /** "Allow sandbox networking" is off by default. */
    var networkAllowed: Boolean
        get() = settings()["networkAllowed"]?.jsonPrimitive?.booleanOrNull ?: false
        set(value) { putSetting("networkAllowed", JsonPrimitive(value)); refresh() }

    /** The reason why the network disconnection filter cannot be installed (obtained from the launcher self-test); it does not block the installation, but the command will be rejected when the network is disconnected. */
    val isolationIssue: String? get() = record()["isolationIssue"]?.jsonPrimitive?.contentOrNull

    val installedVersion: String? get() = record()["version"]?.jsonPrimitive?.contentOrNull

    @Synchronized
    fun completedStep(): InstallStep? =
        record()["completedStep"]?.jsonPrimitive?.contentOrNull?.let { n -> InstallStep.entries.firstOrNull { it.name == n } }

    /** Make a note of "Completed to [s]" and clear the reason for the last failure; when Done, write the version and completion time. */
    @Synchronized
    fun markStep(s: InstallStep) = markStep(s, null)

    @Synchronized
    internal fun markStep(s: InstallStep, version: String?) {
        val r = record().toMutableMap()
        r["completedStep"] = JsonPrimitive(s.name)
        r.remove("broken")
        if (s == InstallStep.Done) {
            version?.let { r["version"] = JsonPrimitive(it) }
            r["doneAt"] = JsonPrimitive(System.currentTimeMillis())
            installing = null
        }
        writeJson(recordFile, JsonObject(r))
        refresh()
    }

    @Synchronized
    internal fun installing(step: String, percent: Int) {
        installing = SandboxStatus.Installing(percent, step)
        refresh()
    }

    @Synchronized
    internal fun markBroken(reason: String) {
        installing = null
        writeJson(recordFile, JsonObject(record() + ("broken" to JsonPrimitive(reason))))
        refresh()
    }

    @Synchronized
    internal fun recordIsolation(issue: String?) {
        val r = record().toMutableMap()
        if (issue == null) r.remove("isolationIssue") else r["isolationIssue"] = JsonPrimitive(issue)
        writeJson(recordFile, JsonObject(r))
    }

    /** Reset starts: first delete the installation record and display "Reset". If you kill it halfway through deletion, it will no longer display ready. */
    @Synchronized
    internal fun resetting() {
        Files.deleteIfExists(recordFile.toPath())
        installing(tr(R.string.sandbox_resetting), 0)
    }

    /** Installation or reset ends (regardless of success or failure): Cancel the ongoing status and recalculate according to the record. */
    @Synchronized
    internal fun cleared() {
        installing = null
        refresh()
    }

    @Synchronized
    fun refresh() { _status.value = read() }

    private fun read(): SandboxStatus {
        if ("arm64-v8a" !in abis()) return SandboxStatus.Unsupported(tr(R.string.install_unsupported))
        installing?.let { return it }
        val r = record()
        val step = r["completedStep"]?.jsonPrimitive?.contentOrNull
        val broken = r["broken"]?.jsonPrimitive?.contentOrNull
        // Alpine's /bin/sh is an absolute link to /bin/busybox, which cannot be parsed on the host: just
        // look at the link itself
        val hasShell = Files.exists(File(dirs.rootfs, "bin/sh").toPath(), LinkOption.NOFOLLOW_LINKS)
        return when {
            step == InstallStep.Done.name && hasShell -> SandboxStatus.Ready(network = networkAllowed)
            step == InstallStep.Done.name -> SandboxStatus.Broken(tr(R.string.sandbox_incomplete))
            broken != null -> SandboxStatus.Broken(broken)
            step != null -> SandboxStatus.Broken(tr(R.string.sandbox_not_finished))
            else -> SandboxStatus.NotInstalled
        }
    }

    private fun record(): Map<String, JsonElement> = readJson(recordFile)
    private fun settings(): Map<String, JsonElement> = readJson(settingsFile)

    @Synchronized
    private fun putSetting(key: String, value: JsonPrimitive) = writeJson(settingsFile, JsonObject(settings() + (key to value)))

    companion object {
        /** The copy in the process (given by the App): the status of the installation service update. The settings page is the same as what the Agent sees. */
        fun get(context: Context): SandboxState = SandboxOwner.of(context).sandboxState

        private fun readJson(f: File): Map<String, JsonElement> =
            runCatching { Json.parseToJsonElement(f.readText()).jsonObject }.getOrNull() ?: emptyMap()

        /** Write the temporary file first and then rename it. If it is killed in the middle of writing, no bad files will be left behind. */
        private fun writeJson(f: File, o: JsonObject) {
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            // Change the name after placement: When the power is cut off or killed, it will be either
            // the old record or the new record.
            java.io.FileOutputStream(tmp).use { out -> out.write(o.toString().toByteArray()); out.fd.sync() }
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
