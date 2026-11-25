package com.meenai.mina.settings

import android.content.Context
import android.content.SharedPreferences
import com.meenai.mina.capsule.CapsuleDock
import com.meenai.mina.capsule.CardDocking
import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.capsule.DockSide
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.cap.phone.OperatingPace
import com.meenai.mina.task.ParallelCapability
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Function switch. The speed increase switch is saved in the format of [SpeedFlags.describe], and can be overwritten by parameters on the evaluation runner. */
class FeatureFlags(private val prefs: SharedPreferences) {
    /** The default value upgrade that has not been done yet (see [SPEED_UPGRADES]) should be done once; after the upgrade, the user manually turns it off and it will remain closed. */
    fun speedFlags(): SpeedFlags {
        val flags = prefs.getString(SPEED_FLAGS, null)?.let { runCatching { SpeedFlags.parse(it) }.getOrNull() } ?: SpeedFlags.OFF
        val pending = SPEED_UPGRADES.filter { (key, _) -> !prefs.getBoolean(key, false) }
        if (pending.isEmpty()) return flags
        val upgraded = pending.fold(flags) { f, (_, upgrade) -> upgrade(f) }
        setSpeed(upgraded)
        return upgraded
    }

    fun setSpeed(flags: SpeedFlags) {
        val edit = prefs.edit().putString(SPEED_FLAGS, flags.describe())
        SPEED_UPGRADES.forEach { (key, _) -> edit.putBoolean(key, true) }
        edit.commit()
    }

    /** The maximum number of parallel tasks can be run at the same time (user setting, 1–3); the real upper limit depends on the device, see ParallelCapability. */
    fun parallelMax(): Int = prefs.getInt(PARALLEL_MAX, ParallelCapability.DEFAULT_MAX)

    fun setParallelMax(max: Int) {
        prefs.edit().putInt(PARALLEL_MAX, max).commit()
    }

    /** The screen remains always on while working; it is on by default and can be turned off by the user. */
    fun keepScreenOn(): Boolean = prefs.getBoolean(KEEP_SCREEN_ON, true)

    fun setKeepScreenOn(on: Boolean) {
        prefs.edit().putBoolean(KEEP_SCREEN_ON, on).commit()
    }

    /** Mute the operated App while working; it is enabled by default and can be turned off by the user. */
    fun muteTarget(): Boolean = prefs.getBoolean(MUTE_TARGET, true)

    fun setMuteTarget(on: Boolean) {
        prefs.edit().putBoolean(MUTE_TARGET, on).commit()
    }

    /** The rhythm of operating in the App: fast, standard (default), human-like (see [OperatingPace]). */
    fun operatingPace(): OperatingPace = OperatingPace.parse(prefs.getString(OPERATING_PACE, null)) ?: OperatingPace.DEFAULT

    fun setOperatingPace(pace: OperatingPace) {
        prefs.edit().putString(OPERATING_PACE, pace.name).commit()
    }

    /** Interface color matching: follow the system, always light color, always dark color (see [ThemeMode]). The interface can be changed on the spot following [themeModeChanges] without rebuilding. */
    fun themeMode(): ThemeMode = ThemeMode.parse(prefs.getString(THEME_MODE, null))

    /** The current value of the appearance will be sent again every time it is changed (the changes on the settings page and the changes in the task are the same). */
    fun themeModeChanges(): Flow<ThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == null || key == THEME_MODE) trySend(themeMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(themeMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(THEME_MODE, mode.name).commit()
    }

    /**
     * Allow Mina to change its own settings (see Core's SelfConfig): Models and Keys, and deleting data
     * are not allowed. It is off by default; when it is on, there are two tools, settings_list and
     * settings_set, in the task.
     */
    fun selfConfig(): Boolean = prefs.getBoolean(SELF_CONFIG, false)

    fun setSelfConfig(on: Boolean) {
        prefs.edit().putBoolean(SELF_CONFIG, on).commit()
    }

    /** Suspended capsule: off, displayed when there is a task (default), always displayed. */
    fun capsuleMode(): CapsuleMode = CapsuleMode.parse(prefs.getString(CAPSULE_MODE, null))

    fun setCapsuleMode(mode: CapsuleMode) {
        prefs.edit().putString(CAPSULE_MODE, mode.name).commit()
    }

    /** The current value of the capsule switch, which will be sent again every time it is changed. */
    fun capsuleModeChanges(): Flow<CapsuleMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            // key is null when clearing all settings
            if (key == null || key == CAPSULE_MODE) trySend(capsuleMode())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        send(capsuleMode())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    /** Where should the capsule stop: left or right, height as a proportion of the swingable range; when it is not being dragged, it is in the upper right corner. */
    fun capsuleDock(): CapsuleDock = CapsuleDock(
        if (prefs.getString(CAPSULE_SIDE, null) == DockSide.Left.name) DockSide.Left else DockSide.Right,
        prefs.getFloat(CAPSULE_HEIGHT, 0f).coerceIn(0f, 1f),
    )

    fun setCapsuleDock(dock: CapsuleDock) {
        prefs.edit().putString(CAPSULE_SIDE, dock.side.name).putFloat(CAPSULE_HEIGHT, dock.fraction).apply()
    }

    /**
     * Where does the unfolded card stop: the proportion of the upper part in the range that can be
     * placed (see [com.meenai.mina.capsule.CardDocking.release]); if you don’t remember, the highest
     * card is in the center. In the past, the ratio of notes on a card with a fixed height was exactly
     * the ratio above it, so it was read as usual.
     */
    fun capsuleCardFraction(): Float = prefs.getFloat(CAPSULE_CARD_HEIGHT, CardDocking.DEFAULT_FRACTION).coerceIn(0f, 1f)

    fun setCapsuleCardFraction(fraction: Float) {
        prefs.edit().putFloat(CAPSULE_CARD_HEIGHT, fraction.coerceIn(0f, 1f)).apply()
    }

    /** Prompt bar after cutting the model; it is enabled by default (true), and the user can turn it off in the settings. */
    fun showSwitchNotice(): Boolean = prefs.getBoolean(SHOW_SWITCH_NOTICE, true)

    fun setShowSwitchNotice(on: Boolean) {
        prefs.edit().putBoolean(SHOW_SWITCH_NOTICE, on).commit()
    }

    /** The daily budget (number of requests) set by yourself on the usage limit page; the progress bar is calculated according to it, and the default is [DEFAULT_USAGE_BUDGET]. */
    fun usageDailyBudget(): Int =
        prefs.getInt(USAGE_DAILY_BUDGET, DEFAULT_USAGE_BUDGET).coerceIn(MIN_USAGE_BUDGET, MAX_USAGE_BUDGET)

    fun setUsageDailyBudget(n: Int) {
        prefs.edit().putInt(USAGE_DAILY_BUDGET, n.coerceIn(MIN_USAGE_BUDGET, MAX_USAGE_BUDGET)).apply()
    }

    companion object {
        const val PREFS = "feature_flags"
        private const val SHOW_SWITCH_NOTICE = "show_switch_notice"
        private const val SPEED_FLAGS = "speed_flags"
        /**
         * To upgrade the default value of the speed-up switch, do it once each in sequence (key to note
         * whether you have done it): v2 turns on graded screen reading, continuous operation, and
         * picture viewing on demand; v3 turns on experience playback (you can only remember the route
         * after learning to change the ending judgment).
         */
        private val SPEED_UPGRADES: List<Pair<String, (SpeedFlags) -> SpeedFlags>> = listOf(
            "speed_defaults_applied_v2" to { f -> f.copy(inlineScreen = true, batchSteps = true, adaptiveVision = true) },
            "speed_defaults_applied_v3" to { f -> f.copy(replay = true) },
        )
        private const val PARALLEL_MAX = "parallel_max"
        private const val KEEP_SCREEN_ON = "keep_screen_on"
        private const val MUTE_TARGET = "mute_target"
        private const val OPERATING_PACE = "operating_pace"
        private const val SELF_CONFIG = "self_config"
        private const val THEME_MODE = "theme_mode"
        private const val CAPSULE_MODE = "capsule_mode"
        private const val CAPSULE_SIDE = "capsule_side"
        private const val CAPSULE_HEIGHT = "capsule_height"
        private const val CAPSULE_CARD_HEIGHT = "capsule_card_height"
        private const val USAGE_DAILY_BUDGET = "usage_daily_budget"
        const val DEFAULT_USAGE_BUDGET = 100
        const val MIN_USAGE_BUDGET = 25
        const val MAX_USAGE_BUDGET = 2_000
        const val USAGE_BUDGET_STEP = 25

        fun get(context: Context) = FeatureFlags(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
