package com.meenai.mina.settings

import com.meenai.mina.core.cap.phone.OperatingPace
import com.meenai.mina.core.settings.SelfConfig

/**
 * The few settings that can be changed on the model. [FeatureFlags] is storage, here only reversible
 * preferences are selected and handed over to the model:
 *
 * **Not given**: Model and Key (replace your own brain, spend the user's money), any deleted data (clear
 * the workspace, reset the running environment, delete conversations) - irreversible. **Not given**:
 * Container networking and proxy-data may leave the phone after opening. This line must be decided by
 * the user in the settings. Whatever is given can be changed back at will.
 */
class SelfConfigImpl(
    private val flags: FeatureFlags,
) : SelfConfig {
    override fun options(): List<SelfConfig.Option> = listOf(
        SelfConfig.Option(KEY_PACE, "Operating pace", flags.operatingPace().name.lowercase(), listOf("fast", "standard", "human")),
        SelfConfig.Option(KEY_MUTE, "Mute target app while working", onOff(flags.muteTarget()), ON_OFF),
        SelfConfig.Option(KEY_THEME, "Appearance", flags.themeMode().name.lowercase(), listOf("system", "light", "dark")),
        SelfConfig.Option(KEY_CAPSULE, "Floating capsule", flags.capsuleMode().name.lowercase(), listOf("off", "duringtasks", "always")),
        SelfConfig.Option(KEY_KEEP_ON, "Keep screen on while working", onOff(flags.keepScreenOn()), ON_OFF),
    )

    override fun set(key: String, value: String): String? {
        val v = value.trim().lowercase()
        fun onOff(): Boolean? = when (v) {
            "on", "true", "yes" -> true
            "off", "false", "no" -> false
            else -> null
        }
        fun bool(write: (Boolean) -> Unit): String? {
            val on = onOff() ?: return ON_OFF_HINT
            write(on)
            return null
        }
        return when (key) {
            KEY_PACE -> {
                val pace = OperatingPace.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "operating pace is one of fast, standard, human"
                flags.setOperatingPace(pace)
                null
            }
            KEY_MUTE -> bool { flags.setMuteTarget(it) }
            KEY_THEME -> {
                val mode = ThemeMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "appearance is one of system, light, dark"
                flags.setThemeMode(mode)
                null
            }
            KEY_CAPSULE -> {
                val mode = com.meenai.mina.capsule.CapsuleMode.entries.firstOrNull { it.name.equals(v, true) }
                    ?: return "capsule is one of off, duringtasks, always"
                flags.setCapsuleMode(mode)
                null
            }
            KEY_KEEP_ON -> bool { flags.setKeepScreenOn(it) }
            else -> "there is no such setting"
        }
    }

    private fun onOff(b: Boolean) = if (b) "on" else "off"

    private companion object {
        val ON_OFF = listOf("on", "off")
        const val ON_OFF_HINT = "value is on or off"
        const val KEY_PACE = "operating_pace"
        const val KEY_MUTE = "mute_apps"
        const val KEY_THEME = "appearance"
        const val KEY_CAPSULE = "capsule"
        const val KEY_KEEP_ON = "keep_screen_on"
    }
}
