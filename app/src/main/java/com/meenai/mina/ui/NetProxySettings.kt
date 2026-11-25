package com.meenai.mina.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.cap.web.WebProxy
import com.meenai.mina.core.net.ProxyAddress
import com.meenai.mina.i18n.tr
import com.meenai.mina.settings.NetProxyStore
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Copywriting for Settings › Network Proxy (pure function, easy for testing). The address only contains
 * the protocol, host, and port, without the account and password (this line can be seen at any time).
 * Only change the setting home page; just refer to the line on the file and computing environment page.
 */
object NetProxyCopy {
    val TITLE: String get() = tr(R.string.net_proxy)
    val HINT: String get() = tr(R.string.net_proxy_hint)
    val INVALID: String get() = tr(R.string.net_proxy_invalid)

    /** On the right side of the line setting the home page: "Not set" is not set, but the address is set. */
    fun value(proxy: String): String = NetProxyStore.display(proxy).ifEmpty { tr(R.string.settings_not_set) }

    /**
     * The description below that line: Usually it says which connections it controls; if it is written
     * only for containers and the browser cannot use it, tell the truth. [browser] is empty, which means
     * that the situation on the browser side has not been found yet.
     */
    fun subtitle(proxy: String, browser: WebProxy.Mode?): String {
        val address = ProxyAddress.parse(proxy)
        return when {
            address != null && !address.forApp -> tr(R.string.net_proxy_container_only)
            browser == WebProxy.Mode.Unsupported -> tr(R.string.net_proxy_browser_unsupported)
            browser == WebProxy.Mode.SocksWithCredentials -> tr(R.string.net_proxy_browser_socks_auth)
            else -> tr(R.string.net_proxy_row_sub)
        }
    }
}

/** Change the network proxy: an input box, leaving it empty means not using it; if something is written incorrectly, it will tell you how to write it, and it will not be saved. */
@Composable
fun NetProxyDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    val ok = NetProxyStore.valid(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(NetProxyCopy.TITLE) },
        text = {
            Column {
                Text(NetProxyCopy.HINT, style = MinaType.Caption, color = MinaTheme.colors.ink3)
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp).testTag("net-proxy-input"),
                    singleLine = true, isError = !ok, placeholder = { Text("http://127.0.0.1:7890") },
                )
                if (!ok) Text(NetProxyCopy.INVALID, Modifier.padding(top = 4.dp), style = MinaType.Hint, color = MinaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton({ onSave(text) }, enabled = ok) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
