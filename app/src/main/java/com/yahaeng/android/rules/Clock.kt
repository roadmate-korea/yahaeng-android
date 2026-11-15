package com.yahaeng.android.rules

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 지금 시각. 테스트와 시연에서는 다른 시계로 바꾼다. */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }

        /** 점멸 운영 시각은 지자체가 한국 시간으로 적는다. */
        val KOREA: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/** 한국 시간 기준 하루 중 몇 분째인지(0~1439). */
fun Clock.minuteOfDay(): Int {
    val t = Instant.ofEpochMilli(nowMillis()).atZone(Clock.KOREA)
    return t.hour * 60 + t.minute
}

fun Clock.koreaDate(): LocalDate = Instant.ofEpochMilli(nowMillis()).atZone(Clock.KOREA).toLocalDate()
