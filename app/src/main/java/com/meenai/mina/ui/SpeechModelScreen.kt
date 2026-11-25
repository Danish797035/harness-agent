package com.meenai.mina.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.SegmentedControl
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaType
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.HarnessApp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.ui.components.CapabilityTag
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.PillTone
import com.meenai.mina.ui.components.RadioRow
import com.meenai.mina.ui.components.StatusPill
import com.meenai.mina.ui.components.InputRow
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.voice.SpeechChoice
import com.meenai.mina.voice.SpeechOptions
import com.meenai.mina.voice.SystemSpeech
import com.meenai.mina.voice.DoubaoCredentials
import com.meenai.mina.voice.DoubaoProtocol

/** Select an action on the page. */
class SpeechPickActions(
    /** "Comes with your phone" or "No need": it will take effect on the spot and return. */
    val pick: (SpeechChoice) -> Unit = {},
    /** (Service provider id, model name): Try the transfer first, then select and return after it passes; both those clicked in the list and those filled in by hand go here. */
    val pickModel: (String, String) -> Unit = { _, _ -> },
    /** Enter the settings page of bean bag streaming recognition. */
    val openDoubao: () -> Unit = {},
    /** (API Key, App ID, Access Token, billing method): Encrypt the credentials and select them; not connected to the Internet. */
    val saveDoubao: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    /** Inherit the saved credentials, switch to this billing method and select it. */
    val useDoubao: (String) -> Unit = {},
    val clearDoubao: () -> Unit = {},
)

/**
 * Settings › Model › Speech to Text: Bring a model status yourself, which can be entered directly from
 * elsewhere (such as "Voice input not yet available" in the input box). After selecting (the service
 * provider model that comes with the phone, is not used, and passes the trial transfer), return to the
 * page you entered.
 */
@Composable
fun SpeechModelScreen(store: ProviderStore, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = (context.applicationContext as? HarnessApp)?.speech
    var doubaoConfigured by remember { mutableStateOf(settings?.hasDoubaoCredentials == true) }
    // The setting page of Doubao is stacked on this page; press the return key to close it first
    var doubaoOpen by remember { mutableStateOf(false) }
    val vm = remember {
        ModelSetupViewModel(
            store, scope,
            speech = settings,
            systemSpeech = {
                val silent = settings?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
        )
    }
    val ui by vm.state.collectAsState()
    val probe by vm.speechProbe.collectAsState()
    LaunchedEffect(Unit) { vm.ensureListed(); vm.clearSpeechProbe() }
    val actions = SpeechPickActions(
        pick = { vm.pickSpeech(it); onBack() },
        pickModel = { pid, model -> vm.pickSpeechModel(pid, model, onPicked = onBack) },
        openDoubao = { doubaoOpen = true },
        // After saving, return to the speech-to-text page and see that it has been selected.
        saveDoubao = { key, appId, token, resource ->
            checkNotNull(settings).saveDoubao(DoubaoCredentials(key, appId, token), resource)
            doubaoConfigured = true
            vm.pickSpeech(SpeechChoice.Doubao(resource))
            doubaoOpen = false
        },
        useDoubao = { resource -> vm.pickSpeech(SpeechChoice.Doubao(resource)); doubaoOpen = false },
        clearDoubao = {
            settings?.clearDoubao()
            doubaoConfigured = false
            settings?.let { vm.pickSpeech(it.choice.value) }
            doubaoOpen = false
        },
    )
    if (doubaoOpen) {
        BackHandler { doubaoOpen = false }
        DoubaoSetupPage(ui.speech, doubaoConfigured, actions) { doubaoOpen = false }
    } else {
        SpeechModelContent(ui, probe, actions, onBack, doubaoConfigured)
    }
}

/** The selection page without status is convenient for testing: the top paragraph states, "comes with the phone" and "does not need to be used", and the following is grouped by service provider for single selection. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SpeechModelContent(ui: ModelSetupUi, probe: Map<String, String>, actions: SpeechPickActions, onBack: () -> Unit, doubaoConfigured: Boolean = false) {
    val c = MinaTheme.colors
    val system = ui.systemSpeech
    val choice = ui.speech
    // If it is not selected, it comes with the phone and I am using it, so "not selected" will also mark
    // it as selected. If it is marked as unresponsive, it will not be marked as selected; the row can
    // still be clicked. Select it again to clear the mark and try again.
    val systemSelected = system.usable && (choice == SpeechChoice.System || choice == SpeechChoice.Auto)
    Page(tr(R.string.role_speech), onBack) {
        Text(
            tr(R.string.speech_hint), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2,
        )
        GroupCard {
            RadioRow(
                tr(R.string.speech_system), selected = systemSelected, enabled = system.available,
                onClick = { actions.pick(SpeechChoice.System) }, first = true,
                subtitle = tr(
                    when {
                        !system.available -> R.string.speech_system_none_note
                        !system.responsive -> R.string.speech_system_silent_note
                        system.onDevice -> R.string.speech_system_note
                        else -> R.string.speech_system_note_online
                    },
                ),
                trailing = {
                    when {
                        system.usable -> StatusPill(tr(R.string.speech_tag_ok), PillTone.Ok)
                        system.available -> StatusPill(tr(R.string.speech_tag_silent), PillTone.Neutral)
                        else -> StatusPill(tr(R.string.speech_tag_none), PillTone.Neutral)
                    }
                },
            )
            RadioRow(
                tr(R.string.models_off), selected = choice == SpeechChoice.Off, onClick = { actions.pick(SpeechChoice.Off) },
                subtitle = tr(R.string.speech_off_note),
            )
        }
        DoubaoRow(choice, doubaoConfigured, actions)
        SpeechOptions.groups(ui.providers).forEach { g ->
            val p = g.provider
            val testing = ui.speechTesting == p.id
            GroupLabel(ModelsCopy.providerName(p))
            // The model selected by hand is not in the list, and a line is added to mark it as selected,
            // so that people can see which one is being used.
            val picked = (choice as? SpeechChoice.Provider)?.takeIf { it.providerId == p.id }?.model
            val rows = if (picked != null && picked !in g.models) g.models + picked else g.models
            GroupCard {
                rows.forEachIndexed { i, id ->
                    RadioRow(
                        id, selected = id == picked, onClick = { actions.pickModel(p.id, id) }, first = i == 0,
                        titleStyle = ModelIdStyle, enabled = ui.speechTesting == null,
                        trailing = { CapabilityTag(tr(R.string.speech_tag_speech)) },
                    )
                }
                // What is not in the list (the list is incomplete or this company does not provide a
                // list) must be filled in by hand
                Box(Modifier.testTag("speech-manual-${p.name}")) {
                    ManualModel(p, if (testing) ListState.Running else null, first = rows.isEmpty()) { actions.pickModel(p.id, it) }
                }
            }
            when {
                probe[p.id] != null -> {
                    // The reason why the list is too long is that it falls outside the screen and there
                    // is no response when I click it: when it comes out, scroll until it is visible.
                    val why = probe.getValue(p.id)
                    val into = remember { BringIntoViewRequester() }
                    LaunchedEffect(why) { into.bringIntoView() }
                    FooterNote(why, Modifier.bringIntoViewRequester(into).testTag("speech-pick-failed"), color = c.bad)
                }
                testing && rows.isNotEmpty() -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("speech-pick-running"))
            }
        }
        FooterNote(tr(R.string.speech_footer), Modifier.padding(top = 8.dp))
    }
}

/**
 * "Speak and transfer voice service" group: only one line. If the credentials are not saved, click it to
 * enter the settings page; after saving, click the circle to use it, and go to "Settings" on the right
 * to change the credentials or delete them. The credentials are stored separately from the chat service
 * provider, the model is not listed, and there is no trial transfer.
 */
@Composable
private fun DoubaoRow(choice: SpeechChoice, configured: Boolean, actions: SpeechPickActions) {
    val c = MinaTheme.colors
    GroupLabel(tr(R.string.speech_doubao_group))
    GroupCard {
        val selected = configured && choice is SpeechChoice.Doubao
        val plan = (choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION
        RadioRow(
            tr(R.string.speech_doubao_model), selected = selected, first = true,
            onClick = { if (configured) actions.pick(SpeechChoice.Doubao(plan)) else actions.openDoubao() },
            subtitle = if (configured && selected) tr(R.string.speech_doubao_configured, tr(planName(plan))) else tr(R.string.speech_doubao_note),
            trailing = {
                if (configured) {
                    Row(
                        Modifier.clip(MinaShapes.Pill).clickable(onClick = actions.openDoubao).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)
                            .testTag("speech-doubao-edit"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(tr(R.string.speech_doubao_edit), style = MinaType.Value, color = c.ink2)
                        RowChevron()
                    }
                } else {
                    StatusPill(tr(R.string.settings_not_set), PillTone.Neutral)
                    RowChevron()
                }
            },
        )
    }
}

private fun planName(resource: String): Int =
    if (resource == DoubaoProtocol.CONCURRENT) R.string.speech_doubao_concurrent else R.string.speech_doubao_duration

/**
 * The setting page of Doubao streaming recognition: fill in the credentials (API Key of the new version
 * of the console, or App ID + Access Token of the old version), and select the billing method. Save
 * without connecting to the Internet; saved credentials will not be backfilled, and the input box will
 * not enter a recoverable state. If you only change the billing method and do not refill the
 * credentials, the existing credentials will be used.
 */
@Composable
fun DoubaoSetupPage(choice: SpeechChoice, configured: Boolean, actions: SpeechPickActions, onBack: () -> Unit) {
    val c = MinaTheme.colors
    var legacy by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var resource by remember { mutableStateOf((choice as? SpeechChoice.Doubao)?.resourceId ?: DoubaoProtocol.DURATION) }
    var failed by remember { mutableStateOf(false) }
    val credentials = DoubaoCredentials(if (legacy) "" else key.trim(), if (legacy) appId.trim() else "", if (legacy) token.trim() else "")
    val unchanged = key.isBlank() && appId.isBlank() && token.isBlank()
    Page(tr(R.string.speech_doubao_model), onBack) {
        Text(tr(R.string.speech_doubao_setup_hint), Modifier.padding(top = 4.dp, bottom = 4.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        GroupLabel(tr(R.string.speech_doubao_credentials))
        SegmentedControl(
            listOf(tr(R.string.speech_doubao_new), tr(R.string.speech_doubao_legacy)), if (legacy) 1 else 0,
            onSelect = { legacy = it == 1; key = ""; appId = ""; token = "" },
            Modifier.padding(bottom = 8.dp),
        )
        GroupCard {
            if (legacy) {
                InputRow("App ID", appId, { appId = it }, tr(R.string.speech_doubao_enter_app), first = true, labelWidth = 110.dp)
                InputRow("Access Token", token, { token = it }, tr(R.string.speech_doubao_enter_key), secret = true, labelWidth = 110.dp)
            } else {
                InputRow("API Key", key, { key = it }, tr(R.string.speech_doubao_enter_key), first = true, secret = true, labelWidth = 110.dp)
            }
        }
        GroupLabel(tr(R.string.speech_doubao_plan))
        GroupCard {
            RadioRow(
                tr(R.string.speech_doubao_duration), selected = resource == DoubaoProtocol.DURATION, first = true,
                onClick = { resource = DoubaoProtocol.DURATION }, subtitle = tr(R.string.speech_doubao_duration_note),
            )
            RadioRow(
                tr(R.string.speech_doubao_concurrent), selected = resource == DoubaoProtocol.CONCURRENT,
                onClick = { resource = DoubaoProtocol.CONCURRENT }, subtitle = tr(R.string.speech_doubao_concurrent_note),
            )
        }
        FooterNote(tr(R.string.speech_doubao_privacy), Modifier.padding(top = 8.dp))
        MinaButton(
            tr(R.string.speech_doubao_save),
            onClick = {
                failed = runCatching {
                    if (configured && unchanged) actions.useDoubao(resource)
                    else actions.saveDoubao(credentials.apiKey, credentials.appId, credentials.accessKey, resource)
                }.isFailure
                key = ""; appId = ""; token = ""
            },
            Modifier.fillMaxWidth().padding(top = 8.dp).testTag("speech-doubao-save"),
            enabled = credentials.valid || configured && unchanged,
        )
        if (failed) FooterNote(tr(R.string.speech_doubao_save_failed), Modifier.padding(top = 8.dp), color = c.bad)
        if (configured) {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                Text(
                    tr(R.string.speech_doubao_remove),
                    Modifier.clip(MinaShapes.Pill).clickable(onClick = actions.clearDoubao).padding(horizontal = 16.dp, vertical = 12.dp)
                        .testTag("speech-doubao-remove"),
                    style = MinaType.Body, color = c.bad,
                )
            }
        }
    }
}
