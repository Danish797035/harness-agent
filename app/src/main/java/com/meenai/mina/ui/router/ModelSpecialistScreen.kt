package com.meenai.mina.ui.router

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.R
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.ui.components.AiBrand
import com.meenai.mina.ui.components.AiLogo
import com.meenai.mina.ui.components.CapabilityTag
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.TagTone
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme

@Composable
fun ModelSpecialistScreen(
    store: ProviderStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefsStore = remember { ModelPreferencesStore.get(context) }
    val routerStatus = LocalRouterStatus.current

    var specialistAuto by remember { mutableStateOf(prefsStore.isSpecialistAutoEnabled()) }
    var specialistMode by remember { mutableStateOf(prefsStore.getSpecialistMode()) }
    var autoStrategy by remember { mutableStateOf(prefsStore.getAutoStrategy()) }
    var freeOnly by remember { mutableStateOf(prefsStore.isFreeOnly()) }
    var failoverEnabled by remember { mutableStateOf(prefsStore.isFailoverEnabled()) }
    var maxSwitches by remember { mutableStateOf(prefsStore.getMaxSwitches()) }
    var preferredProviders by remember { mutableStateOf(prefsStore.getPreferredProviders()) }

    val activeModel by routerStatus.current.collectAsState()
    val usages by routerStatus.usage.collectAsState()
    val allModels = remember(usages) {
        usages.flatMap { it.models }.sortedBy { it.rank ?: 999 }
    }

    PageScaffold(stringResource(R.string.model_specialist_title), onBack) {
        // 1. Top Specialist Auto Master Toggle
        GroupCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.model_specialist_title),
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = MinaTheme.colors.ink,
                    )
                    Text(
                        text = stringResource(R.string.model_specialist_sub),
                        fontSize = 13.sp,
                        color = MinaTheme.colors.ink2,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = specialistAuto,
                    onCheckedChange = {
                        specialistAuto = it
                        prefsStore.setSpecialistAutoEnabled(it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MinaTheme.colors.tileSuccessText,
                        checkedTrackColor = MinaTheme.colors.tileSuccessBg,
                    ),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // 2. Mode Selector (Auto vs Manual)
        val tabIndex = if (specialistMode == SpecialistMode.Auto) 0 else 1
        TabRow(
            selectedTabIndex = tabIndex,
            containerColor = MinaTheme.colors.cardBg,
            contentColor = MinaTheme.colors.ink,
            indicator = { tabPositions ->
                TabRowDefaults.SecondaryIndicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[tabIndex]),
                    color = MinaTheme.colors.tileSuccessText,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp)),
        ) {
            Tab(
                selected = specialistMode == SpecialistMode.Auto,
                onClick = {
                    specialistMode = SpecialistMode.Auto
                    prefsStore.setSpecialistMode(SpecialistMode.Auto)
                },
                text = {
                    Text(
                        stringResource(R.string.router_mode_auto),
                        fontWeight = if (specialistMode == SpecialistMode.Auto) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
            Tab(
                selected = specialistMode == SpecialistMode.Manual,
                onClick = {
                    specialistMode = SpecialistMode.Manual
                    prefsStore.setSpecialistMode(SpecialistMode.Manual)
                },
                text = {
                    Text(
                        stringResource(R.string.router_mode_manual),
                        fontWeight = if (specialistMode == SpecialistMode.Manual) FontWeight.Bold else FontWeight.Normal,
                    )
                },
            )
        }

        Spacer(Modifier.height(16.dp))

        // 3. Top Hero Card (Active Model + Why Chosen + Quota status)
        ActiveModelHeroCard(
            active = activeModel,
            onPinClick = {
                val nextPin = if (activeModel.isPinned) null else activeModel.id
                routerStatus.pinModel(nextPin)
            },
        )

        Spacer(Modifier.height(16.dp))

        // 4. Auto Mode Strategy OR Manual Mode Role Pickers
        if (specialistMode == SpecialistMode.Auto) {
            GroupLabel(stringResource(R.string.router_mode_auto))
            GroupCard {
                // Radio option 1: Free only
                StrategyRadioRow(
                    label = stringResource(R.string.router_strategy_free_only),
                    sub = stringResource(R.string.router_free_only_sub),
                    selected = autoStrategy == AutoStrategy.FreeOnly,
                    onClick = {
                        autoStrategy = AutoStrategy.FreeOnly
                        prefsStore.setAutoStrategy(AutoStrategy.FreeOnly)
                        freeOnly = true
                        prefsStore.setFreeOnly(true)
                    },
                )
                // Radio option 2: Best quality
                StrategyRadioRow(
                    label = stringResource(R.string.router_strategy_best_quality),
                    sub = "Leaderboard top-ranked models for maximum quality",
                    selected = autoStrategy == AutoStrategy.BestQuality,
                    onClick = {
                        autoStrategy = AutoStrategy.BestQuality
                        prefsStore.setAutoStrategy(AutoStrategy.BestQuality)
                    },
                )
                // Radio option 3: Fastest
                StrategyRadioRow(
                    label = stringResource(R.string.router_strategy_fastest),
                    sub = "Lightweight candidates optimized for lowest latency",
                    selected = autoStrategy == AutoStrategy.Fastest,
                    onClick = {
                        autoStrategy = AutoStrategy.Fastest
                        prefsStore.setAutoStrategy(AutoStrategy.Fastest)
                    },
                )
            }
        } else {
            // Manual Mode: 5 Role Dropdowns
            GroupLabel(stringResource(R.string.router_roles_title))
            GroupCard {
                ManualRolePickerRow(
                    roleName = stringResource(R.string.router_role_main),
                    roleKey = "main",
                    allModels = allModels,
                    prefsStore = prefsStore,
                )
                ManualRolePickerRow(
                    roleName = stringResource(R.string.router_role_coding),
                    roleKey = "coding",
                    allModels = allModels,
                    prefsStore = prefsStore,
                )
                ManualRolePickerRow(
                    roleName = stringResource(R.string.router_role_light),
                    roleKey = "light",
                    allModels = allModels,
                    prefsStore = prefsStore,
                )
                ManualRolePickerRow(
                    roleName = stringResource(R.string.router_role_vision),
                    roleKey = "vision",
                    allModels = allModels,
                    prefsStore = prefsStore,
                )
                ManualRolePickerRow(
                    roleName = stringResource(R.string.router_role_long),
                    roleKey = "long",
                    allModels = allModels,
                    prefsStore = prefsStore,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // 5. Customizations Section
        GroupLabel(stringResource(R.string.router_customizations_title))
        GroupCard {
            // Free-only toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.router_free_only_title),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = MinaTheme.colors.ink,
                    )
                    Text(
                        stringResource(R.string.router_free_only_sub),
                        fontSize = 13.sp,
                        color = MinaTheme.colors.ink2,
                    )
                }
                Switch(
                    checked = freeOnly,
                    onCheckedChange = {
                        freeOnly = it
                        prefsStore.setFreeOnly(it)
                    },
                )
            }

            // Failover toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.router_failover_title),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = MinaTheme.colors.ink,
                    )
                    Text(
                        stringResource(R.string.router_failover_sub),
                        fontSize = 13.sp,
                        color = MinaTheme.colors.ink2,
                    )
                }
                Switch(
                    checked = failoverEnabled,
                    onCheckedChange = {
                        failoverEnabled = it
                        prefsStore.setFailoverEnabled(it)
                    },
                )
            }

            // Max switches per task (Segmented Choice: 1 / 2 / 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.router_max_switches_title),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MinaTheme.colors.ink,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 3).forEach { count ->
                        FilterChip(
                            selected = maxSwitches == count,
                            onClick = {
                                maxSwitches = count
                                prefsStore.setMaxSwitches(count)
                            },
                            label = { Text("$count") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MinaTheme.colors.tileSuccessBg,
                                selectedLabelColor = MinaTheme.colors.tileSuccessText,
                            ),
                        )
                    }
                }
            }

            // Preferred Provider Order
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    stringResource(R.string.router_preferred_providers_title),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = MinaTheme.colors.ink,
                )
                Text(
                    "Priority ordering across configured providers",
                    fontSize = 12.sp,
                    color = MinaTheme.colors.ink2,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    preferredProviders.forEachIndexed { idx, pName ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(MinaTheme.colors.tileBg, RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "#${idx + 1} ",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MinaTheme.colors.ink2,
                            )
                            AiLogo(AiBrand.detect(pName), size = 14.dp)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = pName.replaceFirstChar { it.uppercase() },
                                fontSize = 13.sp,
                                color = MinaTheme.colors.ink,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 6. Recent Switches Compact Timeline
        GroupLabel(stringResource(R.string.router_timeline_title))
        GroupCard {
            val statusEvents = remember(activeModel) {
                routerStatus.switchHistory.takeLast(4).reversed()
            }
            if (statusEvents.isEmpty()) {
                Text(
                    text = stringResource(R.string.router_timeline_empty),
                    fontSize = 13.sp,
                    color = MinaTheme.colors.ink2,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                statusEvents.forEach { event ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MinaTheme.colors.tileSuccessText),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "${if (event.fromModel.isNotEmpty()) event.fromModel else "Auto"} → ${event.toModel}",
                                    fontSize = 13.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MinaTheme.colors.ink,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = event.toProvider.ifEmpty { event.fromProvider },
                                    fontSize = 11.sp,
                                    color = MinaTheme.colors.tileSuccessText,
                                    modifier = Modifier
                                        .background(MinaTheme.colors.tileSuccessBg, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 4.dp, vertical = 1.dp),
                                )
                            }
                            Text(
                                text = event.reason,
                                fontSize = 12.sp,
                                color = MinaTheme.colors.ink2,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        GroupLabel(stringResource(R.string.router_models_list_title))
        allModels.forEachIndexed { index, modelLine ->
            ModelCardItem(
                model = modelLine,
                rank = modelLine.rank ?: (index + 1),
                isPinned = activeModel.isPinned && activeModel.id == modelLine.modelId,
                onClick = {
                    val nextPin = if (activeModel.isPinned && activeModel.id == modelLine.modelId) null else modelLine.modelId
                    routerStatus.pinModel(nextPin)
                },
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StrategyRadioRow(
    label: String,
    sub: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(
                selectedColor = MinaTheme.colors.tileSuccessText,
            ),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MinaTheme.colors.ink,
            )
            Text(
                text = sub,
                fontSize = 13.sp,
                color = MinaTheme.colors.ink2,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun ManualRolePickerRow(
    roleName: String,
    roleKey: String,
    allModels: List<ModelQuotaLine>,
    prefsStore: ModelPreferencesStore,
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf(prefsStore.getManualRole(roleKey)) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = roleName,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MinaTheme.colors.ink,
            )
            Text(
                text = selectedModelId ?: "Default (Auto)",
                fontSize = 13.sp,
                color = if (selectedModelId != null) MinaTheme.colors.tileSuccessText else MinaTheme.colors.ink2,
            )
        }

        Box {
            Icon(
                imageVector = MinaIcons.ChevronDown,
                contentDescription = null,
                tint = MinaTheme.colors.ink2,
                modifier = Modifier.size(16.dp),
            )

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text("Default (Auto)") },
                    onClick = {
                        selectedModelId = null
                        prefsStore.setManualRole(roleKey, null)
                        expanded = false
                    },
                )
                allModels.forEach { line ->
                    DropdownMenuItem(
                        leadingIcon = { AiLogo(AiBrand.detect(line.modelId), size = 18.dp) },
                        text = {
                            Text(
                                text = line.modelName,
                                fontWeight = if (line.modelId == selectedModelId) FontWeight.Bold else FontWeight.Normal,
                            )
                        },
                        trailingIcon = {
                            line.rank?.let { r ->
                                Text("#$r", fontSize = 11.sp, color = MinaTheme.colors.ink2)
                            }
                        },
                        onClick = {
                            selectedModelId = line.modelId
                            prefsStore.setManualRole(roleKey, line.modelId)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}
