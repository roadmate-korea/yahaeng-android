package com.yahaeng.android.night

import android.content.Context
import com.yahaeng.android.R
import com.yahaeng.android.rules.FlashMap
import com.yahaeng.android.rules.Guidance
import com.yahaeng.android.rules.GuidanceId

/** 안내 ID → 말할 문장. 판단 로직과 문장을 나눈다(문장은 리소스에 있다). */
class GuidanceTexts(private val context: Context) {

    fun text(g: Guidance, map: FlashMap): String {
        val name = map[g.key]?.name?.let(::spokenName)
        val res = context.resources
        return when (g.id) {
            GuidanceId.FLASH_AHEAD -> if (name != null) res.getString(R.string.say_flash_ahead_named, name) else res.getString(R.string.say_flash_ahead)
            GuidanceId.FLASH_TURN_LEFT -> res.getString(R.string.say_flash_turn_left)
            GuidanceId.FLASH_TURN_RIGHT -> res.getString(R.string.say_flash_turn_right)
            GuidanceId.FLASH_PASSED_WITHOUT_STOP -> {
                val kmh = g.args.firstOrNull() ?: 0
                if (name != null) res.getString(R.string.say_flash_passed_named, name, kmh) else res.getString(R.string.say_flash_passed, kmh)
            }
            GuidanceId.RAIN_SPEED -> res.getString(R.string.say_rain_speed, g.args[1])
            GuidanceId.RAIN_SPEED_CRUISE -> res.getString(R.string.say_rain_speed_cruise, g.args[1], g.args[2])
            GuidanceId.DARK_CROSSWALK -> res.getString(R.string.say_dark_crosswalk)
            GuidanceId.NIGHT_START -> res.getString(R.string.say_night_start)
            GuidanceId.NIGHT_SUMMARY -> res.getString(R.string.say_night_summary, g.args[0], g.args[1])
        }
    }

    fun popupTitle(): String = context.getString(R.string.popup_title)

    fun popupText(): String = context.getString(R.string.popup_text)

    companion object {
        /**
         * 소리 내어 부를 이름. 공공데이터의 설치 장소가 비었거나 도로명 주소(숫자 포함)뿐이면 부르지 않는다
         * ("곧 장자대로 89 교차로입니다"는 오히려 헷갈린다).
         */
        fun spokenName(name: String): String? = name.trim().takeIf { it.isNotEmpty() && it.none(Char::isDigit) }
    }
}
