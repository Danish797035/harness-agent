package com.meenai.mina.ui.router

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.RoleChoice
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.core.router.MagicubeLedger
import com.meenai.mina.ui.components.AiLogo
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaRadius
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

private val SheetModalShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val HeroCardShape = RoundedCornerShape(18.dp)
private val SearchBarShape = RoundedCornerShape(14.dp)
private val ModelRowShape = RoundedCornerShape(10.dp)
private val RankBadgeShape = RoundedCornerShape(6.dp)
private val SheetSingleCardShape = RoundedCornerShape(MinaRadius.Card)
private val SheetTopCardShape = RoundedCornerShape(topStart = MinaRadius.Card, topEnd = MinaRadius.Card)
private val SheetMiddleCardShape = RoundedCornerShape(0.dp)
private val SheetBottomCardShape = RoundedCornerShape(bottomStart = MinaRadius.Card, bottomEnd = MinaRadius.Card)

private fun sheetCardShape(index: Int, count: Int): RoundedCornerShape = when {
    count <= 1 -> SheetSingleCardShape
    index == 0 -> SheetTopCardShape
    index == count - 1 -> SheetBottomCardShape
    else -> SheetMiddleCardShape
}

private data class IndexedFilteredModel(
    val key: String,
    val model: ModelQuotaLine,
)

private sealed interface SheetProviderRow {
    val key: String
    val contentType: String

    data class Header(
        override val key: String,
        val usage: ProviderUsage,
        val showTopDivider: Boolean,
    ) : SheetProviderRow {
        override val contentType: String get() = "provider_header"
    }

    data class Model(
        override val key: String,
        val usage: ProviderUsage,
        val model: ModelQuotaLine,
        val showDivider: Boolean,
        val isLastInProvider: Boolean,
    ) : SheetProviderRow {
        override val contentType: String get() = "provider_model"
    }

    data class Legacy(
        override val key: String,
        val usage: ProviderUsage,
    ) : SheetProviderRow {
        override val contentType: String get() = "provider_legacy"
    }
}

/**
 * Bottom sheet opened from the bottom chat composer model pill:
 * - Live connection status + one-tap re-sign-in / retry when expired
 * - Active model & live remaining quota % bar with reset/count details
 * - Compact Thinking / Effort control (Auto, Fast, Medium, Thinking)
 * - Search & capability filters
 * - All configured models grouped by provider with one-tap switching
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouterBottomSheet(
    activeModel: ActiveModel,
    usages: List<ProviderUsage>,
    onPinClick: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    onSelectModel: ((String) -> Unit)? = null,
    onSelectModelWithProvider: ((String, String) -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    preferencesStore: ModelPreferencesStore? = null,
    quotaSnapshot: QuotaSnapshot? = null,
    connectionStatus: ConnectionStatus = ConnectionStatus.Idle,
    connectionDetail: String? = null,
    thinkingEffort: String? = null,
    onSelectThinkingEffort: ((String?) -> Unit)? = null,
    onReconnectSignIn: (() -> Unit)? = null,
    onRetryConnect: (() -> Unit)? = null,
) {
    val colors = MinaTheme.colors
    val context = LocalContext.current
    val prefStore = preferencesStore ?: remember { ModelPreferencesStore.get(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val store = remember { ProviderStore.get(context) }
    val savedProviders by store.providers.collectAsState()
    val savedRoles by store.roles.collectAsState()
    val savedConfigs by store.configs.collectAsState()
    val storedEffort by prefStore.thinkingEffort.collectAsState()
    val currentEffort = thinkingEffort ?: storedEffort

    val connectedProviderIds = remember(savedProviders) { savedProviders.map { it.id }.toSet() }
    val connectedProviderNames = remember(savedProviders) { savedProviders.map { it.name.lowercase() }.toSet() }

    val connectedUsages = remember(usages, savedProviders, connectedProviderIds, connectedProviderNames) {
        if (savedProviders.isEmpty()) {
            usages
        } else {
            usages.filter { u ->
                u.providerId in connectedProviderIds ||
                    u.provider.lowercase() in connectedProviderNames ||
                    savedProviders.any { p ->
                        AppFreeKeyRouter.providerMatches(p, u.providerId) ||
                            AppFreeKeyRouter.providerMatches(p, u.provider)
                    } ||
                    (u.provider.contains("Gemini", ignoreCase = true) && savedProviders.any { it.name.contains("Gemini", ignoreCase = true) })
            }
        }
    }

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ModelPickerFilter.All) }
    var favorites by remember { mutableStateOf(prefStore.getFavorites()) }
    var recents by remember { mutableStateOf(prefStore.getRecentModels()) }
    var unavailableDialogModel by remember { mutableStateOf<ModelQuotaLine?>(null) }
    var syncDialogUsage by remember { mutableStateOf<ProviderUsage?>(null) }
    var syncInputText by remember { mutableStateOf("") }
    var isLoginMarked by remember { mutableStateOf(MagicubeLedger.isLoginMarkedToday()) }
    var isAlibabaLinked by remember { mutableStateOf(MagicubeLedger.isAlibabaLinked) }

    val effectiveQuota = remember(quotaSnapshot, activeModel) {
        quotaSnapshot ?: QuotaCalculator.compute(activeModel)
    }

    LaunchedEffect(Unit) {
        onRefresh?.invoke()
    }

    if (unavailableDialogModel != null) {
        val model = unavailableDialogModel!!
        AlertDialog(
            onDismissRequest = { unavailableDialogModel = null },
            title = {
                Text(
                    text = "${model.modelName} ${stringResource(R.string.router_model_unavailable_title)}",
                    style = MinaType.Title.copy(fontSize = 16.sp),
                    color = colors.ink,
                )
            },
            text = {
                Text(
                    text = model.exhaustedReason ?: stringResource(R.string.router_status_unsupported),
                    style = MinaType.Body.copy(fontSize = 14.sp),
                    color = colors.ink2,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { unavailableDialogModel = null },
                    modifier = Modifier.testTag("model-unavailable-ok"),
                ) {
                    Text(stringResource(R.string.router_model_unavailable_ok))
                }
            },
        )
    }

    syncDialogUsage?.let {
        AlertDialog(
            onDismissRequest = { syncDialogUsage = null },
            title = { Text(stringResource(R.string.router_sync_balance_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.router_sync_balance_hint),
                        style = MinaType.Caption,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = syncInputText,
                        onValueChange = { syncInputText = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("sync-balance-input"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        syncInputText.toDoubleOrNull()?.let { MagicubeLedger.syncBalance(it) }
                        syncDialogUsage = null
                    },
                    modifier = Modifier.testTag("sync-balance-save"),
                ) {
                    Text(stringResource(R.string.router_sync_balance_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { syncDialogUsage = null }) {
                    Text(stringResource(R.string.router_sync_balance_cancel))
                }
            },
        )
    }

    val allModels = remember(connectedUsages) { connectedUsages.flatMap { it.models } }
    val filteredModels = remember(allModels, searchQuery, selectedFilter) {
        ModelPickerLogic.filterModels(allModels, searchQuery, selectedFilter)
    }
    val filteredEntries = remember(filteredModels) {
        val seen = HashMap<String, Int>(filteredModels.size)
        filteredModels.map { model ->
            val base = "filtered:${model.provider}:${model.modelId}"
            val count = (seen[base] ?: 0) + 1
            seen[base] = count
            IndexedFilteredModel(if (count == 1) base else "$base#$count", model)
        }
    }
    val usageModelById = remember(usages) {
        buildMap<String, ModelQuotaLine> {
            usages.forEach { u ->
                u.models.forEach { m -> putIfAbsent(m.modelId.lowercase(), m) }
            }
        }
    }
    val providerIdByModelId = remember(connectedUsages) {
        buildMap<String, String> {
            connectedUsages.forEach { u ->
                u.models.forEach { m -> putIfAbsent(m.modelId.lowercase(), u.providerId) }
            }
        }
    }
    val roleItems = remember(savedConfigs, savedRoles) {
        val mainCfg = savedConfigs.firstOrNull { it.id == savedRoles.main }
        val visionCfg = if (savedRoles.vision is RoleChoice.Model) {
            savedConfigs.firstOrNull { it.id == savedRoles.vision.configId }
        } else null
        val lightCfg = if (savedRoles.light is RoleChoice.Model) {
            savedConfigs.firstOrNull { it.id == savedRoles.light.configId }
        } else null
        listOfNotNull(
            mainCfg?.let { Triple(ModelRole.Main, it, MinaIcons.Spark) },
            visionCfg?.let { Triple(ModelRole.Vision, it, MinaIcons.Eye) },
            lightCfg?.let { Triple(ModelRole.Light, it, MinaIcons.Leaf) },
        )
    }
    val providerRows = remember(connectedUsages) {
        buildList {
            val seenProviders = HashMap<String, Int>()
            connectedUsages.forEachIndexed { uIdx, usage ->
                val pBase = "${usage.providerId}:${usage.provider}"
                val pCount = (seenProviders[pBase] ?: 0) + 1
                seenProviders[pBase] = pCount
                val pKey = if (pCount == 1) pBase else "$pBase#$pCount"
                add(SheetProviderRow.Header("ph:$pKey", usage, showTopDivider = uIdx > 0))
                if (usage.models.isNotEmpty()) {
                    val seenModels = HashMap<String, Int>(usage.models.size)
                    usage.models.forEachIndexed { mIdx, model ->
                        val mBase = model.modelId
                        val mCount = (seenModels[mBase] ?: 0) + 1
                        seenModels[mBase] = mCount
                        val mKey = if (mCount == 1) mBase else "$mBase#$mCount"
                        add(
                            SheetProviderRow.Model(
                                key = "pm:$pKey:$mKey",
                                usage = usage,
                                model = model,
                                showDivider = mIdx > 0,
                                isLastInProvider = mIdx == usage.models.lastIndex,
                            ),
                        )
                    }
                } else {
                    add(SheetProviderRow.Legacy("pl:$pKey", usage))
                }
            }
        }
    }

    fun selectAndClose(modelId: String, providerId: String = "") {
        prefStore.recordRecent(modelId)
        recents = prefStore.getRecentModels()
        when {
            onSelectModelWithProvider != null -> onSelectModelWithProvider(modelId, providerId)
            onSelectModel != null -> onSelectModel(modelId)
            else -> onPinClick()
        }
        onDismiss()
    }

    fun handleModelClick(model: ModelQuotaLine, providerId: String = "") {
        if (model.quotaState == QuotaState.Exhausted) {
            unavailableDialogModel = model
        } else {
            selectAndClose(model.modelId, providerId)
        }
    }

    fun handleToggleFavorite(modelId: String) {
        prefStore.toggleFavorite(modelId)
        favorites = prefStore.getFavorites()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        shape = SheetModalShape,
        modifier = modifier.testTag("router-bottom-sheet"),
    ) {
        val pinText = if (activeModel.isPinned) {
            stringResource(R.string.router_model_pinned)
        } else {
            stringResource(R.string.router_pin_model)
        }
        val showSearch = allModels.size > 4 || searchQuery.isNotBlank()
        val isSearchingOrFiltered = searchQuery.isNotBlank() || selectedFilter != ModelPickerFilter.All

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // Sheet Header: Title + Live Connection Pill
            item(key = "header", contentType = "header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.router_sheet_title),
                        style = MinaType.Title,
                        color = colors.ink,
                    )
                    ConnectionStatusPill(
                        status = connectionStatus,
                        onClick = {
                            when (connectionStatus) {
                                ConnectionStatus.SignInRequired -> onReconnectSignIn?.invoke()
                                ConnectionStatus.Error -> (onRetryConnect ?: onRefresh)?.invoke()
                                else -> onRefresh?.invoke()
                            }
                        },
                    )
                }
            }

            // Re-sign-in / Retry banner when session expired or network failed
            if (connectionStatus == ConnectionStatus.SignInRequired || connectionStatus == ConnectionStatus.Error) {
                item(key = "alert_banner", contentType = "alert_banner") {
                    Column {
                        ConnectionAlertBanner(
                            status = connectionStatus,
                            detail = connectionDetail,
                            onAction = {
                                if (connectionStatus == ConnectionStatus.SignInRequired) {
                                    onReconnectSignIn?.invoke()
                                } else {
                                    (onRetryConnect ?: onRefresh)?.invoke()
                                }
                            },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            // Live Quota & Active Model Hero Card
            item(key = "hero_card", contentType = "hero_card") {
                Column {
                    SheetQuotaHeroCard(
                        active = activeModel,
                        quota = effectiveQuota,
                        onPinClick = onPinClick,
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }

            // Compact Thinking / Effort Selector
            item(key = "thinking_bar", contentType = "thinking_bar") {
                Column {
                    ThinkingEffortBar(
                        selectedEffort = currentEffort,
                        onSelect = { effort ->
                            if (onSelectThinkingEffort != null) {
                                onSelectThinkingEffort(effort)
                            } else {
                                prefStore.setThinkingEffort(effort)
                            }
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                }
            }

            // Configured Roles Section (Settings › Models)
            if (roleItems.isNotEmpty()) {
                item(key = "roles_label", contentType = "group_label") {
                    GroupLabel(stringResource(R.string.router_configured_roles))
                }
                itemsIndexed(
                    items = roleItems,
                    key = { _, (role, _, _) -> "role:${role.name}" },
                    contentType = { _, _ -> "role_row" },
                ) { idx, (role, cfg, icon) ->
                    val shape = sheetCardShape(idx, roleItems.size)
                    val isCurrent = activeModel.id.equals(cfg.model, ignoreCase = true)
                    val isGemini = cfg.kind == ProviderConfig.Kind.GeminiWeb || cfg.name.contains("Gemini", ignoreCase = true)
                    val matchingLine = usageModelById[cfg.model.lowercase()]
                    val quotaBadge = when {
                        matchingLine?.remainingPercent != null -> QuotaSnapshot.formatPercent(matchingLine.remainingPercent)
                        isGemini -> stringResource(R.string.router_pro_plan)
                        matchingLine?.todayRequests != null && matchingLine.todayRequests > 0 ->
                            "${matchingLine.todayRequests} ${stringResource(R.string.router_today)}"
                        else -> stringResource(R.string.router_status_ready)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = if (idx == roleItems.lastIndex) 12.dp else 0.dp)
                            .clip(shape)
                            .background(colors.surface, shape),
                    ) {
                        if (idx > 0) {
                            HorizontalDivider(
                                color = colors.hair,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                        ConfiguredRoleRow(
                            role = role,
                            cfg = cfg,
                            icon = icon,
                            isCurrent = isCurrent,
                            quotaBadge = quotaBadge,
                            onClick = { selectAndClose(cfg.model, cfg.providerId) },
                        )
                    }
                }
                item(key = "roles_spacer", contentType = "spacer") {
                    Spacer(Modifier.height(14.dp))
                }
            }

            if (showSearch) {
                item(key = "search_bar", contentType = "search_bar") {
                    Column {
                        SheetSearchAndFilterBar(
                            query = searchQuery,
                            onQueryChange = { searchQuery = it },
                            selectedFilter = selectedFilter,
                            onFilterChange = { selectedFilter = it },
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            if (isSearchingOrFiltered) {
                item(key = "filtered_label", contentType = "group_label") {
                    GroupLabel("${stringResource(R.string.router_section_all)} (${filteredEntries.size})")
                }
                if (filteredEntries.isEmpty()) {
                    item(key = "empty_search", contentType = "empty_search") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .clip(SheetSingleCardShape)
                                .background(colors.surface, SheetSingleCardShape)
                                .border(0.5.dp, colors.edge, SheetSingleCardShape)
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.router_no_models_found),
                                style = MinaType.Caption,
                                color = colors.ink3,
                            )
                        }
                    }
                } else {
                    itemsIndexed(
                        items = filteredEntries,
                        key = { _, entry -> entry.key },
                        contentType = { _, _ -> "filtered_model" },
                    ) { index, entry ->
                        val model = entry.model
                        val shape = sheetCardShape(index, filteredEntries.size)
                        val matchedProviderId = providerIdByModelId[model.modelId.lowercase()].orEmpty()
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = if (index == filteredEntries.lastIndex) 12.dp else 0.dp)
                                .clip(shape)
                                .background(colors.surface, shape),
                        ) {
                            if (index > 0) {
                                HorizontalDivider(
                                    color = colors.hair,
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                            ModelQuotaRow(
                                model = model,
                                isFavorite = favorites.contains(model.modelId),
                                isPinned = activeModel.isPinned && activeModel.id.equals(model.modelId, ignoreCase = true),
                                isSelected = activeModel.id.equals(model.modelId, ignoreCase = true),
                                onToggleFavorite = { handleToggleFavorite(model.modelId) },
                                onClick = { handleModelClick(model, matchedProviderId) },
                            )
                        }
                    }
                }
            } else if (providerRows.isNotEmpty()) {
                item(key = "providers_label", contentType = "group_label") {
                    GroupLabel(stringResource(R.string.router_connected_providers))
                }
                itemsIndexed(
                    items = providerRows,
                    key = { _, row -> row.key },
                    contentType = { _, row -> row.contentType },
                ) { index, row ->
                    val shape = sheetCardShape(index, providerRows.size)
                    val bottomPad = if (index == providerRows.lastIndex) 12.dp else 0.dp
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = bottomPad)
                            .clip(shape)
                            .background(colors.surface, shape),
                    ) {
                        when (row) {
                            is SheetProviderRow.Header -> {
                                if (row.showTopDivider) {
                                    HorizontalDivider(
                                        color = colors.hair,
                                        thickness = 1.dp,
                                        modifier = Modifier.padding(horizontal = 14.dp),
                                    )
                                }
                                ProviderUsageHeader(
                                    usage = row.usage,
                                    isAlibabaLinked = isAlibabaLinked,
                                    isLoginMarked = isLoginMarked,
                                    onToggleAlibaba = { checked ->
                                        isAlibabaLinked = checked
                                        MagicubeLedger.setAlibabaLinked(checked)
                                    },
                                    onDailyLogin = {
                                        MagicubeLedger.recordDailyLogin(isAlibabaLinked = isAlibabaLinked)
                                        isLoginMarked = true
                                    },
                                    onOpenSync = {
                                        val currentBal = row.usage.magicubeBalance ?: MagicubeLedger.totalBalance()
                                        syncInputText = if (currentBal % 1.0 == 0.0) currentBal.toInt().toString() else currentBal.toString()
                                        syncDialogUsage = row.usage
                                    },
                                )
                            }
                            is SheetProviderRow.Model -> {
                                Column(
                                    Modifier.padding(
                                        start = 14.dp,
                                        end = 14.dp,
                                        bottom = if (row.isLastInProvider) 10.dp else 0.dp,
                                    ),
                                ) {
                                    if (row.showDivider) {
                                        HorizontalDivider(
                                            color = colors.hair,
                                            thickness = 0.5.dp,
                                            modifier = Modifier.padding(vertical = 4.dp),
                                        )
                                    }
                                    ModelQuotaRow(
                                        model = row.model,
                                        isFavorite = favorites.contains(row.model.modelId),
                                        isPinned = activeModel.isPinned && activeModel.id.equals(row.model.modelId, ignoreCase = true),
                                        isSelected = activeModel.id.equals(row.model.modelId, ignoreCase = true),
                                        onToggleFavorite = { handleToggleFavorite(row.model.modelId) },
                                        onClick = { handleModelClick(row.model, row.usage.providerId) },
                                    )
                                }
                            }
                            is SheetProviderRow.Legacy -> {
                                Box(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) {
                                    LegacyProviderUsageRow(usage = row.usage)
                                }
                            }
                        }
                    }
                }
            }

            // "Pin this model" button
            item(key = "pin_button", contentType = "action_button") {
                Column {
                    Spacer(Modifier.height(16.dp))
                    MinaButton(
                        text = pinText,
                        onClick = onPinClick,
                        kind = if (activeModel.isPinned) ButtonKind.Tonal else ButtonKind.Primary,
                        size = ButtonSize.Large,
                        enabled = activeModel.canPin,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin-model-button"),
                    )
                }
            }

            if (onOpenSettings != null) {
                item(key = "settings_button", contentType = "action_button") {
                    Column {
                        Spacer(Modifier.height(8.dp))
                        MinaButton(
                            text = stringResource(R.string.router_manage_in_settings),
                            onClick = {
                                onDismiss()
                                onOpenSettings()
                            },
                            kind = ButtonKind.Secondary,
                            size = ButtonSize.Large,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manage-models-button"),
                        )
                    }
                }
            }

            item(key = "bottom_spacer", contentType = "spacer") {
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun ConnectionStatusPill(
    status: ConnectionStatus,
    onClick: () -> Unit,
) {
    val c = MinaTheme.colors
    val (dotColor, bgColor, textColor, label) = when (status) {
        ConnectionStatus.Connected -> Quad(c.ok, c.okSoft, c.ok, stringResource(R.string.router_conn_connected))
        ConnectionStatus.Connecting -> Quad(c.warnSolid, c.warnSoft, c.warn, stringResource(R.string.router_conn_connecting))
        ConnectionStatus.SignInRequired -> Quad(c.bad, c.badSoft, c.bad, stringResource(R.string.router_signin_short))
        ConnectionStatus.Error -> Quad(c.bad, c.badSoft, c.bad, stringResource(R.string.router_retry_connect))
        ConnectionStatus.Idle -> Quad(c.ink3, c.fill, c.ink3, stringResource(R.string.router_status_ready))
    }
    Row(
        modifier = Modifier
            .clip(MinaShapes.Pill)
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp)
            .testTag("sheet-connection-pill"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor))
        Text(
            text = label,
            style = MinaType.Caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = textColor,
        )
    }
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

@Composable
private fun ConnectionAlertBanner(
    status: ConnectionStatus,
    detail: String?,
    onAction: () -> Unit,
) {
    val c = MinaTheme.colors
    val isSignIn = status == ConnectionStatus.SignInRequired
    val title = if (isSignIn) {
        stringResource(R.string.router_conn_signin_required)
    } else {
        stringResource(R.string.router_conn_error)
    }
    val actionLabel = if (isSignIn) {
        stringResource(R.string.router_resign_in)
    } else {
        stringResource(R.string.router_retry_connect)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isSignIn) c.warnSoft else c.badSoft)
            .border(1.dp, (if (isSignIn) c.warnSolid else c.bad).copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .clickable(onClick = onAction)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("reconnect-banner"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(
                text = title,
                style = MinaType.Body.copy(fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                color = if (isSignIn) c.warn else c.bad,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = MinaType.Caption.copy(fontSize = 11.5.sp),
                    color = c.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            modifier = Modifier
                .clip(MinaShapes.Pill)
                .background(if (isSignIn) c.warnSolid else c.bad)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(
                text = actionLabel,
                style = MinaType.Caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
                color = Color.White,
            )
        }
    }
}

@Composable
private fun SheetQuotaHeroCard(
    active: ActiveModel,
    quota: QuotaSnapshot,
    onPinClick: () -> Unit,
) {
    val c = MinaTheme.colors
    val hasModel = active.id.isNotBlank() && active.name != "No model"
    val barColor = when {
        !hasModel -> c.ink3
        quota.remainingPercent > 30.0 -> c.ok
        quota.remainingPercent > 10.0 -> c.warnSolid
        else -> c.bad
    }
    val pctFraction = (quota.remainingPercent / 100.0).toFloat().coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(HeroCardShape)
            .background(c.surface)
            .border(1.dp, c.hair, HeroCardShape)
            .padding(14.dp)
            .testTag("active-model-hero-card"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AiLogo(
                providerOrModel = active.id.ifBlank { active.name },
                size = 38.dp,
                corner = 10.dp,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = if (hasModel) active.name else stringResource(R.string.settings_not_set),
                    style = MinaType.Title.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 2.dp),
                ) {
                    val subLabel = listOfNotNull(
                        active.provider.takeIf { it.isNotBlank() },
                        quota.tierLabel.takeIf { it.isNotBlank() && !it.equals(active.provider, ignoreCase = true) },
                    ).joinToString(" · ")
                    if (subLabel.isNotBlank()) {
                        Text(
                            text = subLabel,
                            style = MinaType.Caption.copy(fontSize = 12.sp),
                            color = c.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (active.isPinned) {
                        Box(
                            modifier = Modifier
                                .clip(MinaShapes.Pill)
                                .background(c.accentSoft)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.router_model_pinned),
                                style = MinaType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                                color = c.accent,
                            )
                        }
                    }
                }
            }
            if (hasModel) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(MinaShapes.Pill)
                        .background(barColor.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                        .testTag("sheet-quota-percent"),
                ) {
                    Text(
                        text = quota.formattedPercent,
                        style = MinaType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        color = barColor,
                    )
                }
            }
        }

        if (hasModel) {
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(c.hair.copy(alpha = 0.45f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(pctFraction)
                        .clip(CircleShape)
                        .background(barColor),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val leftSummary = buildString {
                    if (quota.windowLabel.isNotBlank()) {
                        append(quota.windowLabel)
                        append(" · ")
                    }
                    append(stringResource(R.string.router_quota_left_pct, quota.formattedPercent))
                }
                Text(
                    text = leftSummary,
                    style = MinaType.Caption.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                    color = c.ink2,
                )
                PrimaryQuotaRightSummary(quota = quota)
            }

            if (quota.weeklyRemainingPercent != null) {
                Spacer(Modifier.height(8.dp))
                val weeklyFrac = (quota.weeklyRemainingPercent / 100.0).toFloat().coerceIn(0f, 1f)
                val weeklyColor = when {
                    quota.weeklyRemainingPercent > 30.0 -> c.ok
                    quota.weeklyRemainingPercent > 10.0 -> c.warnSolid
                    else -> c.bad
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.router_quota_weekly_left,
                            QuotaSnapshot.formatPercent(quota.weeklyRemainingPercent),
                        ),
                        style = MinaType.Caption.copy(fontSize = 11.5.sp),
                        color = c.ink3,
                    )
                    WeeklyResetCountdownText(resetsAtEpochMs = quota.weeklyResetsAtEpochMs)
                }
                Spacer(Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(c.hair.copy(alpha = 0.35f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(weeklyFrac)
                            .clip(CircleShape)
                            .background(weeklyColor),
                    )
                }
            }
        }
    }
}

@Composable
private fun PrimaryQuotaRightSummary(quota: QuotaSnapshot) {
    val c = MinaTheme.colors
    var nowMs by remember(quota.resetsAtEpochMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(quota.resetsAtEpochMs) {
        val target = quota.resetsAtEpochMs ?: return@LaunchedEffect
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            if (target <= current) break
            delay(1_000L - current % 1_000L)
        }
    }
    val resetCountdown = formatResetCountdown(quota.resetsAtEpochMs, nowMs)
    val rightSummary = when {
        resetCountdown != null -> stringResource(R.string.router_quota_resets_in, resetCountdown)
        quota.limitCount != null && quota.limitCount > 0 ->
            stringResource(R.string.router_quota_requests_ratio, quota.usedCount, quota.limitCount)
        else -> stringResource(R.string.router_quota_requests_used, quota.usedCount)
    }
    Text(
        text = rightSummary,
        style = MinaType.Caption.copy(fontSize = 12.sp),
        color = c.ink3,
    )
}

@Composable
private fun WeeklyResetCountdownText(resetsAtEpochMs: Long?) {
    val target = resetsAtEpochMs ?: return
    val c = MinaTheme.colors
    var nowMs by remember(target) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(target) {
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            if (target <= current) break
            delay(1_000L - current % 1_000L)
        }
    }
    val weeklyResetCountdown = formatResetCountdown(target, nowMs) ?: return
    Text(
        text = stringResource(R.string.router_quota_resets_in, weeklyResetCountdown),
        style = MinaType.Caption.copy(fontSize = 11.5.sp),
        color = c.ink3,
    )
}

@Composable
private fun ThinkingEffortBar(
    selectedEffort: String?,
    onSelect: (String?) -> Unit,
) {
    val c = MinaTheme.colors
    val normalizedSelected = when (selectedEffort?.trim()?.lowercase()) {
        "fast", "low" -> "low"
        "medium" -> "medium"
        "high", "thinking" -> "high"
        else -> null
    }
    val options = listOf(
        null to stringResource(R.string.router_effort_auto),
        "low" to stringResource(R.string.router_effort_fast),
        "medium" to stringResource(R.string.router_effort_medium),
        "high" to stringResource(R.string.router_effort_thinking),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(c.fill)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("thinking-effort-row"),
    ) {
        Text(
            text = stringResource(R.string.router_thinking_title),
            style = MinaType.Caption.copy(fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold),
            color = c.ink3,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (value, label) ->
                val active = normalizedSelected == value
                val keyTag = value ?: "auto"
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(MinaShapes.Pill)
                        .background(if (active) c.surface else Color.Transparent)
                        .border(
                            width = if (active) 1.dp else 0.5.dp,
                            color = if (active) c.accent.copy(alpha = 0.6f) else c.hair.copy(alpha = 0.7f),
                            shape = MinaShapes.Pill,
                        )
                        .clickable { onSelect(value) }
                        .padding(vertical = 7.dp)
                        .testTag("effort-$keyTag"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MinaType.Caption.copy(
                            fontSize = 12.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (active) c.accent else c.ink2,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetSearchAndFilterBar(
    query: String,
    onQueryChange: (String) -> Unit,
    selectedFilter: ModelPickerFilter,
    onFilterChange: (ModelPickerFilter) -> Unit,
) {
    val c = MinaTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SearchBarShape)
                .background(c.fill)
                .border(0.5.dp, c.hair, SearchBarShape)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MinaIcons.Search,
                contentDescription = null,
                tint = c.ink3,
                modifier = Modifier.size(16.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.router_search_hint),
                        style = MinaType.Caption.copy(fontSize = 13.sp),
                        color = c.ink3,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MinaType.Body.copy(fontSize = 13.5.sp, color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().testTag("router-search-field"),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = MinaIcons.Close,
                    contentDescription = "Clear",
                    tint = c.ink3,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChange("") },
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val filters = listOf(
                ModelPickerFilter.All to stringResource(R.string.router_filter_all),
                ModelPickerFilter.Free to stringResource(R.string.router_filter_free),
                ModelPickerFilter.Vision to stringResource(R.string.router_filter_vision),
                ModelPickerFilter.Tools to stringResource(R.string.router_filter_tools),
                ModelPickerFilter.LongContext to stringResource(R.string.router_filter_long_context),
            )
            filters.forEach { (filter, label) ->
                val selected = selectedFilter == filter
                Box(
                    modifier = Modifier
                        .clip(MinaShapes.Pill)
                        .background(if (selected) c.accentSoft else c.fill)
                        .border(
                            width = if (selected) 1.dp else 0.5.dp,
                            color = if (selected) c.accent.copy(alpha = 0.5f) else c.hair,
                            shape = MinaShapes.Pill,
                        )
                        .clickable { onFilterChange(filter) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                ) {
                    Text(
                        text = label,
                        style = MinaType.Caption.copy(
                            fontSize = 11.5.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (selected) c.accent else c.ink2,
                    )
                }
            }
        }
    }
}

private fun formatResetCountdown(resetsAtEpochMs: Long?, nowMs: Long = System.currentTimeMillis()): String? {
    val target = resetsAtEpochMs ?: return null
    val diffMs = target - nowMs
    if (diffMs <= 0L) return null
    val totalMinutes = (diffMs / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours >= 24 -> "${hours / 24}d ${hours % 24}h"
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m"
        else -> "< 1m"
    }
}

@Composable
fun CapabilityBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(MinaShapes.Pill)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MinaType.Caption.copy(
                fontWeight = FontWeight.Medium,
                fontSize = 10.sp,
            ),
            color = color,
        )
    }
}

@Composable
fun ConfiguredRoleRow(
    role: ModelRole,
    cfg: ProviderConfig,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isCurrent: Boolean,
    quotaBadge: String,
    onClick: () -> Unit,
) {
    val colors = MinaTheme.colors
    val roleName = when (role) {
        ModelRole.Main -> stringResource(R.string.router_role_main)
        ModelRole.Vision -> stringResource(R.string.router_role_vision)
        ModelRole.Light -> stringResource(R.string.router_role_light)
        else -> role.name
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(if (isCurrent) colors.accent.copy(alpha = 0.15f) else colors.neutralSoft, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isCurrent) colors.accent else colors.ink,
                modifier = Modifier.size(19.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = roleName,
                style = MinaType.Caption.copy(fontSize = 11.5.sp),
                color = colors.ink3,
            )
            Text(
                text = formatRouterModelName(cfg.model),
                style = MinaType.Body.copy(fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp),
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = cfg.name,
                style = MinaType.Caption.copy(fontSize = 12.sp),
                color = colors.ink2,
            )
        }
        CapabilityBadge(
            text = quotaBadge,
            color = if (quotaBadge.contains("Pro", ignoreCase = true)) colors.ok else colors.accent,
        )
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(colors.accent, CircleShape)
            )
        }
    }
}

@Composable
private fun ProviderUsageHeader(
    usage: ProviderUsage,
    isAlibabaLinked: Boolean,
    isLoginMarked: Boolean,
    onToggleAlibaba: (Boolean) -> Unit,
    onDailyLogin: () -> Unit,
    onOpenSync: () -> Unit,
) {
    val colors = MinaTheme.colors
    val isModelScope = usage.provider.equals("ModelScope", ignoreCase = true)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = usage.provider,
                        style = MinaType.Body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                    )
                    if (usage.isBadKey) {
                        Text(
                            text = " · ${stringResource(R.string.router_status_bad_key)}",
                            style = MinaType.Caption.copy(fontWeight = FontWeight.Medium),
                            color = colors.bad,
                        )
                    }
                }

                val formattedMeta = buildList {
                    if (usage.concurrency != null) {
                        add(stringResource(R.string.router_concurrency, usage.concurrency))
                    }
                    if (usage.magicubeBalance != null) {
                        val formattedBal = if (usage.magicubeBalance % 1.0 == 0.0) {
                            usage.magicubeBalance.toInt().toString()
                        } else {
                            String.format(java.util.Locale.US, "%.1f", usage.magicubeBalance)
                        }
                        add(stringResource(R.string.router_magicube_balance, formattedBal))
                    }
                    if (usage.totalCostToday != null) {
                        val formattedCost = if (usage.totalCostToday % 1.0 == 0.0) {
                            usage.totalCostToday.toInt().toString()
                        } else {
                            String.format(java.util.Locale.US, "%.1f", usage.totalCostToday)
                        }
                        add(stringResource(R.string.router_magicube_total, formattedCost))
                    }
                }
                if (formattedMeta.isNotEmpty()) {
                    Text(
                        text = formattedMeta.joinToString(" · "),
                        style = MinaType.Hint,
                        color = colors.ink3,
                    )
                }
            }
        }

        if (isModelScope) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.router_magicube_alibaba_linked),
                    style = MinaType.Body.copy(fontSize = 13.5.sp),
                    color = colors.ink,
                )
                MinaSwitch(
                    checked = isAlibabaLinked,
                    onCheckedChange = onToggleAlibaba,
                    modifier = Modifier.testTag("magicube-alibaba-toggle"),
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinaButton(
                    text = if (isLoginMarked) stringResource(R.string.router_magicube_login_done) else stringResource(R.string.router_magicube_login_btn),
                    onClick = onDailyLogin,
                    kind = if (isLoginMarked) ButtonKind.Tonal else ButtonKind.Secondary,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f).testTag("magicube-login-btn"),
                )
                MinaButton(
                    text = stringResource(R.string.router_sync_balance),
                    onClick = onOpenSync,
                    kind = ButtonKind.Tonal,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f).testTag("sync-balance-btn"),
                )
            }
        }
    }
}

@Composable
fun ProviderUsageBlock(
    usage: ProviderUsage,
    isFirst: Boolean,
    favorites: Set<String> = emptySet(),
    pinnedModelId: String? = null,
    activeModelId: String? = null,
    onToggleFavorite: (String) -> Unit = {},
    onModelClick: (ModelQuotaLine) -> Unit = {},
) {
    val colors = MinaTheme.colors
    val isModelScope = usage.provider.equals("ModelScope", ignoreCase = true)
    var showSyncDialog by remember { mutableStateOf(false) }
    var syncInputText by remember { mutableStateOf("") }
    var isLoginMarked by remember { mutableStateOf(MagicubeLedger.isLoginMarkedToday()) }
    var isAlibabaLinked by remember { mutableStateOf(MagicubeLedger.isAlibabaLinked) }

    if (showSyncDialog) {
        AlertDialog(
            onDismissRequest = { showSyncDialog = false },
            title = { Text(stringResource(R.string.router_sync_balance_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.router_sync_balance_hint),
                        style = MinaType.Caption,
                        color = colors.ink3,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = syncInputText,
                        onValueChange = { syncInputText = it },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("sync-balance-input"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        syncInputText.toDoubleOrNull()?.let { MagicubeLedger.syncBalance(it) }
                        showSyncDialog = false
                    },
                    modifier = Modifier.testTag("sync-balance-save"),
                ) {
                    Text(stringResource(R.string.router_sync_balance_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSyncDialog = false }) {
                    Text(stringResource(R.string.router_sync_balance_cancel))
                }
            },
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // Provider Header Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = usage.provider,
                        style = MinaType.Body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                    )
                    if (usage.isBadKey) {
                        Text(
                            text = " · ${stringResource(R.string.router_status_bad_key)}",
                            style = MinaType.Caption.copy(fontWeight = FontWeight.Medium),
                            color = colors.bad,
                        )
                    }
                }

                val metaTexts = mutableListOf<String>()
                if (usage.concurrency != null) {
                    metaTexts.add(stringResource(R.string.router_concurrency, usage.concurrency))
                }
                if (usage.magicubeBalance != null) {
                    val formattedBal = if (usage.magicubeBalance % 1.0 == 0.0) {
                        usage.magicubeBalance.toInt().toString()
                    } else {
                        String.format(java.util.Locale.US, "%.1f", usage.magicubeBalance)
                    }
                    metaTexts.add(stringResource(R.string.router_magicube_balance, formattedBal))
                }
                if (usage.totalCostToday != null) {
                    val formattedCost = if (usage.totalCostToday % 1.0 == 0.0) {
                        usage.totalCostToday.toInt().toString()
                    } else {
                        String.format(java.util.Locale.US, "%.1f", usage.totalCostToday)
                    }
                    metaTexts.add(stringResource(R.string.router_magicube_total, formattedCost))
                }
                if (metaTexts.isNotEmpty()) {
                    Text(
                        text = metaTexts.joinToString(" · "),
                        style = MinaType.Hint,
                        color = colors.ink3,
                    )
                }
            }
        }

        if (isModelScope) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.router_magicube_alibaba_linked),
                    style = MinaType.Body.copy(fontSize = 13.5.sp),
                    color = colors.ink,
                )
                MinaSwitch(
                    checked = isAlibabaLinked,
                    onCheckedChange = { checked ->
                        isAlibabaLinked = checked
                        MagicubeLedger.setAlibabaLinked(checked)
                    },
                    modifier = Modifier.testTag("magicube-alibaba-toggle"),
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinaButton(
                    text = if (isLoginMarked) stringResource(R.string.router_magicube_login_done) else stringResource(R.string.router_magicube_login_btn),
                    onClick = {
                        MagicubeLedger.recordDailyLogin(isAlibabaLinked = isAlibabaLinked)
                        isLoginMarked = true
                    },
                    kind = if (isLoginMarked) ButtonKind.Tonal else ButtonKind.Secondary,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f).testTag("magicube-login-btn"),
                )
                MinaButton(
                    text = stringResource(R.string.router_sync_balance),
                    onClick = {
                        val currentBal = usage.magicubeBalance ?: MagicubeLedger.totalBalance()
                        syncInputText = if (currentBal % 1.0 == 0.0) currentBal.toInt().toString() else currentBal.toString()
                        showSyncDialog = true
                    },
                    kind = ButtonKind.Tonal,
                    size = ButtonSize.Small,
                    modifier = Modifier.weight(1f).testTag("sync-balance-btn"),
                )
            }
        }

        Spacer(Modifier.height(6.dp))

        if (usage.models.isNotEmpty()) {
            usage.models.forEachIndexed { index, model ->
                if (index > 0) {
                    HorizontalDivider(
                        color = colors.hair,
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(vertical = 4.dp),
                    )
                }
                ModelQuotaRow(
                    model = model,
                    isFavorite = favorites.contains(model.modelId),
                    isPinned = pinnedModelId?.equals(model.modelId, ignoreCase = true) == true,
                    isSelected = activeModelId?.equals(model.modelId, ignoreCase = true) == true,
                    onToggleFavorite = { onToggleFavorite(model.modelId) },
                    onClick = { onModelClick(model) },
                )
            }
        } else {
            LegacyProviderUsageRow(usage = usage)
        }
    }
}

@Composable
fun ModelQuotaRow(
    model: ModelQuotaLine,
    modifier: Modifier = Modifier,
    isFavorite: Boolean = false,
    isPinned: Boolean = false,
    isSelected: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    onClick: () -> Unit = {},
) {
    val colors = MinaTheme.colors
    val estimateLabel = stringResource(R.string.router_estimate)

    val requestsText = if (model.limit != null) {
        "${model.todayRequests}/${model.limit}"
    } else if (model.isEstimate) {
        "${model.todayRequests} ($estimateLabel)"
    } else {
        "${model.todayRequests}"
    }

    val subtitle = if (model.costPerCall != null) {
        val formattedCost = if (model.costPerCall % 1.0 == 0.0) {
            model.costPerCall.toInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", model.costPerCall)
        }
        "$requestsText · " + stringResource(R.string.router_magicube_cost, formattedCost)
    } else {
        requestsText
    }

    val (statusText, statusColor) = when (model.quotaState) {
        QuotaState.Ready -> {
            val pct = model.remainingPercent
            if (pct != null) {
                val pctColor = when {
                    pct > 30.0 -> colors.ok
                    pct > 10.0 -> colors.warnSolid
                    else -> colors.bad
                }
                QuotaSnapshot.formatPercent(pct) to pctColor
            } else {
                stringResource(R.string.router_quota_ready) to colors.ok
            }
        }
        QuotaState.Low -> {
            val pct = model.remainingPercent
            val label = if (pct != null) QuotaSnapshot.formatPercent(pct) else stringResource(R.string.router_quota_low)
            label to colors.warnSolid
        }
        QuotaState.Exhausted -> {
            if (model.cooldownMinutes > 0) {
                stringResource(R.string.router_status_cooldown, model.cooldownMinutes) to colors.bad
            } else if (model.status == ModelStatusKind.BadKey) {
                stringResource(R.string.router_status_bad_key) to colors.bad
            } else if (model.status == ModelStatusKind.NoBalance) {
                "No balance" to colors.bad
            } else {
                stringResource(R.string.router_quota_exhausted) to colors.bad
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ModelRowShape)
            .background(if (isSelected) colors.accentSoft.copy(alpha = 0.55f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Rank badge if available
        model.rank?.let { rankNum ->
            Box(
                modifier = Modifier
                    .clip(RankBadgeShape)
                    .background(if (rankNum <= 3) colors.accent else colors.hair.copy(alpha = 0.8f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "#$rankNum",
                    style = MinaType.Caption.copy(
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    color = if (rankNum <= 3) Color.White else colors.ink2,
                )
            }
            Spacer(Modifier.size(8.dp))
        }

        // Official AI Logo
        AiLogo(
            providerOrModel = model.modelId,
            size = 30.dp,
            corner = 8.dp,
        )

        Spacer(Modifier.size(10.dp))

        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.modelName.ifBlank { model.modelId },
                    style = MinaType.Body.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isPinned) {
                    Spacer(Modifier.size(4.dp))
                    Box(
                        modifier = Modifier
                            .clip(MinaShapes.Pill)
                            .background(colors.accent.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.router_model_pinned),
                            style = MinaType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold),
                            color = colors.accent,
                        )
                    }
                }
            }

            // Provider chip and subtitle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 1.dp),
            ) {
                if (model.provider.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .clip(MinaShapes.Pill)
                            .background(colors.hair.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            text = model.provider,
                            style = MinaType.Caption.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                            color = colors.ink2,
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MinaType.Hint.copy(fontSize = 12.sp),
                    color = colors.ink3,
                )
            }

            // Capability Badges
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Free / Paid badge
                CapabilityBadge(
                    text = if (model.isFree) stringResource(R.string.router_badge_free) else stringResource(R.string.router_badge_paid),
                    color = if (model.isFree) colors.ok else colors.ink3,
                )
                // Vision badge
                if (model.supportsVision) {
                    CapabilityBadge(
                        text = stringResource(R.string.router_badge_vision),
                        color = colors.accent,
                    )
                }
                // Tools badge
                if (model.supportsTools) {
                    CapabilityBadge(
                        text = stringResource(R.string.router_badge_tools),
                        color = colors.accent,
                    )
                }
                // Long context badge
                if (model.contextTokens >= ModelPickerLogic.LONG_CONTEXT_THRESHOLD) {
                    CapabilityBadge(
                        text = ModelPickerLogic.formatContextTokens(model.contextTokens),
                        color = colors.ink2,
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Quota Status Pill
            Box(
                modifier = Modifier
                    .clip(MinaShapes.Pill)
                    .background(statusColor.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = statusText,
                    style = MinaType.Caption.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp,
                    ),
                    color = statusColor,
                )
            }

            if (isSelected) {
                Icon(
                    imageVector = MinaIcons.Check,
                    contentDescription = "Selected",
                    tint = colors.accent,
                    modifier = Modifier.size(16.dp),
                )
            }

            // Star Button
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isFavorite) MinaIcons.StarFilled else MinaIcons.Star,
                    contentDescription = "Favorite",
                    tint = if (isFavorite) colors.accent else colors.ink3,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun LegacyProviderUsageRow(
    usage: ProviderUsage,
) {
    val colors = MinaTheme.colors

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val quotaText = if (usage.totalQuota > 0) {
            stringResource(R.string.router_quota_format, usage.remainingQuota, usage.totalQuota)
        } else {
            stringResource(R.string.router_today_count, usage.todayCount)
        }
        Text(
            text = quotaText,
            style = MinaType.Hint,
            color = colors.ink3,
        )

        CooldownCountdownText(cooldownUntilMs = usage.cooldownUntilMs)
    }
}

@Composable
private fun CooldownCountdownText(cooldownUntilMs: Long) {
    val colors = MinaTheme.colors
    var nowMs by remember(cooldownUntilMs) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldownUntilMs) {
        while (cooldownUntilMs > nowMs) {
            val current = System.currentTimeMillis()
            nowMs = current
            if (cooldownUntilMs <= current) break
            delay(1_000L)
        }
    }
    val isCooldown = cooldownUntilMs > nowMs
    val cooldownSeconds = if (isCooldown) ((cooldownUntilMs - nowMs) / 1000L).coerceAtLeast(1L) else 0L
    val cooldownText = if (isCooldown) {
        stringResource(R.string.router_cooldown_active, "${cooldownSeconds}s")
    } else {
        stringResource(R.string.router_no_cooldown)
    }
    val cooldownColor = if (isCooldown) colors.warn else colors.ok

    Text(
        text = cooldownText,
        style = MinaType.Caption.copy(fontWeight = FontWeight.Medium),
        color = cooldownColor,
    )
}
