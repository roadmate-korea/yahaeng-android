package com.yahaeng.android.night

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SettingsState(
    val onboarded: Boolean = false,
    val autoStart: Boolean = true,
    val flashVoice: Boolean = true,
    val popup: Boolean = true,
    val rain: Boolean = true,
    val crosswalk: Boolean = true,
)

/** 사용자 설정. 기기 안(SharedPreferences)에만 둔다. */
class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private fun read() = SettingsState(
        onboarded = prefs.getBoolean(ONBOARDED, false),
        autoStart = prefs.getBoolean(AUTO_START, true),
        flashVoice = prefs.getBoolean(FLASH_VOICE, true),
        popup = prefs.getBoolean(POPUP, true),
        rain = prefs.getBoolean(RAIN, true),
        crosswalk = prefs.getBoolean(CROSSWALK, true),
    )

    fun update(block: (SettingsState) -> SettingsState) {
        val next = block(_state.value)
        prefs.edit()
            .putBoolean(ONBOARDED, next.onboarded)
            .putBoolean(AUTO_START, next.autoStart)
            .putBoolean(FLASH_VOICE, next.flashVoice)
            .putBoolean(POPUP, next.popup)
            .putBoolean(RAIN, next.rain)
            .putBoolean(CROSSWALK, next.crosswalk)
            .apply()
        _state.value = next
    }

    private companion object {
        const val ONBOARDED = "onboarded"
        const val AUTO_START = "auto_start"
        const val FLASH_VOICE = "flash_voice"
        const val POPUP = "popup"
        const val RAIN = "rain"
        const val CROSSWALK = "crosswalk"
    }
}
