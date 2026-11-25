package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.cap.sandbox.RootfsInstaller
import com.meenai.mina.cap.sandbox.RuntimePreparer
import com.meenai.mina.cap.sandbox.SandboxState
import kotlinx.coroutines.launch
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.HarnessApp
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.cap.sandbox.SandboxUsage
import com.meenai.mina.cap.sandbox.SandboxUsageReader
import com.meenai.mina.cap.sandbox.WorkspaceUsage
import com.meenai.mina.settings.NetProxyStore
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Copywriting for Settings › Files and Computing Environment (pure function, easy for testing). The
 * implementation details of the running environment are invisible to users (product decisions):
 * literally only "scripts", "processing files" and "running environment" are mentioned, and technical
 * terms such as sandbox, Linux, PRoot, and Alpine do not appear.
 */
object SandboxCopy {
    val NETWORK_TITLE: String get() = tr(R.string.sandbox_network)
    val NETWORK_SUBTITLE: String get() = tr(R.string.sandbox_network_sub)
    val NETWORK_EXPLAIN: String get() = tr(R.string.sandbox_network_explain)
    val PROXY_TITLE: String get() = tr(R.string.net_proxy)
    val USAGE_TITLE: String get() = tr(R.string.sandbox_usage)
    val USAGE_NOTE: String get() = tr(R.string.sandbox_usage_note)
    val CLEAR: String get() = tr(R.string.sandbox_usage_clear)
    val CLEAR_TITLE: String get() = tr(R.string.sandbox_usage_clear_title)
    val CLEAR_ALL: String get() = tr(R.string.sandbox_usage_clear_all)
    val CLEAR_ALL_TITLE: String get() = tr(R.string.sandbox_usage_clear_all_title)
    val CLEAR_ALL_BODY: String get() = tr(R.string.sandbox_usage_clear_all_body)

    /**
     * Description of the proxy line (the proxy can be changed in Settings › Network Proxy, and is only
     * referenced here): If it is not set, it is a direct connection; if it is set, write the host and
     * port (without the account and password, this line can be seen at any time); when the network is
     * turned off, it says that the Internet must be allowed first before it will take effect.
     */
    fun proxySub(proxy: String, networkAllowed: Boolean): String = when {
        proxy.isBlank() -> tr(R.string.sandbox_proxy_sub)
        !networkAllowed -> tr(R.string.sandbox_proxy_off, NetProxyStore.display(proxy))
        else -> tr(R.string.sandbox_proxy_on, NetProxyStore.display(proxy))
    }

    fun clearBody(w: WorkspaceUsage): String = tr(R.string.sandbox_usage_clear_body, workspaceTitle(w))

    /** The name of the line in the workspace: conversation title; if the conversation has been deleted, indicate it. */
    fun workspaceTitle(w: WorkspaceUsage): String = w.title?.let(ChatCopy::plainTitle)?.takeIf(String::isNotBlank) ?: tr(R.string.sandbox_usage_deleted_chat)

    fun usageRows(u: SandboxUsage): List<Pair<String, Long>> = listOf(
        tr(R.string.sandbox_usage_total) to u.total,
        tr(R.string.sandbox_usage_runtime) to u.runtime,
        tr(R.string.sandbox_usage_skills) to u.skills,
        tr(R.string.sandbox_usage_workspaces) to u.workspacesTotal,
    )

    /** The number of bytes is written in human-readable units. */
    fun size(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(Locale.ROOT, bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(Locale.ROOT, bytes.toDouble() / (1L shl 20))
        bytes >= 1L shl 10 -> "%.0f KB".format(Locale.ROOT, bytes.toDouble() / (1L shl 10))
        else -> "$bytes B"
    }
    val ISOLATION_UNAVAILABLE: String get() = tr(R.string.sandbox_isolation_unavailable)
    val RESET_TITLE: String get() = tr(R.string.sandbox_reset_title)
    val RESET_BODY: String get() = tr(R.string.sandbox_reset_body)

    /** The line below "Reset the operating environment": explain what will happen in normal times, tell the truth when preparing or when preparation fails. */
    fun resetSubtitle(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Installing -> tr(R.string.runtime_preparing)
        is SandboxStatus.Broken -> tr(R.string.sandbox_broken, s.reason)
        else -> tr(R.string.sandbox_reset_sub)
    }
}

/**
 * Connected to the real status of the "File and Computing Environment" page content: network switch read
 * and write [SandboxState]; agent read [NetProxyStore] (changed on the settings home page, only
 * displayed here); read the used space in the background when entering the page, clean up
 * [SandboxUsageReader] (the dialogue with tasks running is unclear); immediately prepare again in the
 * background after reset.
 */
@Composable
fun AdvancedSettings() {
    val context = LocalContext.current
    val state = remember { SandboxState.get(context) }
    val status by state.status.collectAsState()
    var network by remember { mutableStateOf(state.networkAllowed) }
    val proxies = remember { NetProxyStore.get(context) }
    var proxy by remember { mutableStateOf("") }
    val app = context.applicationContext as HarnessApp
    val usageReader = remember {
        SandboxUsageReader(SandboxDirs(context), { app.conversations.list() }, { app.taskState.activeConversations.value })
    }
    var usage by remember { mutableStateOf<SandboxUsage?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(reload) {
        proxy = withContext(Dispatchers.IO) { proxies.get() }
        usage = withContext(Dispatchers.IO) { runCatching { usageReader.read() }.getOrNull() }
    }
    val scope = rememberCoroutineScope()
    fun clearing(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }
            reload++
        }
    }
    AdvancedSection(
        status = status, networkAllowed = network, isolationIssue = state.isolationIssue != null,
        onNetworkChange = { state.networkAllowed = it; network = it },
        proxy = proxy,
        usage = usage,
        onClearWorkspace = { w -> clearing { usageReader.clear(w.dirName) } },
        onClearAll = { clearing { usageReader.clearAll() } },
        onReset = {
            scope.launch {
                RootfsInstaller.create(context).reset()
                RuntimePreparer.ensure(context, retryFailed = true)
                reload++
            }
        },
    )
}

/** The content of the "Files and Computing Environment" page without status is convenient for testing. The entire group of unsupported devices is not displayed. */
@Composable
fun AdvancedSection(
    status: SandboxStatus,
    networkAllowed: Boolean,
    isolationIssue: Boolean,
    onNetworkChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    /** Network proxy (Settings › Network proxy), an empty string means not using it. */
    proxy: String = "",
    /** Used space; it is null if it has not been read yet (this group will not be displayed yet). */
    usage: SandboxUsage? = null,
    onClearWorkspace: (WorkspaceUsage) -> Unit = {},
    onClearAll: () -> Unit = {},
) {
    if (status is SandboxStatus.Unsupported) return
    var confirming by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf<WorkspaceUsage?>(null) }
    var clearingAll by remember { mutableStateOf(false) }

    GroupCard {
        ListRow(MinaIcons.Link, SandboxCopy.NETWORK_TITLE, SandboxCopy.NETWORK_SUBTITLE, first = true) {
            MinaSwitch(networkAllowed, onNetworkChange, Modifier.testTag("sandbox-network"))
        }
        Note(SandboxCopy.NETWORK_EXPLAIN, if (networkAllowed) MinaTheme.colors.warn else MinaTheme.colors.ink3)
        // The proxy is changed on the settings home page (Mina's own connection also uses it); the
        // command here only gets it when the Internet is allowed.
        ListRow(MinaIcons.Globe, SandboxCopy.PROXY_TITLE, SandboxCopy.proxySub(proxy, networkAllowed))
        if (isolationIssue) Note(SandboxCopy.ISOLATION_UNAVAILABLE, MinaTheme.colors.bad)
        ListRow(MinaIcons.Close, tr(R.string.sandbox_reset), SandboxCopy.resetSubtitle(status), titleColor = MinaTheme.colors.bad, tone = TileTone.Bad,
            onClick = if (status is SandboxStatus.Installing) null else ({ confirming = true }))
    }

    usage?.let { u -> UsageGroup(u, onClear = { clearing = it }, onClearAll = { clearingAll = true }) }

    clearing?.let { w ->
        AlertDialog(
            onDismissRequest = { clearing = null },
            title = { Text(SandboxCopy.CLEAR_TITLE) },
            text = { Text(SandboxCopy.clearBody(w)) },
            confirmButton = { TextButton({ clearing = null; onClearWorkspace(w) }) { Text(SandboxCopy.CLEAR, color = MinaTheme.colors.bad) } },
            dismissButton = { TextButton({ clearing = null }) { Text(tr(R.string.cancel)) } },
        )
    }
    if (clearingAll) {
        AlertDialog(
            onDismissRequest = { clearingAll = false },
            title = { Text(SandboxCopy.CLEAR_ALL_TITLE) },
            text = { Text(SandboxCopy.CLEAR_ALL_BODY) },
            confirmButton = { TextButton({ clearingAll = false; onClearAll() }) { Text(SandboxCopy.CLEAR, color = MinaTheme.colors.bad) } },
            dismissButton = { TextButton({ clearingAll = false }) { Text(tr(R.string.cancel)) } },
        )
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(SandboxCopy.RESET_TITLE) },
            text = { Text(SandboxCopy.RESET_BODY) },
            confirmButton = { TextButton({ confirming = false; onReset() }) { Text(tr(R.string.reset), color = MinaTheme.colors.bad) } },
            dismissButton = { TextButton({ confirming = false }) { Text(tr(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Note(text: String, color: Color) {
    Text(text, Modifier.fillMaxWidth().padding(start = 60.dp, end = 16.dp, bottom = 10.dp), style = MinaType.Hint, color = color)
}

/**
 * Used space: Total, running environment, skills, and dialogue workspaces. List the workspaces by
 * dialogue below (larger first). Click a line to clear it (confirm first). Dialogues with tasks running
 * indicate that they cannot be cleared temporarily; the last line clears all workspaces.
 */
@Composable
private fun UsageGroup(u: SandboxUsage, onClear: (WorkspaceUsage) -> Unit, onClearAll: () -> Unit) {
    val c = MinaTheme.colors
    GroupLabel(SandboxCopy.USAGE_TITLE)
    GroupCard {
        SandboxCopy.usageRows(u).forEachIndexed { i, (name, bytes) ->
            ListRow(null, name, first = i == 0) { RowValue(SandboxCopy.size(bytes)) }
        }
    }
    if (u.workspaces.isNotEmpty()) {
        GroupCard(Modifier.padding(top = 8.dp)) {
            u.workspaces.forEachIndexed { i, w ->
                ListRow(
                    null, SandboxCopy.workspaceTitle(w), if (w.busy) tr(R.string.sandbox_usage_busy) else null, first = i == 0,
                    onClick = if (w.busy) null else ({ onClear(w) }),
                ) { RowValue(SandboxCopy.size(w.bytes)) }
            }
            if (u.workspaces.any { !it.busy }) {
                ListRow(null, SandboxCopy.CLEAR_ALL, titleColor = c.bad, onClick = onClearAll)
            }
        }
    }
    Text(SandboxCopy.USAGE_NOTE, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp), style = MinaType.Hint, color = c.ink3)
}
