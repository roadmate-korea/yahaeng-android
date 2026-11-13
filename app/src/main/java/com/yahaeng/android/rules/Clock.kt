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

/**
 * 시연용 시계. [base]에 [offsetMillis]를 더한다. 낮에 찍는 시연 영상에서 "밤 11시 30분"을 재현할 때 쓴다.
 * 판단 로직은 시계가 진짜인지 모른다(시연용 분기 없음).
 */
class OffsetClock(private val base: Clock = Clock.SYSTEM) : Clock {
    @Volatile
    var offsetMillis: Long = 0L

    override fun nowMillis(): Long = base.nowMillis() + offsetMillis

    /** 한국 시간으로 오늘 [minuteOfDay]가 지금이 되도록 맞춘다. */
    fun setKoreaTime(minuteOfDay: Int) {
        val now = Instant.ofEpochMilli(base.nowMillis()).atZone(Clock.KOREA)
        val target = now.toLocalDate().atStartOfDay(Clock.KOREA).plusMinutes(minuteOfDay.toLong())
        offsetMillis = target.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
    }

    fun reset() {
        offsetMillis = 0L
    }
}
