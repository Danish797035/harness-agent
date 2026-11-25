package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.RowChevron

/** An engineering switch: [id] is used as a test label, and [change] is used to calculate the entire set of switches after changing this item. */
data class DevSwitch(
    val id: String, val title: String, val subtitle: String, val checked: Boolean, val change: (Boolean) -> SpeedFlags,
)

/** Two groups of developer options: Speed (verified, mostly on by default) and Experimentation (off by default). The value of the switch and the storage ([SpeedFlags]) remain unchanged. */
object DeveloperSwitches {
    fun speed(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("adaptive-vision", tr(R.string.dev_adaptive), tr(R.string.dev_adaptive_sub), flags.adaptiveVision) { flags.copy(adaptiveVision = it) },
        DevSwitch("inline-screen", tr(R.string.dev_inline), tr(R.string.dev_inline_sub), flags.inlineScreen) { flags.copy(inlineScreen = it) },
        DevSwitch("batch-steps", tr(R.string.dev_batch), tr(R.string.dev_batch_sub), flags.batchSteps) { flags.copy(batchSteps = it) },
        DevSwitch("deep-links", tr(R.string.dev_deep_links), tr(R.string.dev_deep_links_sub), flags.deepLinks) { flags.copy(deepLinks = it) },
        DevSwitch("light-steps", tr(R.string.dev_light), tr(R.string.dev_light_sub), flags.lightSteps) { flags.copy(lightSteps = it) },
    )

    fun experimental(flags: SpeedFlags): List<DevSwitch> = listOf(
        DevSwitch("replay", tr(R.string.dev_replay), tr(R.string.dev_replay_sub), flags.replay) { flags.copy(replay = it) },
        DevSwitch("parallel-tasks", tr(R.string.dev_parallel), tr(R.string.dev_parallel_sub), flags.parallelTasks) { flags.copy(parallelTasks = it) },
        DevSwitch("structured-compaction", tr(R.string.dev_compaction), tr(R.string.dev_compaction_sub), flags.structuredCompaction) { flags.copy(structuredCompaction = it) },
    )
}

/** "Way of doing things" group: inquiry/autonomous global switch. */
object AutonomyCopy {
    val GROUP: String get() = tr(R.string.dev_group)
    val TITLE: String get() = tr(R.string.dev_autonomy)
    val SUBTITLE: String get() = tr(R.string.dev_autonomy_sub)
}

val DEVELOPER_NOTE: String get() = tr(R.string.dev_note)

/** The current image viewing model's "image viewing position has been verified": [model] is its model name. */
data class GroundingSetting(val model: String, val verified: Boolean)

object GroundingCopy {
    val GROUP: String get() = tr(R.string.cap_vision)
    val TITLE: String get() = tr(R.string.dev_grounding)

    fun subtitle(model: String) = tr(R.string.dev_grounding_sub, model)
}

/**
 * Settings › Developer Options: The front is "Autonomous Mode" ([autonomous], the default is off to
 * ask); the original eight switches under "Speed" are divided into two groups: speed and experiment;
 * when there is a picture viewing model, there is also its "Viewing Positioning Verified" (not displayed
 * when [grounding] is null); the last is the entrance to "Run Statistics" (not displayed when
 * [onOpenRunStats] is null).
 */
@Composable
fun DeveloperOptionsScreen(
    speed: SpeedFlags,
    onSpeed: (SpeedFlags) -> Unit,
    onBack: () -> Unit,
    autonomous: Boolean = false,
    onAutonomous: (Boolean) -> Unit = {},
    grounding: GroundingSetting? = null,
    onGrounding: (Boolean) -> Unit = {},
    onOpenRunStats: (() -> Unit)? = null,
) {
    PageScaffold(tr(R.string.settings_developer), onBack) {
        GroupLabel(AutonomyCopy.GROUP)
        GroupCard {
            ListRow(null, AutonomyCopy.TITLE, AutonomyCopy.SUBTITLE, first = true, onClick = { onAutonomous(!autonomous) }) {
                MinaSwitch(autonomous, onAutonomous, Modifier.testTag("dev-autonomous"))
            }
        }
        GroupLabel(tr(R.string.dev_speed))
        SwitchGroup(DeveloperSwitches.speed(speed), onSpeed)
        GroupLabel(tr(R.string.dev_experiments))
        SwitchGroup(DeveloperSwitches.experimental(speed), onSpeed)
        if (grounding != null) {
            GroupLabel(GroundingCopy.GROUP)
            GroupCard {
                ListRow(null, GroundingCopy.TITLE, GroundingCopy.subtitle(grounding.model), first = true, onClick = { onGrounding(!grounding.verified) }) {
                    MinaSwitch(grounding.verified, onGrounding, Modifier.testTag("dev-grounding-verified"))
                }
            }
        }
        GroupLabel(tr(R.string.dev_run_log))
        GroupCard {
            TraceSettingsRows()
            if (onOpenRunStats != null) {
                ListRow(null, RunMetricsCopy.STATS_TITLE, RunMetricsCopy.STATS_SUBTITLE, onClick = onOpenRunStats) { RowChevron() }
            }
        }
        FooterNote(DEVELOPER_NOTE)
    }
}

@Composable
private fun SwitchGroup(switches: List<DevSwitch>, onSpeed: (SpeedFlags) -> Unit) {
    GroupCard {
        switches.forEachIndexed { i, s ->
            // The entire row can be clicked: the switch is small, the row is large
            ListRow(null, s.title, s.subtitle, first = i == 0, onClick = { onSpeed(s.change(!s.checked)) }) {
                MinaSwitch(s.checked, { onSpeed(s.change(it)) }, Modifier.testTag("dev-${s.id}"))
            }
        }
    }
}
