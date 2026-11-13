package com.meenai.mina.ui.router

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Bottom sheet opened upon tapping the router chip:
 * - Current model & why chosen ("abhi ka model, kyun chuna")
 * - Search bar & Capability filter chips (Free, Vision, Tools, Long Context)
 * - Favourites & Recent models
 * - Each provider's today quota, capability badges & cooldown
 * - Direct tap to select/pin model, with warning dialog for exhausted models
 * - "Pin this model" button (disabled when no candidates available)
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
    preferencesStore: ModelPreferencesStore? = null,
) {
    val colors = MinaTheme.colors
    val context = LocalContext.current
    val prefStore = preferencesStore ?: remember { ModelPreferencesStore.get(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(ModelPickerFilter.All) }
    var favorites by remember { mutableStateOf(prefStore.getFavorites()) }
    var recents by remember { mutableStateOf(prefStore.getRecentModels()) }
    var unavailableDialogModel by remember { mutableStateOf<ModelQuotaLine?>(null) }

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

    val allModels = remember(usages) { usages.flatMap { it.models } }
    val filteredModels = remember(allModels, searchQuery, selectedFilter) {
        ModelPickerLogic.filterModels(allModels, searchQuery, selectedFilter)
    }

    fun handleModelClick(model: ModelQuotaLine) {
        if (model.quotaState == QuotaState.Exhausted) {
            unavailableDialogModel = model
        } else {
            prefStore.recordRecent(model.modelId)
            recents = prefStore.getRecentModels()
            if (onSelectModel != null) {
                onSelectModel(model.modelId)
            } else {
                onPinClick()
            }
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
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        modifier = modifier.testTag("router-bottom-sheet"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.router_sheet_title),
                style = MinaType.Title,
                color = colors.ink,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            // Current Model & Selection Reason
            GroupCard {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.router_current_model),
                            style = MinaType.Hint,
                            color = colors.ink3,
                        )
                        if (activeModel.isPinned) {
                            Box(
                                modifier = Modifier
                                    .clip(MinaShapes.Pill)
                                    .background(colors.accent.copy(alpha = 0.15f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.router_model_pinned),
                                    style = MinaType.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                                    color = colors.accent,
                                )
                            }
                        }
                    }

                    Text(
                        text = if (activeModel.provider.isNotBlank()) "${activeModel.name} · ${activeModel.provider}" else activeModel.name,
                        style = MinaType.Body.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.ink,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )

                    if (activeModel.reason.isNotBlank()) {
                        Text(
                            text = stringResource(R.string.router_why_chosen),
                            style = MinaType.Hint,
                            color = colors.ink3,
                        )
                        Text(
                            text = activeModel.reason,
                            style = MinaType.Caption,
                            color = colors.ink2,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        text = stringResource(R.string.router_search_hint),
                        style = MinaType.Hint,
                        color = colors.ink3,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = MinaIcons.Search,
                        contentDescription = null,
                        tint = colors.ink3,
                        modifier = Modifier.size(18.dp),
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { searchQuery = "" }
                                .padding(4.dp),
                        ) {
                            Icon(
                                imageVector = MinaIcons.Close,
                                contentDescription = "Clear",
                                tint = colors.ink3,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                },
                singleLine = true,
                shape = MinaShapes.Pill,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("router-search-input"),
            )

            Spacer(Modifier.height(10.dp))

            // Filter chips row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelPickerFilter.values().forEach { filter ->
                    val label = when (filter) {
                        ModelPickerFilter.All -> stringResource(R.string.router_filter_all)
                        ModelPickerFilter.Free -> stringResource(R.string.router_filter_free)
                        ModelPickerFilter.Vision -> stringResource(R.string.router_filter_vision)
                        ModelPickerFilter.Tools -> stringResource(R.string.router_filter_tools)
                        ModelPickerFilter.LongContext -> stringResource(R.string.router_filter_long_context)
                    }
                    val isSelected = selectedFilter == filter
                    Box(
                        modifier = Modifier
                            .testTag("filter-chip-${filter.name.lowercase()}")
                            .clip(MinaShapes.Pill)
                            .background(if (isSelected) colors.accent.copy(alpha = 0.15f) else colors.surface)
                            .border(
                                width = 1.dp,
                                color = if (isSelected) colors.accent else colors.hair,
                                shape = MinaShapes.Pill,
                            )
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            style = MinaType.Caption.copy(
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                fontSize = 12.sp,
                            ),
                            color = if (isSelected) colors.accent else colors.ink2,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            val isSearchingOrFiltered = searchQuery.isNotBlank() || selectedFilter != ModelPickerFilter.All

            if (isSearchingOrFiltered) {
                GroupLabel("${stringResource(R.string.router_section_all)} (${filteredModels.size})")
                GroupCard {
                    if (filteredModels.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.router_no_models_found),
                                style = MinaType.Caption,
                                color = colors.ink3,
                            )
                        }
                    } else {
                        filteredModels.forEachIndexed { index, model ->
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
                                isPinned = activeModel.isPinned && activeModel.id == model.modelId,
                                onToggleFavorite = { handleToggleFavorite(model.modelId) },
                                onClick = { handleModelClick(model) },
                            )
                        }
                    }
                }
            } else {
                // Favourites section
                val favoriteModels = allModels.filter { favorites.contains(it.modelId) }
                if (favoriteModels.isNotEmpty()) {
                    GroupLabel(stringResource(R.string.router_section_favorites))
                    GroupCard {
                        favoriteModels.forEachIndexed { index, model ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = colors.hair,
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                            ModelQuotaRow(
                                model = model,
                                isFavorite = true,
                                isPinned = activeModel.isPinned && activeModel.id == model.modelId,
                                onToggleFavorite = { handleToggleFavorite(model.modelId) },
                                onClick = { handleModelClick(model) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Recent models section (excluding those already in favorites)
                val recentModels = recents.filterNot { favorites.contains(it) }.mapNotNull { rId ->
                    allModels.find { it.modelId == rId }
                }
                if (recentModels.isNotEmpty()) {
                    GroupLabel(stringResource(R.string.router_section_recent))
                    GroupCard {
                        recentModels.forEachIndexed { index, model ->
                            if (index > 0) {
                                HorizontalDivider(
                                    color = colors.hair,
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 14.dp),
                                )
                            }
                            ModelQuotaRow(
                                model = model,
                                isFavorite = false,
                                isPinned = activeModel.isPinned && activeModel.id == model.modelId,
                                onToggleFavorite = { handleToggleFavorite(model.modelId) },
                                onClick = { handleModelClick(model) },
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                // Providers Quota & Limits
                GroupLabel(stringResource(R.string.router_provider_quotas))
                GroupCard {
                    usages.forEachIndexed { index, usage ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = colors.hair,
                                thickness = 1.dp,
                                modifier = Modifier.padding(horizontal = 14.dp),
                            )
                        }
                        ProviderUsageBlock(
                            usage = usage,
                            isFirst = index == 0,
                            favorites = favorites,
                            pinnedModelId = if (activeModel.isPinned) activeModel.id else null,
                            onToggleFavorite = { handleToggleFavorite(it) },
                            onModelClick = { handleModelClick(it) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            // "Pin this model" button
            val pinText = if (activeModel.isPinned) {
                stringResource(R.string.router_model_pinned)
            } else {
                stringResource(R.string.router_pin_model)
            }

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

            Spacer(Modifier.height(8.dp))
        }
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
fun ProviderUsageBlock(
    usage: ProviderUsage,
    isFirst: Boolean,
    favorites: Set<String> = emptySet(),
    pinnedModelId: String? = null,
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
                    isPinned = pinnedModelId == model.modelId,
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
        QuotaState.Ready -> stringResource(R.string.router_quota_ready) to colors.ok
        QuotaState.Low -> stringResource(R.string.router_quota_low) to colors.warn
        QuotaState.Exhausted -> {
            if (model.cooldownMinutes > 0) {
                stringResource(R.string.router_status_cooldown, model.cooldownMinutes) to colors.bad
            } else if (model.status == ModelStatusKind.BadKey) {
                stringResource(R.string.router_status_bad_key) to colors.bad
            } else {
                stringResource(R.string.router_quota_exhausted) to colors.bad
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
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

            Text(
                text = if (model.provider.isNotBlank()) "${model.provider} · $subtitle" else subtitle,
                style = MinaType.Hint.copy(fontSize = 12.sp),
                color = colors.ink3,
                modifier = Modifier.padding(top = 1.dp),
            )

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
    val now = remember { System.currentTimeMillis() }
    val isCooldown = usage.cooldownUntilMs > now
    val cooldownSeconds by remember(usage.cooldownUntilMs, now) {
        derivedStateOf {
            if (isCooldown) ((usage.cooldownUntilMs - now) / 1000L).coerceAtLeast(1L) else 0L
        }
    }

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
}
