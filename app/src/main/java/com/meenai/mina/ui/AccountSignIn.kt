package com.meenai.mina.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/** The three companies in the "Log in with account" group in "Add Model". There is another set of login for ChatGPT, xAI and OpenRouter correspond to [AccountProvider]. */
enum class SignInKind(val account: AccountProvider?, val label: String, val tag: String) {
    ChatGpt(null, "ChatGPT", "chatgpt"),
    GeminiWeb(null, "Google Gemini", "gemini_web"),
    Xai(AccountProvider.Xai, "xAI / Grok", "xai"),
    OpenRouter(AccountProvider.OpenRouter, "OpenRouter", "openrouter");

    /** The default selected when "Switch to API Key"; ChatGPT does not have this option. */
    val keyPreset: String? get() = when (this) {
        ChatGpt -> null
        GeminiWeb -> "Gemini"
        Xai -> "xAI"
        OpenRouter -> "OpenRouter"
    }

    companion object {
        fun of(account: AccountProvider): SignInKind = entries.first { it.account == account }
    }
}

/** What this company looks like in the account login: logged in service provider id (none or logged out is null), logging in, and the reason for failure. */
internal fun ModelSetupUi.signInState(kind: SignInKind): Triple<String?, Boolean, String?> = when (kind) {
    SignInKind.ChatGpt -> Triple(
        providers.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }?.id?.takeIf { chatGptAccount?.signedIn == true },
        chatGptSignIn == ListState.Running,
        (chatGptSignIn as? ListState.Failed)?.message,
    )
    SignInKind.GeminiWeb -> Triple(
        providers.firstOrNull { it.kind == ProviderConfig.Kind.GeminiWeb }?.id?.takeIf { geminiWebAccount?.signedIn == true },
        geminiWebSignIn == ListState.Running,
        (geminiWebSignIn as? ListState.Failed)?.message,
    )
    else -> {
        val signedIn = if (kind == SignInKind.Xai) xaiAccount?.signedIn == true else openRouterAccount?.signedIn == true
        val id = providers.firstOrNull {
            if (kind == SignInKind.Xai) it.kind == ProviderConfig.Kind.Xai
            else it.signIn == ProviderStore.OPENROUTER_SIGN_IN
        }?.id
        val mine = accountSignIn.provider == kind.account
        Triple(id?.takeIf { signedIn }, mine && accountSignIn.running, accountSignIn.failure?.takeIf { mine })
    }
}

/**
 * "Log in with account · No need to fill in Key": One line each for ChatGPT, xAI / Grok, and OpenRouter.
 * If you are logged in, write "logged in" and click on the service provider details; if you are not
 * logged in, click on the description panel ([onOpen]) and start logging in in the panel. If you are
 * logging in, write "Logging in..." and click to see the progress.
 */
@Composable
internal fun AccountSignInGroup(ui: ModelSetupUi, onOpen: (SignInKind) -> Unit, openProvider: (String) -> Unit) {
    GroupLabel(ModelsCopy.SIGN_IN_GROUP)
    GroupCard {
        SignInKind.entries.forEachIndexed { i, kind ->
            val (signedIn, running, _) = ui.signInState(kind)
            Box(Modifier.testTag("signin-${kind.tag}")) {
                ListRow(
                    null, kind.label, ModelsCopy.signInRowLine(kind), first = i == 0,
                    onClick = { if (signedIn != null) openProvider(signedIn) else onOpen(kind) },
                ) {
                    when {
                        running -> RowValue(ModelsCopy.SIGN_IN_RUNNING)
                        signedIn != null -> Box(Modifier.testTag("signin-${kind.tag}-signed-in")) { RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok) }
                        else -> RowChevron()
                    }
                }
            }
        }
    }
}

/** Login panel actions: start, cancel, switch to API Key (select the default, open the form to fill in the Key), close. */
class SignInSheetActions(
    val start: (SignInKind) -> Unit = {},
    val cancel: (SignInKind) -> Unit = {},
    val useApiKey: (SignInKind) -> Unit = {},
    val dismiss: () -> Unit = {},
)

/**
 * An account login panel. First explain what will happen (and the risks), and click "Continue to log in"
 * to start; during login: xAI displays a short code (copy and reopen the confirmation page), otherwise
 * write "Complete in browser", you can cancel; if it fails, the reason will be stated in the panel, you
 * can try again. After success, close the panel and open the details from the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SignInSheet(ui: ModelSetupUi, kind: SignInKind, actions: SignInSheetActions) {
    val c = MinaTheme.colors
    ModalBottomSheet(
        onDismissRequest = actions.dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        SignInSheetBody(ui, kind, actions)
    }
}

/** The content in the panel (without the panel itself, convenient for testing). */
@Composable
internal fun SignInSheetBody(ui: ModelSetupUi, kind: SignInKind, actions: SignInSheetActions) {
    val (_, running, failure) = ui.signInState(kind)
    val code = ui.accountSignIn.userCode.takeIf { running && kind == SignInKind.Xai && it.isNotBlank() }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp).testTag("signin-sheet-${kind.tag}")) {
        when {
            code != null -> DeviceCodeBody(ui.accountSignIn, code) { actions.cancel(kind) }
            // There is only one login at the same time: you cannot start here while someone else is
            // logging in.
            else -> IntroBody(kind, running, failure, busy = !running && (ui.chatGptSignIn == ListState.Running || ui.accountSignIn.running || ui.geminiWebSignIn == ListState.Running), actions)
        }
    }
}

@Composable
private fun IntroBody(kind: SignInKind, running: Boolean, failure: String?, busy: Boolean, actions: SignInSheetActions) {
    val c = MinaTheme.colors
    Text(ModelsCopy.signInTitle(kind), Modifier.fillMaxWidth(), style = MinaType.Title, color = c.ink, textAlign = TextAlign.Center)
    Text(ModelsCopy.signInBody(kind), Modifier.padding(top = 10.dp), fontSize = 13.5.sp, lineHeight = 21.sp, color = c.ink2)
    ModelsCopy.signInRisk(kind)?.let { Note(it, c.warnSoft, c.warn, Modifier.testTag("signin-risk")) }
    ModelsCopy.signInInfo(kind)?.let { Note(it, c.fill, c.ink2) }
    MinaButton(
        ModelsCopy.signInStart(kind), { actions.start(kind) },
        Modifier.fillMaxWidth().padding(top = 16.dp).testTag("signin-start"), enabled = !running && !busy,
    )
    if (busy) Text(ModelsCopy.SIGN_IN_BUSY, Modifier.padding(top = 10.dp).testTag("signin-busy"), fontSize = 13.sp, lineHeight = 18.sp, color = c.ink2)
    if (running) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(ModelsCopy.signInWaiting(kind), Modifier.weight(1f), fontSize = 13.sp, lineHeight = 18.sp, color = c.ink2)
            Text(
                ModelSetupCopy.CANCEL,
                Modifier.clickable { actions.cancel(kind) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp).testTag("signin-cancel"),
                fontSize = 13.sp, color = c.accent,
            )
        }
    }
    failure?.let {
        Text(it, Modifier.padding(top = 10.dp).testTag("signin-failed"), fontSize = 13.sp, lineHeight = 18.sp, color = c.bad)
    }
    if (kind.keyPreset != null && !running) {
        MinaButton(
            ModelsCopy.SIGN_IN_USE_KEY, { actions.useApiKey(kind) },
            Modifier.fillMaxWidth().padding(top = 4.dp).testTag("signin-use-key"), kind = ButtonKind.Text, size = ButtonSize.Medium,
        )
    }
}

/** xAI device code: the same short code and validity period as on the browser confirmation page; copy and reopen the confirmation page; you can cancel while waiting. */
@Composable
private fun DeviceCodeBody(s: AccountSignInUi, code: String, cancel: () -> Unit) {
    val c = MinaTheme.colors
    val context = LocalContext.current
    Text(ModelsCopy.XAI_CODE_TITLE, Modifier.fillMaxWidth(), style = MinaType.Title, color = c.ink, textAlign = TextAlign.Center)
    Text(
        ModelsCopy.XAI_CODE_BODY, Modifier.fillMaxWidth().padding(top = 10.dp),
        fontSize = 13.5.sp, lineHeight = 21.sp, color = c.ink2, textAlign = TextAlign.Center,
    )
    Text(
        code, Modifier.fillMaxWidth().padding(top = 16.dp).testTag("signin-code"),
        fontFamily = FontFamily.Monospace, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = 4.sp,
        fontWeight = FontWeight.SemiBold, color = c.ink, textAlign = TextAlign.Center,
    )
    if (s.expiresAtMs > 0) {
        val minutes = ((s.expiresAtMs - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        Text(ModelsCopy.codeValid(minutes.toInt()), Modifier.fillMaxWidth().padding(top = 4.dp), style = MinaType.Hint, color = c.ink3, textAlign = TextAlign.Center)
    }
    MinaButton(
        ModelsCopy.XAI_CODE_COPY_REOPEN, { copyAndReopen(context, code, s.openUrl) },
        Modifier.fillMaxWidth().padding(top = 16.dp).testTag("signin-copy-reopen"), kind = ButtonKind.Tonal,
    )
    Text(
        ModelsCopy.XAI_CODE_WAITING, Modifier.fillMaxWidth().padding(top = 14.dp),
        fontSize = 13.sp, color = c.ink2, textAlign = TextAlign.Center,
    )
    MinaButton(
        ModelSetupCopy.CANCEL, cancel, Modifier.fillMaxWidth().padding(top = 4.dp).testTag("signin-cancel"),
        kind = ButtonKind.Text, size = ButtonSize.Medium,
    )
}

@Composable
private fun Note(text: String, bg: Color, fg: Color, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(top = 12.dp).background(bg, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        fontSize = 12.5.sp, lineHeight = 19.sp, color = fg,
    )
}

private fun copyAndReopen(context: Context, code: String, url: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("code", code))
    Toast.makeText(context, tr(R.string.account_signin_copied), Toast.LENGTH_SHORT).show()
    if (url.isNotBlank()) openInBrowser(context, url)
}
