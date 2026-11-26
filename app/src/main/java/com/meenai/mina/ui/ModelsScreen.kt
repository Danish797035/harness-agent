package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.core.oauth.AccountSignInException
import com.meenai.mina.core.oauth.AccountSignInFailure
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import androidx.compose.ui.res.stringResource
import com.meenai.mina.ui.components.keepsKeyboard
import com.meenai.mina.ui.components.KeyboardDismissArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.core.chatgpt.SignInFailure
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.settings.ModelOption
import com.meenai.mina.settings.ModelOptions
import com.meenai.mina.settings.Provider
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.RoleChoice
import com.meenai.mina.ui.components.BarIconButton
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.Hairline
import com.meenai.mina.ui.components.InputRow
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.components.PillTone
import com.meenai.mina.ui.components.RadioRow
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.SearchField
import com.meenai.mina.ui.components.StatusPill
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaRadius
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaSize
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import com.meenai.mina.ui.router.LocalRouterStatus
import com.meenai.mina.ui.router.RouterStatus
import com.meenai.mina.voice.SpeechChoice
import com.meenai.mina.voice.SpeechOptions

/** Settings › Model: It is the same page ([ModelSetupScreen]) as the configuration model when getting started, and is not in the boot process. */
@Composable
fun ModelsScreen(
    store: ProviderStore,
    onBack: () -> Unit,
    onOpenSpeech: (() -> Unit)? = null,
    routerStatus: RouterStatus = LocalRouterStatus.current,
) {
    ModelSetupScreen(
        store,
        onboarding = false,
        onBack = onBack,
        onDone = onBack,
        onOpenSpeech = onOpenSpeech,
        routerStatus = routerStatus,
    )
}

/** The skeleton shared by sub-pages: top bar + scrollable content. */
@Composable
internal fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(title, MinaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                content()
            }
        }
    }
}

/** Usage card: large icon block, "name · what to do", what was selected (use monospaced fonts for the model name), and description. */
@Composable
internal fun RoleCard(icon: ImageVector, label: String, line: ModelsCopy.RoleLine, first: Boolean = false, tag: String, onClick: () -> Unit) {
    val c = MinaTheme.colors
    Column {
        if (!first) Hairline(start = 0.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag(tag).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(40.dp).background(c.neutralSoft, RoleTileShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(21.dp), tint = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.ink3)
                Text(
                    line.title, Modifier.padding(top = 1.dp), fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = if (line.isModel) FontFamily.Monospace else null, color = if (line.unset) c.ink3 else c.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (line.subtitle.isNotEmpty()) {
                    Text(
                        line.subtitle, Modifier.padding(top = 1.dp), style = MinaType.Caption,
                        color = if (line.subtitleOk) c.ok else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            RowChevron()
        }
    }
}

private val RoleTileShape = RoundedCornerShape(12.dp)

/** Service provider row: On the left is the icon block with the initials of the name; [warning] is a sentence to be reminded (use a reminder color to start a new row). */
@Composable
internal fun ProviderRow(p: Provider, line: String, first: Boolean, warning: String? = null, onClick: () -> Unit) {
    val c = MinaTheme.colors
    Column {
        if (!first) Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("provider-${p.name}").padding(horizontal = 16.dp).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.size(MinaSize.Tile).background(c.neutralSoft, MinaShapes.Tile), contentAlignment = Alignment.Center) {
                Text(p.name.take(1).uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.ink)
            }
            Column(Modifier.weight(1f)) {
                Text(ModelsCopy.providerName(p), style = MinaType.Body, color = c.ink, maxLines = 1)
                Text(line, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = c.ink3)
                warning?.let { Text(it, Modifier.padding(top = 2.dp).testTag("provider-warning-${p.name}"), style = MinaType.Caption, color = c.warn) }
            }
            RowChevron()
        }
    }
}

// ---------- Choose which model to use for a certain purpose ----------

class RolePickerActions(
    /** (service provider id, model name) */
    val pick: (String, String) -> Unit = { _, _ -> },
    /** The model name (service provider id, model name) manually filled in by a service provider that does not provide a list: Try calling it first, and then select it if it succeeds. */
    val pickManual: (String, String) -> Unit = { _, _ -> },
    /** See pictures, lightweight fixed options (use the main model, not use it). */
    val choose: (RoleChoice) -> Unit = {},
    val addProvider: () -> Unit = {},
)

/** Select the fixed options at the top of the page: "Use the main model" or "Don't use it" for the picture, "Use the main model" for the light version; there is no main model. */
internal fun fixedChoices(role: ModelRole): List<RoleChoice> = when (role) {
    ModelRole.Main -> emptyList()
    ModelRole.Vision -> listOf(RoleChoice.SameAsMain, RoleChoice.Off)
    ModelRole.Light -> listOf(RoleChoice.SameAsMain)
}

/**
 * Purpose of selection: Look at pictures, lightweight. The top is fixed options; the following lists all
 * models of all connected service providers grouped by service provider (use a memorized list). The
 * labels are for reference only, and are not filtered or grayed out by capabilities; the model name can
 * be manually filled in under each provider (it is not in the list, or the provider does not provide a
 * list), and the hand-filled ones are marked "Hand-filled". Click to take effect and return.
 */
@Composable
fun RolePickerContent(ui: ModelSetupUi, role: ModelRole, actions: RolePickerActions, onBack: () -> Unit) {
    val c = MinaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    val groups = ui.providers.map { it to ui.options(it) }
    val total = groups.sumOf { it.second.size }
    val q = filter.trim()
    Page(ModelsCopy.roleName(role), onBack) {
        Text(ModelsCopy.roleHint(role), Modifier.padding(top = 4.dp, bottom = 12.dp), fontSize = 14.5.sp, lineHeight = 22.sp, color = c.ink2)
        val fixed = fixedChoices(role)
        if (fixed.isNotEmpty()) {
            GroupCard {
                fixed.forEachIndexed { i, choice ->
                    RadioRow(
                        ModelsCopy.choiceTitle(choice), selected = ui.roles[role] == choice, onClick = { actions.choose(choice) },
                        subtitle = ModelsCopy.choiceNote(role, choice), first = i == 0,
                    )
                }
            }
        }
        if (total > FILTER_FROM) {
            GroupLabel(ModelsCopy.PICK_FROM, Modifier.padding(top = 8.dp))
            Box(Modifier.padding(bottom = 4.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("model-filter"))
            }
        }
        var anyShown = false
        groups.forEach { (p, options) ->
            val shown = options.filter { q.isEmpty() || it.id.contains(q, ignoreCase = true) }
            // This company cannot list models: filling in by hand is the only option
            val noList = p.models?.isEmpty() == true
            if (q.isNotEmpty() && shown.isEmpty()) return@forEach
            anyShown = true
            val state = ui.listing[p.id]
            GroupLabel(ModelsCopy.providerName(p))
            GroupCard {
                shown.take(PICK_AT_MOST).forEachIndexed { i, o ->
                    RadioRow(
                        o.id, selected = ui.picked(role, p.id, o.id), onClick = { actions.pick(p.id, o.id) }, first = i == 0,
                        titleStyle = ModelIdStyle, below = { Tags(o, forVision = role == ModelRole.Vision) },
                    )
                }
                ManualModel(p, ui.manualPick[p.id], first = shown.isEmpty()) { actions.pickManual(p.id, it) }
            }
            val manualState = ui.manualPick[p.id]
            when {
                manualState is ListState.Failed -> FooterNote(manualState.message, Modifier.testTag("manual-model-failed"), color = c.bad)
                // The model in the selection list may also be tried to be called first (see
                // ModelSetupUi.probeBeforePick); the hand-filled row will display the progress by itself
                !noList && manualState == ListState.Running -> FooterNote(ModelSetupCopy.TESTING, Modifier.testTag("pick-probe-running"))
                state == ListState.Running -> FooterNote(ModelsCopy.LISTING)
                state is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                shown.size > PICK_AT_MOST -> FooterNote(ModelsCopy.more(shown.size - PICK_AT_MOST))
            }
        }
        if (q.isNotEmpty() && !anyShown) FooterNote(ModelsCopy.NO_MATCH)
        if (ui.providers.isEmpty()) FooterNote(ModelsCopy.ROLE_EMPTY)
        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MinaIcons.Plus, ModelsCopy.ADD_PROVIDER, first = true, titleColor = c.accent, tone = TileTone.Accent, onClick = actions.addProvider)
        }
        FooterNote(ModelsCopy.PICKER_NOTE)
    }
}

/** Manually filled model name (not in the list, or the service does not provide a list): fill it in according to the service provider's documentation; click "Use" to try calling it once ([state] is the progress), only select it after passing it and record it as manual filling. */
@Composable
internal fun ManualModel(p: Provider, state: ListState?, first: Boolean, onUse: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val running = state == ListState.Running
    val enabled = name.isNotBlank() && !running
    val example = ModelSetupCopy.modelExample(ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind)
    InputRow(ModelsCopy.MODEL_NAME, name, { name = it }, example, first = first, modifier = Modifier.testTag("manual-model")) {
        Text(
            if (running) ModelSetupCopy.TESTING else ModelsCopy.USE, Modifier.clickable(enabled = enabled) { onUse(name.trim()) }.testTag("manual-model-use"),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (enabled) MinaTheme.colors.accent else MinaTheme.colors.ink3,
        )
    }
}

private const val FILTER_FROM = 8
private const val PICK_AT_MOST = 80

/** You can put up to a few models in the service provider details; the rest go to the "All Models" page. */
internal const val MODEL_PREVIEW = 3

// ---------- A service provider ----------

class ProviderDetailActions(
    val refresh: () -> Unit = {},
    val changeKey: (String) -> Unit = {},
    val remove: () -> Unit = {},
    /** Use a model of this house for a certain purpose (click on the "Use" panel). */
    val use: (ModelRole, String) -> Unit = { _, _ -> },
    /** The user asked whether a model of this company can be used to view pictures ("used as" a switch in the panel). */
    val setVision: (String, Boolean) -> Unit = { _, _ -> },
    val showAll: () -> Unit = {},
    /** Try calling the hand-filled model that is in use but has not been verified again (see [ModelSetupUi.unverified]). */
    val retest: () -> Unit = {},
    /** Service providers (ChatGPT, xAI, OpenRouter) that log in with an account: Log in again, log out, and open the other party's usage or account page. */
    val signIn: () -> Unit = {},
    val signOut: () -> Unit = {},
    val manageUsage: () -> Unit = {},
    /** Set this default model as the main model (the line in the account login details). */
    val useAsMain: () -> Unit = {},
)

/**
 * A service provider: connection (status, interface, address, Key can be replaced), several models (the
 * one in use first, up to [MODEL_PREVIEW], and the rest click "View All"), delete. Click a model to pop
 * up "Use" from the bottom. The page length is basically fixed, and no matter how many models there are,
 * the deletion will not be pushed very far.
 */
@Composable
fun ProviderDetailContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MinaTheme.colors
    var changingKey by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var using by remember { mutableStateOf<ModelOption?>(null) }
    val state = ui.listing[p.id]
    val list = ui.sortedOptions(p)
    Page(ModelsCopy.providerName(p), onBack) {
        GroupLabel(ModelsCopy.CONNECTION)
        if (p.kind == ProviderConfig.Kind.ChatGpt) {
            ChatGptConnection(ui, state, actions)
        } else if (p.kind == ProviderConfig.Kind.GeminiWeb || p.signIn == ProviderStore.GEMINI_WEB_SIGN_IN) {
            GeminiWebConnection(ui, state, actions)
        } else if (p.kind == ProviderConfig.Kind.Xai || p.signIn == ProviderStore.OPENROUTER_SIGN_IN) {
            AccountConnection(ui, p, state, actions)
        } else {
            GroupCard {
                ListRow(null, ModelsCopy.STATUS, first = true) {
                    when (state) {
                        ListState.Running -> RowValue(ModelsCopy.LISTING)
                        is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                        else -> RowValue(ModelsCopy.CONNECTED, ValueTone.Ok)
                    }
                }
                ListRow(null, ModelsCopy.KIND) { RowValue(ModelsCopy.kindLabel(p.kind)) }
                ListRow(null, ModelsCopy.BASE_URL) { RowValue(ModelsCopy.shortUrl(p.baseUrl)) }
                ListRow(null, ModelsCopy.API_KEY, onClick = { changingKey = true }) {
                    RowValue(if (p.id in ui.keyed) ModelsCopy.KEY_SAVED else ModelsCopy.KEY_NONE)
                    RowValue(ModelsCopy.CHANGE, ValueTone.Link)
                }
            }
        }
        (state as? ListState.Failed)?.let { FooterNote(ModelSetupCopy.failed(it.message), color = c.bad) }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GroupLabel(ModelsCopy.MODELS, Modifier.weight(1f))
            BarIconButton(MinaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
        }
        if (list.isNotEmpty()) {
            GroupCard {
                list.take(MODEL_PREVIEW).forEachIndexed { i, o -> ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o } }
                if (list.size > MODEL_PREVIEW) {
                    ListRow(null, ModelsCopy.viewAll(list.size), titleColor = c.accent, onClick = actions.showAll) { RowChevron() }
                }
            }
            FooterNote(ModelsCopy.MODELS_NOTE)
        }
        // Skip the test and save it directly: state clearly that it has not been tested yet, don't say
        // "this company does not provide a model list" - that statement is not true for it
        val untested = ui.untested(p)
        val unverified = ui.unverified(p)
        if (untested) {
            FooterNote(ModelsCopy.UNTESTED_NOTE, Modifier.testTag("provider-untested"), color = c.warn)
        } else if (unverified.isNotEmpty()) {
            FooterNote(ModelsCopy.unverifiedNote(unverified), Modifier.testTag("provider-unverified"), color = c.warn)
        }
        // If there is a model in use, you can retest it on the spot; if there is no model in use,
        // refresh the list or select one.
        if (unverified.isNotEmpty()) {
            val retest = ui.retest[p.id]
            MinaButton(
                if (retest == ListState.Running) ModelSetupCopy.TESTING else ModelsCopy.RETEST, actions.retest,
                Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("provider-retest"),
                kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = retest != ListState.Running,
            )
            (retest as? ListState.Failed)?.let { FooterNote(it.message, Modifier.testTag("provider-retest-failed"), color = c.bad) }
        }
        when {
            // The model I saved after skipping the test cannot be used as evidence that "this company
            // does not provide listings"
            p.models?.isEmpty() == true && !untested -> FooterNote(ModelsCopy.NO_LIST_NOTE)
            p.models == null && list.isEmpty() && state != ListState.Running -> FooterNote(ModelsCopy.NOT_LISTED)
        }

        GroupCard(Modifier.padding(top = 8.dp)) {
            ListRow(MinaIcons.Trash, ModelsCopy.DELETE, titleColor = c.bad, tone = TileTone.Bad, first = true, onClick = { confirmDelete = true })
        }
    }

    using?.let { o -> UseAsSheet(ui, p, o.id, onUse = { role -> actions.use(role, o.id) }, onVision = { actions.setVision(o.id, it) }, onDismiss = { using = null }) }
    if (changingKey) {
        var key by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { changingKey = false },
            title = { Text(ModelsCopy.CHANGE_KEY) },
            text = {
                KeyboardDismissArea {
                    Box(Modifier.fillMaxWidth().keepsKeyboard().background(c.fill, MinaShapes.Button).padding(12.dp)) {
                        if (key.isEmpty()) Text(ModelsCopy.NEW_KEY_HINT, style = MinaType.Body, color = c.ink3)
                        BasicTextField(
                            key, { key = it }, Modifier.fillMaxWidth().testTag("new-key"), singleLine = true,
                            textStyle = MinaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton({
                    changingKey = false
                    if (key.isNotBlank()) actions.changeKey(key)
                }) { Text(ModelsCopy.SAVE) }
            },
            dismissButton = { TextButton({ changingKey = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(ModelsCopy.deleteTitle(ModelsCopy.providerName(p))) },
            text = { Text(ModelsCopy.deleteBody(ui.rolesOf(p))) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    actions.remove()
                }, Modifier.testTag("confirm-delete")) { Text(ModelsCopy.DELETE_CONFIRM, color = c.bad) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text(ModelSetupCopy.CANCEL) } },
        )
    }
}

/**
 * ChatGPT connection: account (email), log out or log in again, manage usage (open ChatGPT usage page).
 * After logging out, only the token on this phone will be cleared, and the service provider, model and
 * purpose will be retained.
 */
@Composable
private fun ChatGptConnection(ui: ModelSetupUi, state: ListState?, actions: ProviderDetailActions) {
    val c = MinaTheme.colors
    val account = ui.chatGptAccount
    val signedIn = account?.signedIn == true
    GroupCard {
        ListRow(null, ModelsCopy.STATUS, first = true) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
        account?.email?.takeIf { it.isNotEmpty() }?.let { email -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT) { RowValue(email) } }
        ListRow(null, ModelsCopy.CHATGPT_MANAGE_USAGE, titleColor = c.accent, onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(
                null, if (ui.chatGptSignIn == ListState.Running) ModelsCopy.CHATGPT_WAITING else ModelsCopy.CHATGPT_CONTINUE,
                titleColor = c.accent, onClick = actions.signIn,
            )
        }
    }
    FooterNote(if (signedIn) ModelsCopy.CHATGPT_PLAN_NOTE else ModelsCopy.CHATGPT_SIGNED_OUT_NOTE)
    if (ui.chatGptRevokeUnconfirmed) FooterNote(ModelsCopy.CHATGPT_REVOKE_UNCONFIRMED, color = c.warn)
    (ui.chatGptSignIn as? ListState.Failed)?.let { FooterNote(it.message, color = c.bad) }
}

@Composable
private fun GeminiWebConnection(ui: ModelSetupUi, state: ListState?, actions: ProviderDetailActions) {
    val c = MinaTheme.colors
    val account = ui.geminiWebAccount
    val signedIn = account?.signedIn == true
    GroupCard {
        ListRow(null, ModelsCopy.STATUS, first = true) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
        account?.email?.takeIf { it.isNotEmpty() }?.let { email -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT) { RowValue(email) } }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(
                null, if (ui.geminiWebSignIn == ListState.Running) tr(R.string.signin_gemini_waiting) else tr(R.string.signin_continue),
                titleColor = c.accent, onClick = actions.signIn,
            )
        }
    }
    FooterNote(if (signedIn) tr(R.string.account_signed_in_note_gemini) else tr(R.string.account_signed_out_note_gemini))
    (ui.geminiWebSignIn as? ListState.Failed)?.let { FooterNote(it.message, color = c.bad) }
}

/**
 * Connections logged in with xAI or OpenRouter accounts: account, status, set as main model, open the
 * other party's account page (OpenRouter is the Keys page), log out or log in again. After logging out,
 * only the login information on this phone (xAI token, Key exchanged from OpenRouter) will be cleared,
 * and the service provider, model and purpose will be retained.
 */
@Composable
private fun AccountConnection(ui: ModelSetupUi, p: Provider, state: ListState?, actions: ProviderDetailActions) {
    val c = MinaTheme.colors
    val kind = if (p.kind == ProviderConfig.Kind.Xai) SignInKind.Xai else SignInKind.OpenRouter
    val account = if (kind == SignInKind.Xai) ui.xaiAccount else ui.openRouterAccount
    val signedIn = account?.signedIn == true
    val (_, waiting, failure) = ui.signInState(kind)
    GroupCard {
        account?.label?.takeIf { it.isNotEmpty() }?.let { label -> ListRow(null, ModelsCopy.CHATGPT_ACCOUNT, first = true) { RowValue(label) } }
        ListRow(null, ModelsCopy.STATUS, first = account?.label.isNullOrEmpty()) {
            when {
                !signedIn -> RowValue(ModelsCopy.CHATGPT_SIGNED_OUT, ValueTone.Warn)
                state == ListState.Running -> RowValue(ModelsCopy.LISTING)
                state is ListState.Failed -> RowValue(ModelsCopy.LIST_FAILED, ValueTone.Bad)
                else -> RowValue(ModelsCopy.CHATGPT_SIGNED_IN, ValueTone.Ok)
            }
        }
    }
    GroupCard {
        // Since the main model is already here, this line will not be included.
        val isMain = ui.configFor(ModelRole.Main)?.providerId == p.id
        if (signedIn && !isMain && ui.options(p).isNotEmpty()) {
            ListRow(null, ModelsCopy.SET_MAIN, ModelsCopy.SET_MAIN_NOTE, first = true, onClick = actions.useAsMain) { RowChevron() }
        }
        ListRow(null, ModelsCopy.accountManage(kind), titleColor = c.accent, first = !(signedIn && !isMain && ui.options(p).isNotEmpty()), onClick = actions.manageUsage) { RowChevron() }
        if (signedIn) {
            ListRow(null, ModelsCopy.CHATGPT_SIGN_OUT, ModelsCopy.SIGN_OUT_NOTE, titleColor = c.bad, onClick = actions.signOut)
        } else {
            ListRow(null, if (waiting) ModelsCopy.SIGN_IN_RUNNING else ModelsCopy.signInStart(kind), titleColor = c.accent, onClick = actions.signIn)
        }
    }
    FooterNote(ModelsCopy.accountNote(kind, signedIn))
    if (signedIn && kind == SignInKind.Xai) FooterNote(ModelsCopy.XAI_403_NOTE, Modifier.testTag("account-403-note"), color = c.warn)
    failure?.let { FooterNote(it, color = c.bad) }
}

/**
 * "<Service Provider> · All models": The search box is fixed under the top bar, and the list is only scrolled below
 * (typesetting is required when there are many models); refresh the upper right corner and re-list it.
 * Clicking on the model also pops up "Use".
 */
@Composable
fun AllModelsContent(ui: ModelSetupUi, p: Provider, actions: ProviderDetailActions, onBack: () -> Unit) {
    val c = MinaTheme.colors
    var filter by rememberSaveable { mutableStateOf("") }
    var using by remember { mutableStateOf<ModelOption?>(null) }
    val state = ui.listing[p.id]
    val all = remember(p, ui.providers, ui.configs, ui.roles) { ui.sortedOptions(p) }
    val q = filter.trim()
    val shown = remember(all, q) {
        if (q.isEmpty()) all else all.filter { it.id.contains(q, ignoreCase = true) }
    }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(ModelsCopy.allModelsTitle(ModelsCopy.providerName(p)), MinaIcons.Back, onBack, stringResource(R.string.back), right = {
                BarIconButton(MinaIcons.Refresh, ModelsCopy.REFRESH, actions.refresh, enabled = state != ListState.Running)
            })
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp)) {
                SearchField(filter, { filter = it }, ModelsCopy.FILTER_HINT, Modifier.testTag("all-models-filter"))
            }
            when (state) {
                ListState.Running -> FooterNote(ModelsCopy.LISTING)
                is ListState.Failed -> FooterNote(ModelSetupCopy.failed(state.message), color = c.bad)
                else -> Unit
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
                itemsIndexed(shown, key = { _, o -> o.id }, contentType = { _, _ -> "model_row" }) { i, o ->
                    Box(Modifier.fillMaxWidth().clip(rowShape(i, shown.size)).background(c.surface)) {
                        ModelRow(o, ui.rolesOf(p.id, o.id), first = i == 0) { using = o }
                    }
                }
                item(key = "footer", contentType = "footer") {
                    FooterNote(
                        if (q.isNotEmpty() && shown.isEmpty()) ModelsCopy.NO_MATCH else ModelsCopy.allModelsNote(all.size),
                        Modifier.padding(top = 10.dp),
                    )
                }
            }
        }
    }
    using?.let { o -> UseAsSheet(ui, p, o.id, onUse = { role -> actions.use(role, o.id) }, onVision = { actions.setVision(o.id, it) }, onDismiss = { using = null }) }
}

private val RowSingleShape = RoundedCornerShape(MinaRadius.Card)
private val RowTopShape = RoundedCornerShape(topStart = MinaRadius.Card, topEnd = MinaRadius.Card)
private val RowMiddleShape = RoundedCornerShape(0.dp)
private val RowBottomShape = RoundedCornerShape(bottomStart = MinaRadius.Card, bottomEnd = MinaRadius.Card)

/** When a card is split into lazy-loaded rows, the corners of each row will be rounded: the first row will be rounded at the top, and the last row will be rounded at the bottom. */
private fun rowShape(i: Int, n: Int): RoundedCornerShape = when {
    n <= 1 -> RowSingleShape
    i == 0 -> RowTopShape
    i == n - 1 -> RowBottomShape
    else -> RowMiddleShape
}

/** One line of model: model name (same width) and label, with the right side indicating what purpose it is working for. */
@Composable
private fun ModelRow(o: ModelOption, roles: List<ModelRole>, first: Boolean, onClick: () -> Unit) {
    Column {
        if (!first) Hairline(start = 16.dp)
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).testTag("model-${o.id}").padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(o.id, style = ModelIdStyle, color = MinaTheme.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box(Modifier.padding(top = 4.dp)) { Tags(o, accent = false) }
            }
            roles.forEach { StatusPill(ModelsCopy.roleName(it), PillTone.Accent) }
            RowChevron()
        }
    }
}

/**
 * After clicking on a model, it rises from the bottom: "<Service Provider> · Used as" main model / picture viewing
 * model / lightweight model, it will take effect immediately after clicking it; below is the "can view
 * pictures" switch: models that are not listed are left to the user, and those that are written in the
 * list can also be changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UseAsSheet(ui: ModelSetupUi, p: Provider, model: String, onUse: (ModelRole) -> Unit, onVision: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val c = MinaTheme.colors
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp).testTag("use-as")) {
            Text(model, Modifier.padding(horizontal = 16.dp), style = ModelIdStyle, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ModelsCopy.useAsSubtitle(ModelsCopy.providerName(p)), Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), style = MinaType.Caption, color = c.ink3)
            ModelRole.entries.forEachIndexed { i, role ->
                val on = ui.picked(role, p.id, model)
                RadioRow(
                    ModelsCopy.roleName(role), selected = on, first = i == 0,
                    subtitle = if (on) ModelsCopy.IN_USE else ModelsCopy.roleWhat(role),
                    onClick = { onUse(role); onDismiss() },
                )
            }
            ui.options(p).firstOrNull { it.id == model }?.let { o ->
                val usedForVision = ModelRole.Vision in ui.rolesOf(p.id, model)
                val sees = ModelOptions.seesImages(o.capabilities.vision, o.visionByUser, usedForVision)
                ListRow(null, ModelsCopy.SEES_IMAGES, ModelsCopy.seesNote(o, usedForVision)) {
                    MinaSwitch(sees, onVision, Modifier.testTag("model-sees-images"))
                }
            }
            ListRow(null, ModelSetupCopy.CANCEL, titleColor = c.ink2, onClick = onDismiss)
        }
    }
}

/** The text of the model page (purpose, selection page, service provider details, all models) (pure function, easy for testing). All three uses are available in the App only here. */
object ModelsCopy {
    val TITLE: String get() = tr(R.string.settings_model)
    val LEAD: String get() = tr(R.string.models_lead)
    val ROLES: String get() = tr(R.string.models_roles)
    val PROVIDERS: String get() = tr(R.string.models_providers)
    val ADD_PROVIDER: String get() = tr(R.string.models_add_provider)
    val KEY_NOTE: String get() = tr(R.string.models_key_note)
    val UNSET: String get() = tr(R.string.settings_not_set)
    val MAIN_ADD_FIRST: String get() = tr(R.string.models_main_add_first)
    val MAIN_PICK: String get() = tr(R.string.models_main_pick)
    val SAME_AS_MAIN: String get() = tr(R.string.models_same_as_main)
    val MAIN_NOT_PICKED: String get() = tr(R.string.models_main_not_picked)
    val OFF: String get() = tr(R.string.models_off)
    val OFF_NOTE: String get() = tr(R.string.models_off_note)
    val VISION_SAME_NOTE: String get() = tr(R.string.models_vision_same_note)
    val LIGHT_SAME_NOTE: String get() = tr(R.string.models_light_same_note)
    val PICK_FROM: String get() = tr(R.string.models_pick_from)
    val FILTER_HINT: String get() = tr(R.string.models_filter_hint)
    val NO_MATCH: String get() = tr(R.string.models_no_match)
    val MODEL_NAME: String get() = tr(R.string.models_model_name)
    val USE: String get() = tr(R.string.models_use)
    val ROLE_EMPTY: String get() = tr(R.string.models_role_empty)
    val PICKER_NOTE: String get() = tr(R.string.models_picker_note)
    val LISTING: String get() = tr(R.string.models_listing)
    val CONNECTION: String get() = tr(R.string.models_connection)
    val STATUS: String get() = tr(R.string.models_status)
    val CONNECTED: String get() = tr(R.string.phone_connected)
    val LIST_FAILED: String get() = tr(R.string.models_list_failed)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.models_url)
    const val API_KEY = "API Key"
    val KEY_SAVED: String get() = tr(R.string.models_key_saved)
    val KEY_NONE: String get() = tr(R.string.models_key_none)
    val CHANGE: String get() = tr(R.string.models_change)
    val CHANGE_KEY: String get() = tr(R.string.models_change_key)
    val NEW_KEY_HINT: String get() = tr(R.string.models_new_key_hint)
    val SAVE: String get() = tr(R.string.save)
    val MODELS: String get() = tr(R.string.models_models)
    val MODELS_NOTE: String get() = tr(R.string.models_models_note)
    val NO_LIST_NOTE: String get() = tr(R.string.models_no_list_note)
    val UNTESTED_NOTE: String get() = tr(R.string.models_untested_note)
    val UNVERIFIED: String get() = tr(R.string.models_unverified)
    val RETEST: String get() = tr(R.string.models_retest)
    val SEES_IMAGES: String get() = tr(R.string.models_sees_images)

    /** Description under the "Can view pictures" switch: What the user said, used to view pictures, stated in the list, not yet confirmed. */
    fun seesNote(o: ModelOption, usedForVision: Boolean): String = tr(when {
        o.visionByUser != null -> R.string.models_sees_note_user
        usedForVision -> R.string.models_sees_note_vision
        o.visionKnown -> R.string.models_sees_note_listed
        else -> R.string.models_sees_note_unconfirmed
    })

    fun unverifiedNote(models: List<String>) = tr(R.string.models_unverified_note, models.joinToString(tr(R.string.list_sep)))
    val NOT_LISTED: String get() = tr(R.string.models_not_listed)
    val REFRESH: String get() = tr(R.string.refresh)
    val IN_USE: String get() = tr(R.string.models_in_use)
    val DELETE: String get() = tr(R.string.models_delete)
    val DELETE_CONFIRM: String get() = tr(R.string.confirm_delete)
    val CHATGPT_TITLE: String get() = tr(R.string.chatgpt_use_plan_title)
    val CHATGPT_BODY: String get() = tr(R.string.chatgpt_use_plan_body)
    val CHATGPT_CONTINUE: String get() = tr(R.string.chatgpt_continue)
    val CHATGPT_WAITING: String get() = tr(R.string.chatgpt_waiting)
    val CHATGPT_WELCOME_TITLE: String get() = tr(R.string.chatgpt_welcome_title)
    val CHATGPT_WELCOME_BODY: String get() = tr(R.string.chatgpt_welcome_body)
    val CHATGPT_GOT_IT: String get() = tr(R.string.chatgpt_got_it)
    val CHATGPT_ACCOUNT: String get() = tr(R.string.chatgpt_account)
    val CHATGPT_SIGNED_IN: String get() = tr(R.string.chatgpt_signed_in)
    val CHATGPT_SIGNED_OUT: String get() = tr(R.string.chatgpt_signed_out)
    val CHATGPT_SIGN_OUT: String get() = tr(R.string.chatgpt_sign_out)
    val CHATGPT_MANAGE_USAGE: String get() = tr(R.string.chatgpt_manage_usage)
    val CHATGPT_PLAN_NOTE: String get() = tr(R.string.chatgpt_plan_note)
    val CHATGPT_SIGNED_OUT_NOTE: String get() = tr(R.string.chatgpt_signed_out_note)
    val CHATGPT_REVOKE_UNCONFIRMED: String get() = tr(R.string.chatgpt_revoke_unconfirmed)

    /**
     * The reason why login using ChatGPT failed (the interface and callback webpage are shared). If you
     * can tell the specific reasons, please mention Plus and Pro only if the server mentions the package
     * and permissions, or if there is no package item in the granted permissions; otherwise, the error
     * code [code] (not confidential) will be given if it is rejected.
     */
    fun chatGptFailure(reason: SignInFailure, code: String? = null): String = when (reason) {
        SignInFailure.Denied -> tr(R.string.chatgpt_fail_denied)
        SignInFailure.StateMismatch, SignInFailure.ClientMismatch -> tr(R.string.chatgpt_fail_mismatch)
        SignInFailure.NoClientId -> tr(R.string.chatgpt_fail_no_client)
        SignInFailure.TimedOut -> tr(R.string.chatgpt_fail_timed_out)
        SignInFailure.Exchange -> tr(R.string.chatgpt_fail_exchange, code?.takeIf { it.isNotBlank() } ?: "unknown")
        SignInFailure.CodeExpired -> tr(R.string.chatgpt_fail_code_expired)
        SignInFailure.Region -> tr(R.string.chatgpt_fail_region)
        SignInFailure.Blocked -> tr(R.string.chatgpt_fail_blocked)
        SignInFailure.IdToken -> tr(R.string.chatgpt_fail_id_token)
        SignInFailure.NoPlanScope -> tr(R.string.chatgpt_fail_no_plan)
        SignInFailure.Network -> tr(R.string.chatgpt_fail_network)
    }

    // ---------- Log in with your account (xAI, OpenRouter; ChatGPT is above) ----------

    val SIGN_IN_GROUP: String get() = tr(R.string.signin_group)
    val KEY_GROUP: String get() = tr(R.string.signin_key_group)
    val SIGN_IN_RUNNING: String get() = tr(R.string.signin_running)
    val SIGN_IN_USE_KEY: String get() = tr(R.string.signin_use_key)
    val SIGN_IN_BUSY: String get() = tr(R.string.signin_busy)

    /** The name of the service provider on the interface: the authorized OpenRouter plus a source, which should be separated from the one where the user fills in the Key. */
    fun providerName(p: Provider): String =
        if (p.signIn == ProviderStore.OPENROUTER_SIGN_IN) tr(R.string.signin_openrouter_name, p.name) else p.name
    val XAI_CODE_TITLE: String get() = tr(R.string.signin_xai_code_title)
    val XAI_CODE_BODY: String get() = tr(R.string.signin_xai_code_body)
    val XAI_CODE_COPY_REOPEN: String get() = tr(R.string.signin_xai_code_copy_reopen)
    val XAI_CODE_WAITING: String get() = tr(R.string.signin_xai_code_waiting)
    val XAI_403_NOTE: String get() = tr(R.string.signin_xai_403_note)
    val SET_MAIN: String get() = tr(R.string.signin_set_main)
    val SET_MAIN_NOTE: String get() = tr(R.string.signin_set_main_note)
    val SIGN_OUT_NOTE: String get() = tr(R.string.signin_sign_out_note)
    /** xAI’s account page. */
    const val ACCOUNT_MANAGE_URL = "https://accounts.x.ai"

    fun codeValid(minutes: Int) = tr(R.string.signin_xai_code_valid, minutes)

    /** "Log in with your account" is the line of small words below each house in the group. */
    fun signInRowLine(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> tr(R.string.signin_row_chatgpt)
        SignInKind.GeminiWeb -> tr(R.string.signin_row_gemini)
        SignInKind.Xai -> tr(R.string.signin_row_xai)
        SignInKind.OpenRouter -> tr(R.string.signin_row_openrouter)
    }

    fun signInTitle(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_TITLE
        SignInKind.GeminiWeb -> tr(R.string.signin_gemini_title)
        SignInKind.Xai -> tr(R.string.signin_xai_title)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_title)
    }

    fun signInBody(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_BODY
        SignInKind.GeminiWeb -> tr(R.string.signin_gemini_body)
        SignInKind.Xai -> tr(R.string.signin_xai_body)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_body)
    }

    /** Risks that need to be stated clearly before logging in (yellow background); null if not. */
    fun signInRisk(kind: SignInKind): String? = if (kind == SignInKind.Xai) tr(R.string.signin_xai_risk) else null

    /** Supplementary instructions before login (grey background); null if not. */
    fun signInInfo(kind: SignInKind): String? = when (kind) {
        SignInKind.ChatGpt -> null
        SignInKind.GeminiWeb -> tr(R.string.signin_gemini_info)
        SignInKind.Xai -> tr(R.string.signin_stored_note)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_note)
    }

    fun signInStart(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_CONTINUE
        SignInKind.GeminiWeb -> tr(R.string.signin_continue)
        SignInKind.Xai -> tr(R.string.signin_continue)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_authorize)
    }

    fun signInWaiting(kind: SignInKind) = when (kind) {
        SignInKind.ChatGpt -> CHATGPT_WAITING
        SignInKind.GeminiWeb -> tr(R.string.signin_gemini_waiting)
        SignInKind.Xai -> tr(R.string.signin_xai_starting)
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_waiting)
    }

    fun accountManage(kind: SignInKind) = when (kind) {
        SignInKind.OpenRouter -> tr(R.string.signin_openrouter_manage)
        else -> tr(R.string.account_manage_xai)
    }

    /** Instructions below the account login details: what you are using and what happens after you log out. */
    fun accountNote(kind: SignInKind, signedIn: Boolean) = when (kind) {
        SignInKind.OpenRouter -> tr(if (signedIn) R.string.signin_openrouter_signed_in_note else R.string.signin_openrouter_signed_out_note)
        SignInKind.GeminiWeb -> tr(if (signedIn) R.string.account_signed_in_note_gemini else R.string.account_signed_out_note_gemini)
        else -> tr(if (signedIn) R.string.account_signed_in_note_xai else R.string.account_signed_out_note)
    }

    fun accountFailure(e: AccountSignInException): String = when (e.reason) {
        AccountSignInFailure.Denied -> tr(R.string.account_fail_denied)
        AccountSignInFailure.Expired -> tr(R.string.account_fail_expired)
        AccountSignInFailure.TimedOut -> tr(R.string.account_fail_timed_out)
        AccountSignInFailure.Rejected -> tr(R.string.account_fail_rejected, e.code ?: "unknown")
        AccountSignInFailure.NoSubscription -> tr(R.string.account_fail_no_sub)
        AccountSignInFailure.Network -> tr(R.string.account_fail_network)
        AccountSignInFailure.BadIssuer -> tr(R.string.account_fail_issuer)
    }

    /** A line on the usage card: [title] is the model name ([isModel]), "use the main model", "not used" or "not set" ([unset]), and [subtitle] is the supplementary description. */
    data class RoleLine(
        val title: String, val subtitle: String, val isModel: Boolean = false, val unset: Boolean = false,
        /** The subtitle is "Available" in green (the voice-to-text text says "Available offline"). */
        val subtitleOk: Boolean = false,
    )

    fun roleName(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main)
        ModelRole.Vision -> tr(R.string.role_vision)
        ModelRole.Light -> tr(R.string.role_light)
    }

    /** What is this used for. */
    fun roleWhat(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_what)
        ModelRole.Vision -> tr(R.string.role_vision_what)
        ModelRole.Light -> tr(R.string.role_light_what)
    }

    fun roleLabel(role: ModelRole) = "${roleName(role)} · ${roleWhat(role)}"

    /** Select Description at the top of the page. */
    fun roleHint(role: ModelRole) = when (role) {
        ModelRole.Main -> tr(R.string.role_main_hint)
        ModelRole.Vision -> tr(R.string.role_vision_hint)
        ModelRole.Light -> tr(R.string.role_light_hint)
    }

    /** The fourth use of "speech to text" is not in [ModelRole] (it is not a dialogue model). The name, icon, and two lines on the right of the card are here. */
    val SPEECH_LABEL: String get() = "${tr(R.string.role_speech)} · ${tr(R.string.role_speech_what)}"
    val SPEECH_ICON: ImageVector get() = MinaIcons.Mic

    fun speechLine(ui: ModelSetupUi): RoleLine {
        val s = SpeechOptions.summary(ui.speech, ui.systemSpeech, ui.providers)
        val unset = s.title == UNSET
        return RoleLine(s.title, s.sub.orEmpty(), isModel = !unset && (ui.speech is SpeechChoice.Provider || ui.speech is SpeechChoice.Doubao), unset = unset, subtitleOk = s.subOk)
    }

    fun roleIcon(role: ModelRole): ImageVector = when (role) {
        ModelRole.Main -> MinaIcons.Spark
        ModelRole.Vision -> MinaIcons.Eye
        ModelRole.Light -> MinaIcons.Leaf
    }

    /** What to write on the purpose card: If you choose a model, write the model name and service provider; if you "use the main model", write who the main model is; if you "don't use", just rely on text to read the screen. */
    fun roleLine(ui: ModelSetupUi, role: ModelRole): RoleLine {
        fun modelLine(cfg: ProviderConfig) = RoleLine(cfg.model, ui.providerOf(cfg)?.let(::providerName) ?: cfg.name, isModel = true)
        val main = ui.configFor(ModelRole.Main)
        return when (val choice = ui.roles[role]) {
            null -> RoleLine(UNSET, if (ui.providers.isEmpty()) MAIN_ADD_FIRST else MAIN_PICK, unset = true)
            RoleChoice.SameAsMain -> RoleLine(SAME_AS_MAIN, main?.model ?: MAIN_NOT_PICKED)
            RoleChoice.Off -> RoleLine(OFF, OFF_NOTE)
            is RoleChoice.Model -> ui.configs.firstOrNull { it.id == choice.configId }?.let(::modelLine)
                ?: RoleLine(UNSET, MAIN_PICK, unset = true)
        }
    }

    fun choiceTitle(choice: RoleChoice) = if (choice == RoleChoice.Off) OFF else SAME_AS_MAIN

    fun choiceNote(role: ModelRole, choice: RoleChoice) = when {
        choice == RoleChoice.Off -> OFF_NOTE
        role == ModelRole.Vision -> VISION_SAME_NOTE
        else -> LIGHT_SAME_NOTE
    }

    fun roleNames(roles: List<ModelRole>) = roles.joinToString(tr(R.string.list_sep)) { roleName(it) }

    fun deleteTitle(name: String) = tr(R.string.models_delete_title, name)

    /** Before deleting, explain clearly what uses will be affected. */
    fun deleteBody(roles: List<ModelRole>) =
        tr(R.string.models_delete_body) + if (roles.isEmpty()) tr(R.string.models_delete_unused) else tr(R.string.models_delete_used, roleNames(roles))

    fun more(n: Int) = trPlural(R.plurals.models_more, n)

    fun viewAll(n: Int) = trPlural(R.plurals.models_view_all, n)

    fun allModelsTitle(name: String) = tr(R.string.models_all_title, name)

    fun allModelsNote(n: Int) = trPlural(R.plurals.models_all_note, n)

    fun useAsSubtitle(name: String) = tr(R.string.models_use_as, name)

    fun kindLabel(kind: ProviderConfig.Kind) = when (kind) {
        ProviderConfig.Kind.OpenAiCompat -> tr(R.string.models_openai_compat)
        ProviderConfig.Kind.Anthropic -> "Anthropic"
        ProviderConfig.Kind.Gemini -> "Gemini"
        ProviderConfig.Kind.OpenAiResponses -> "OpenAI Responses"
        ProviderConfig.Kind.ChatGpt -> "ChatGPT"
        ProviderConfig.Kind.Xai -> "xAI Responses"
        ProviderConfig.Kind.GeminiWeb -> "Gemini Web (Google One AI Premium)"
    }

    fun shortUrl(url: String) = url.removePrefix("https://").removePrefix("http://")

    /** The number of models to remember; it will be empty if it has not been listed yet, and it will be specified if the service does not provide a list. */
    fun modelCount(p: Provider): String? = when {
        p.models == null -> null
        p.models.isEmpty() -> tr(R.string.models_no_list)
        else -> trPlural(R.plurals.models_count, p.models.count { it.usable })
    }

    /** Description of the service provider line: Connected · N models · Which purposes are in use; description when refresh fails. */
    fun providerLine(p: Provider, state: ListState?, roles: List<ModelRole>): String =
        if (state is ListState.Failed) LIST_FAILED
        else listOfNotNull(CONNECTED, modelCount(p), roles.takeIf { it.isNotEmpty() }?.let { tr(R.string.models_used_by, roleNames(it)) }).joinToString(" · ")
}
