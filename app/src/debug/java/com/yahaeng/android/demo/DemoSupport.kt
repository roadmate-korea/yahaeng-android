package com.yahaeng.android.demo

import com.yahaeng.android.drive.RawSignal
import com.yahaeng.android.rules.Clock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.time.Instant

/**
 * 시연 입력(디버그 빌드만). 낮에 찍는 시연 영상에서 밤 시각을 재현하고, 에뮬레이터 내비가 0으로 주는 제한 속도를 넣는다.
 * 넣은 값은 실제 SDK 신호와 같은 [RawSignal] 경로로 판단 로직에 들어간다. release 빌드의 같은 이름 객체는 아무것도 바꾸지 않는다.
 */
object DemoSupport {

    private class OffsetClock : Clock {
        @Volatile
        var offsetMillis = 0L
        override fun nowMillis(): Long = System.currentTimeMillis() + offsetMillis
    }

    private val offsetClock = OffsetClock()
    private val _input = MutableSharedFlow<RawSignal>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    @Volatile
    private var speedLimit: Int? = null

    val clock: Clock = offsetClock
    val input: Flow<RawSignal> = _input

    /** 시연 제한 속도가 있으면 내비의 제한 속도(에뮬레이터는 0)는 받지 않는다. */
    val overridesSpeedLimit: Boolean get() = speedLimit != null

    /** 한국 시간으로 오늘 [minuteOfDay]가 지금이 되도록 시계를 옮긴다. */
    fun setKoreaTime(minuteOfDay: Int) {
        val now = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(Clock.KOREA)
        val target = now.toLocalDate().atStartOfDay(Clock.KOREA).plusMinutes(minuteOfDay.toLong())
        offsetClock.offsetMillis = target.toInstant().toEpochMilli() - now.toInstant().toEpochMilli()
    }

    fun setSpeedLimit(kmh: Int) {
        speedLimit = kmh.takeIf { it > 0 }
        _input.tryEmit(RawSignal.Navi.SpeedLimit(kmh))
    }

    fun reset() {
        offsetClock.offsetMillis = 0L
        speedLimit = null
    }
}
