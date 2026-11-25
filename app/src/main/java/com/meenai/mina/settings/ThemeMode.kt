package com.meenai.mina.settings

/** Interface color matching: follow the system (default), always light, always dark. */
enum class ThemeMode {
    System, Light, Dark;

    companion object {
        fun parse(s: String?): ThemeMode = entries.firstOrNull { it.name == s } ?: System
    }
}
