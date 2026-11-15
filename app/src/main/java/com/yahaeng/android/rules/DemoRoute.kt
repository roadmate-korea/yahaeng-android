package com.yahaeng.android.rules

import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * 정차 중 [시연 재생]으로 돌리는 경로(순수 Kotlin). 실제 SDK 신호와 같은 형식의 [NightEvent]를 시각 순서대로 만든다.
 *
 * 실제 시계, 실제 기록, 실제 설정은 건드리지 않는다. 시연은 따로 만든 [NightEngine]과 [DemoClock]으로 돌리고 끝나면 버린다.
 * 근접 알림(FusedLocation)은 기기에서만 오므로, 경로의 위치마다 지도의 고리 안팎을 비교해 같은 이벤트를 만든다.
 *
 * 1. 구리시 장자대로 89(22:00~07:00 점멸)로 시속 40으로 북쪽 주행. 길 위의 어두운 횡단보도, 점멸 교차로, 좌측 방향지시등.
 * 2. 교차로를 지나 멈추면 사실 한 문장, P에서 오늘 밤 요약.
 * 3. 교차로가 없는 곳에서 비 오는 밤: 제한 속도 100, 와이퍼 연속, 크루즈 목표 100.
 */
object DemoRoute {
    data class Step(val atMs: Long, val event: NightEvent)

    /** 시연 시각(한국 시간). 점멸 시간 안이고 해가 진 뒤다. */
    val START: LocalTime = LocalTime.of(23, 30)

    const val LAT = 37.58857
    const val LON = 127.14009

    /** 비 오는 밤 장면 위치. 1km 안에 점멸 교차로와 어두운 횡단보도가 없다(테스트로 확인). */
    const val RAIN_LAT = 37.56120
    const val RAIN_LON = 127.15800

    private const val M_PER_DEG_LAT = 111_195.0
    private const val STEP_MS = 1_000L

    private fun south(m: Int) = LAT - m / M_PER_DEG_LAT

    fun steps(map: FlashMap): List<Step> {
        val out = mutableListOf<Step>()
        val zones = ZoneTracker(map)
        var t = 0L
        fun at(e: NightEvent) { out += Step(t, e) }
        fun move(lat: Double, lon: Double) {
            at(NightEvent.LocationChanged(lat, lon))
            zones.update(lat, lon).forEach(::at)
        }

        // 출발 준비
        move(south(760), LON)
        at(NightEvent.TurnSignalChanged(TurnSignal.OFF))
        at(NightEvent.WiperSwitchChanged(WiperMode.OFF))
        at(NightEvent.CruiseStateChanged(false))
        at(NightEvent.GearChanged(Gear.DRIVE))
        at(NightEvent.SpeedChanged(40))

        // 1. 북쪽 주행(1초에 20m)
        for (m in 740 downTo -140 step 20) {
            t += STEP_MS
            move(south(m), LON)
            if (m == 280) at(NightEvent.TurnSignalChanged(TurnSignal.LEFT))
            if (m == 40) at(NightEvent.TurnSignalChanged(TurnSignal.OFF))
        }

        // 2. 멈추고, P
        t += STEP_MS
        at(NightEvent.SpeedChanged(0))
        t += 12_000
        at(NightEvent.GearChanged(Gear.PARK))

        // 3. 비 오는 밤
        t += 12_000
        move(RAIN_LAT, RAIN_LON)
        at(NightEvent.SpeedLimitChanged(100))
        at(NightEvent.GearChanged(Gear.DRIVE))
        at(NightEvent.SpeedChanged(90))
        at(NightEvent.WiperSwitchChanged(WiperMode.CONTINUOUS))
        at(NightEvent.WiperMotionChanged(true))
        at(NightEvent.CruiseStateChanged(true))
        at(NightEvent.CruiseTargetChanged(100))
        t += 22_000
        at(NightEvent.WiperSwitchChanged(WiperMode.OFF))
        at(NightEvent.WiperMotionChanged(false))
        at(NightEvent.CruiseStateChanged(false))
        at(NightEvent.SpeedChanged(0))
        t += 2_000
        at(NightEvent.GearChanged(Gear.PARK))
        return out
    }

    /** 마지막 이벤트 뒤 안내가 끝날 때까지 기다리는 시간을 더한 전체 길이. */
    fun durationMs(steps: List<Step>): Long = (steps.lastOrNull()?.atMs ?: 0L) + 10_000L

    /** 위치가 바뀔 때마다 교차로 고리(300m, 70m) 안팎을 비교해 근접 알림과 같은 이벤트를 만든다. */
    private class ZoneTracker(private val map: FlashMap) {
        private var inside = emptySet<Pair<String, Ring>>()

        fun update(lat: Double, lon: Double): List<NightEvent> {
            val now = map.near(lat, lon, Ring.APPROACH.radiusMeters.toDouble())
                .flatMap { (p, d) -> Ring.entries.filter { d <= it.radiusMeters }.map { p.id to it } }
                .toSet()
            // 나갈 때는 안쪽 고리부터, 들어올 때는 바깥 고리부터
            val exits = (inside - now).sortedByDescending { it.second.ordinal }.map { NightEvent.ZoneExited(it.first, it.second) }
            val enters = (now - inside).sortedBy { it.second.ordinal }.map { NightEvent.ZoneEntered(it.first, it.second) }
            inside = now
            return exits + enters
        }
    }
}

/** 시연 시계. 시작한 순간을 오늘 [DemoRoute.START](한국 시간)로 두고 실제 시간만큼 흐른다. 실제 시계는 바꾸지 않는다. */
class DemoClock(private val real: Clock) : Clock {
    private val startedReal = real.nowMillis()
    private val startedDemo = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(startedReal), Clock.KOREA)
        .with(DemoRoute.START).toInstant().toEpochMilli()

    override fun nowMillis(): Long = startedDemo + (real.nowMillis() - startedReal)
}
