package com.yahaeng.android.rules

import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * 일출과 일몰 시각(한국 시간, 하루 중 분). 기기 안에서 위치와 날짜로 계산한다(외부 서버 없음).
 * 미국 해양대기청(NOAA)의 근사식이다. 오차는 몇 분 안이다.
 */
data class SunTimes(val sunriseMinute: Int, val sunsetMinute: Int) {

    /** 일몰 뒤이거나 일출 전이면 밤. */
    fun isNight(minuteOfDay: Int): Boolean = minuteOfDay >= sunsetMinute || minuteOfDay < sunriseMinute

    companion object {
        /** 극지방처럼 해가 뜨거나 지지 않으면 null(한국에서는 일어나지 않는다). */
        fun of(date: LocalDate, lat: Double, lon: Double): SunTimes? {
            val gamma = 2 * PI / 365 * (date.dayOfYear - 1)
            val eqTime = 229.18 * (
                0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
                    0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma)
                )
            val decl = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) -
                0.006758 * cos(2 * gamma) + 0.000907 * sin(2 * gamma) -
                0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
            val latRad = lat * PI / 180
            val x = cos(90.833 * PI / 180) / (cos(latRad) * cos(decl)) - tan(latRad) * tan(decl)
            if (x !in -1.0..1.0) return null
            val haDeg = acos(x) * 180 / PI
            val riseUtc = 720 - 4 * (lon + haDeg) - eqTime
            val setUtc = 720 - 4 * (lon - haDeg) - eqTime
            fun korea(utcMinutes: Double) = Math.floorMod((utcMinutes + 540).roundToInt(), 1440)
            return SunTimes(korea(riseUtc), korea(setUtc))
        }
    }
}
