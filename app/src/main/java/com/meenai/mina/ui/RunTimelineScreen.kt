package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.trPlural
import androidx.compose.ui.res.stringResource
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import com.meenai.mina.ui.components.AiLogo
import com.meenai.mina.ui.theme.MinaShapes
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.components.EmptyNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.StepItem
import com.meenai.mina.ui.components.StepState
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.components.ValueTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 一次运行的时间线(只读):顶部是目标、标准、一行小结(点开看时间去向)和结局(模型调用次数这类细节收在「更多详情」里),
 * 下面是竖线步骤,每步一行,点有画面的步骤看当时的画面。
 */
/** 任务还在跑时,运行记录多久重读一次。 */
private const val LIVE_REFRESH_MS = 1_000L

@Composable
fun RunTimelineScreen(vm: RunTimelineViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    // 还在跑就每秒再读一次运行记录(不自动滚到底:用户翻到哪就停在哪)
    val ui by produceState<TimelineUi>(TimelineUi.Loading, vm) {
        while (true) {
            val next = withContext(Dispatchers.IO) { vm.load() }
            value = next
            if ((next as? TimelineUi.Ready)?.header?.running != true) break
            delay(LIVE_REFRESH_MS)
        }
    }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saveLog = rememberLauncherForActivityResult(CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val log = vm.usageLog()
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter().use { it.write(log) }
                }.isSuccess
            }
            Toast.makeText(context, if (saved) R.string.timeline_exported else R.string.timeline_export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.timeline_title), left = MinaIcons.Back, leftLabel = stringResource(R.string.back), onLeft = onBack)
            when (val u = ui) {
                TimelineUi.Loading -> Unit
                TimelineUi.Expired -> EmptyNote(TimelineUi.EXPIRED_TEXT, Modifier.padding(top = 80.dp))
                is TimelineUi.Ready -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                ) {
                    item("header") {
                        HeaderGroup(u.header) {
                            try {
                                saveLog.launch("mina-usage-${vm.runId}.jsonl")
                            } catch (_: ActivityNotFoundException) {
                                Toast.makeText(context, R.string.timeline_export_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    item("trace") { RunTraceSection(vm.runId) }
                    if (u.steps.isNotEmpty()) {
                        item("label") { GroupLabel(trPlural(R.plurals.timeline_total_steps, u.steps.size)) }
                        item("steps") {
                            GroupCard {
                                Column(Modifier.padding(start = 16.dp)) {
                                    u.steps.forEachIndexed { i, s ->
                                        TimelineRow(s, first = i == 0, last = i == u.steps.lastIndex, vm)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderGroup(h: TimelineHeader, onExport: () -> Unit) {
    val c = MinaTheme.colors
    var more by rememberSaveable { mutableStateOf(false) }
    GroupCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Text(h.goal, style = MinaType.Title, color = c.ink)
            if (h.criteria.isNotEmpty()) {
                Text(stringResource(R.string.timeline_criteria), Modifier.padding(top = 10.dp), style = MinaType.Label, color = c.ink3)
                h.criteria.forEachIndexed { i, text ->
                    Text(
                        if (h.criteria.size > 1) "${i + 1}. $text" else text, Modifier.padding(top = 3.dp),
                        style = MinaType.Caption, color = c.ink2,
                    )
                }
            }
        }
        h.metrics?.let { RunMetricsSummary(it) }
        // 还没跑完:这里说「进行中」,下面这些小结(核查、结局)等结束了才有
        if (h.running) {
            ListRow(null, stringResource(R.string.timeline_outcome)) { RowValue(stringResource(R.string.timeline_running), ValueTone.Plain) }
        } else {
            ListRow(null, stringResource(R.string.timeline_outcome)) {
                RowValue(h.outcome, if (h.outcome == stringResource(R.string.outcome_succeeded)) ValueTone.Ok else ValueTone.Plain)
            }
        }
        if (h.verdict != stringResource(R.string.verify_none)) ListRow(null, stringResource(R.string.timeline_check)) { RowValue(h.verdict) }
        h.routerNote?.let { note ->
            ListRow(null, stringResource(R.string.timeline_router_model)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AiLogo(providerOrModel = note, size = 18.dp, showContainer = false)
                    Spacer(Modifier.width(6.dp))
                    RowValue(note)
                }
            }
        }
        // 小结那一行已经写了用时
        if (h.metrics == null) h.duration?.let { d -> ListRow(null, stringResource(R.string.timeline_duration)) { RowValue(d) } }
        ListRow(null, stringResource(R.string.timeline_export), stringResource(R.string.timeline_export_hint), onClick = onExport) { RowChevron() }
        ListRow(null, stringResource(R.string.timeline_more), onClick = { more = !more }) {
            RowValue(if (more) stringResource(R.string.collapse) else stringResource(R.string.expand))
            RowChevron()
        }
        if (more) {
            ListRow(null, stringResource(R.string.timeline_model_calls)) { RowValue(trPlural(R.plurals.times, h.modelCalls)) }
            if (h.usedMemory.isNotEmpty()) {
                ListRow(null, stringResource(R.string.timeline_memory_used)) { RowValue(trPlural(R.plurals.items, h.usedMemory.size)) }
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                    h.usedMemory.forEach { memory ->
                        Text("· $memory", Modifier.padding(top = 3.dp), style = MinaType.Hint, color = c.ink2)
                    }
                }
            }
            Text(
                stringResource(R.string.timeline_timing_note),
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
                style = MinaType.Hint, color = c.ink3,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TimelineRow(s: TimelineStep, first: Boolean, last: Boolean, vm: RunTimelineViewModel) {
    val c = MinaTheme.colors
    var open by rememberSaveable(s.step) { mutableStateOf(false) }
    val shot = s.screenshotId
    val failed = s.result?.startsWith(stringResource(R.string.result_error_prefix)) == true

    // Direct, straight action text ("text seedha")
    val actionText = remember(s) {
        if (s.activities.isNotEmpty()) {
            val primary = s.activities.first()
            val base = if (primary.count > 1) "${primary.label} (${primary.count}×)" else primary.label
            val target = primary.target?.takeIf { it.isNotBlank() }
            listOfNotNull(base, target).joinToString(" · ")
        } else {
            listOfNotNull(s.tool, s.detail?.takeIf { it.isNotBlank() }).joinToString(" · ")
        }
    }

    StepItem(
        if (failed) StepState.Failed else StepState.Done, first, last,
        Modifier.clickable { open = !open },
        dotY = 14.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(end = 16.dp, top = 6.dp, bottom = 6.dp)) {
            // Direct top line: Step # + Action text + Outcome pill
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "#${s.step}",
                        style = MinaType.Label.copy(fontWeight = FontWeight.Bold, fontSize = 12.sp),
                        color = c.ink3,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = actionText.ifBlank { s.tool ?: stringResource(R.string.step_number, s.step) },
                        style = MinaType.Body.copy(fontWeight = FontWeight.Medium, fontSize = 13.5.sp),
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                s.result?.let { res ->
                    val (resBg, resFg) = when {
                        failed -> c.bad.copy(alpha = 0.12f) to c.bad
                        res == stringResource(R.string.result_effective) || res.contains("Worked", ignoreCase = true) -> c.ok.copy(alpha = 0.12f) to c.ok
                        else -> c.hair to c.ink3
                    }
                    Box(
                        modifier = Modifier
                            .clip(MinaShapes.Pill)
                            .background(resBg)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = res,
                            style = MinaType.Caption.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                            color = resFg,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Compact timing line and expandable indicator
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = RunTimelineViewModel.timingLabel(s.timing),
                    style = MinaType.Hint.copy(fontSize = 11.5.sp),
                    color = c.ink3,
                )

                if (shot != null || s.said.isNotEmpty() || s.activities.size > 1 || s.tags.isNotEmpty()) {
                    Text(
                        text = if (open) stringResource(R.string.collapse) else stringResource(R.string.expand),
                        style = MinaType.Hint.copy(fontSize = 11.sp, color = c.accent),
                    )
                }
            }

            // Expandable details (only shown when tapped)
            if (open) {
                if (s.said.isNotEmpty()) {
                    Text(
                        text = s.said,
                        modifier = Modifier.padding(top = 4.dp),
                        style = MinaType.Caption.copy(fontSize = 12.sp),
                        color = c.ink2,
                    )
                }

                if (s.activities.size > 1) {
                    Column(Modifier.padding(top = 4.dp)) {
                        s.activities.drop(1).forEach { a ->
                            val action = if (a.count > 1) trPlural(R.plurals.timeline_viewed_times, a.count) else a.label
                            val effect = ChatCopy.effectLabel(a.effect)
                            Text(
                                text = listOfNotNull(action, a.target?.takeIf(String::isNotBlank), effect.takeIf(String::isNotBlank)).joinToString(" · "),
                                style = MinaType.Caption.copy(fontSize = 11.5.sp),
                                color = c.ink3,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                    }
                }

                if (s.tags.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        s.tags.forEach { TagChip(it) }
                    }
                }

                if (shot != null) {
                    Text(
                        text = stringResource(R.string.timeline_show_screen),
                        modifier = Modifier.padding(top = 6.dp),
                        style = MinaType.Label,
                        color = c.accent,
                    )
                    Shot(vm, shot, s.step)
                }
            }
        }
    }
}

@Composable
private fun TagChip(t: Tag) {
    val c = MinaTheme.colors
    val (bg, fg) = when (t.tone) {
        TagTone.Ok -> c.okSoft to c.ok
        TagTone.Warn -> c.warnSoft to c.warn
        TagTone.Bad -> c.badSoft to c.bad
    }
    Text(
        t.text, Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = MinaType.Hint, fontWeight = FontWeight.SemiBold, color = fg,
    )
}

/** 当时的画面:在后台线程解码;文件已被清理时只说一句。 */
@Composable
private fun Shot(vm: RunTimelineViewModel, id: Int, step: Int) {
    val image by produceState<ImageBitmap?>(null, id) {
        value = withContext(Dispatchers.IO) { vm.screen(id)?.let { BitmapFactory.decodeFile(it.path) }?.asImageBitmap() }
    }
    val shape = RoundedCornerShape(12.dp)
    image?.let {
        Image(
            it, stringResource(R.string.timeline_step_screen, step),
            Modifier.padding(top = 8.dp).widthIn(max = 220.dp).clip(shape).background(MinaTheme.colors.fill, shape),
            contentScale = ContentScale.FillWidth,
        )
    }
}
