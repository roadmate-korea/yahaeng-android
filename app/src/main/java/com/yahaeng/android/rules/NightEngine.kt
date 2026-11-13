package com.yahaeng.android.rules

import java.time.LocalDate

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

enum class TurnSignal { OFF, LEFT, RIGHT }

/** 와이퍼 스위치 위치. MIST는 한 번 닦기라 비로 보지 않는다. */
enum class WiperMode { OFF, MIST, INTERMITTENT, CONTINUOUS, AUTO, OTHER }

/** 판단 로직이 받는 이벤트. SDK 타입은 어댑터(SignalMapper)에서 바꿔 들어온다. */
sealed interface NightEvent {
    data class LocationChanged(val lat: Double, val lon: Double) : NightEvent {
        override fun toString() = "LocationChanged"
    }

    data class ZoneEntered(val intersectionId: String, val ring: Ring) : NightEvent
    data class ZoneExited(val intersectionId: String, val ring: Ring) : NightEvent

    /** null이면 속도를 읽을 수 없음. */
    data class SpeedChanged(val kmh: Int?) : NightEvent
    data class GearChanged(val gear: Gear) : NightEvent
    data class TurnSignalChanged(val signal: TurnSignal) : NightEvent
    data class WiperSwitchChanged(val mode: WiperMode) : NightEvent

    /** 와이퍼가 실제로 움직이는지(WiperState ON). */
    data class WiperMotionChanged(val moving: Boolean) : NightEvent
    data class CruiseStateChanged(val active: Boolean) : NightEvent

    /** null이면 목표 속도 없음. */
    data class CruiseTargetChanged(val kmh: Int?) : NightEvent

    /** 내비가 준 지금 도로 제한 속도. 0이면 모름. */
    data class SpeedLimitChanged(val kmh: Int) : NightEvent
}

/** 점멸 교차로를 지난 사실 한 건. 점수가 아니라 기록이다. */
data class Passage(
    val intersectionId: String,
    val name: String,
    val atMillis: Long,
    val window: FlashWindow,
    /** 안쪽 고리 안에서 가장 낮았던 속도. 속도를 읽지 못했으면 null. */
    val minKmh: Int?,
) {
    enum class Outcome { STOPPED, SLOWED, PASSED, UNKNOWN }

    val outcome: Outcome
        get() = when {
            minKmh == null -> Outcome.UNKNOWN
            minKmh <= NightEngine.STOP_KMH -> Outcome.STOPPED
            minKmh <= NightEngine.SLOW_KMH -> Outcome.SLOWED
            else -> Outcome.PASSED
        }
}

/** 화면에 보여 줄 판단 상태. */
data class NightState(
    val night: Boolean = false,
    val sun: SunTimes? = null,
    val hasLocation: Boolean = false,
    /** 오늘 밤 근처(10km)에 점멸 교차로가 몇 곳인지. 위치를 모르면 null. */
    val nearbyTonight: Int? = null,
    /** 지금 다가가는 점멸 교차로. */
    val approaching: String? = null,
    val wiping: Boolean = false,
    val speedLimit: Int = 0,
    /** 와이퍼가 계속 돌 때의 젖은 노면 기준 속도. 조건이 안 되면 null. */
    val wetLimit: Int? = null,
)

/**
 * 밤에 바뀌는 규칙을 판단한다(순수 Kotlin, Android import 금지).
 *
 * 1. 점멸 교차로: 바깥 고리에 들어왔을 때 그 교차로가 지금 점멸 중이면 한 번 알린다. 방향지시등에 따라 한 문장을 더한다.
 *    안쪽 고리 안에서 가장 낮은 속도를 기록하고, 서행도 하지 않고 지났으면 차가 멈췄을 때 사실을 한 번 말한다.
 * 2. 비 오는 밤: 와이퍼가 [WIPER_SUSTAIN_MS] 넘게 계속 돌고 내비 제한 속도를 알면 20% 줄인 속도를 계산한다
 *    (도로교통법 시행규칙 제19조 제2항, 비가 내려 노면이 젖은 경우). 크루즈 목표 속도나 지금 속도가 그보다 높을 때 한 번 말한다.
 *    앱은 실제 노면을 모르므로 "노면이 젖었다면"이라는 조건을 붙여 말한다.
 * 3. 해가 지고 처음 주행할 때 오늘 밤 근처 점멸 교차로 수를 알린다. 정보가 없는 지역이면 없다고 말한다.
 * 4. P에 두면 오늘 밤 지난 점멸 교차로를 사실로 요약한다.
 *
 * 시간에 따른 판단을 위해 1초마다 [tick]을 부른다.
 */
class NightEngine(
    private val map: () -> FlashMap,
    private val clock: Clock = Clock.SYSTEM,
) {
    private var lat: Double? = null
    private var lon: Double? = null
    private var gear = Gear.UNKNOWN
    private var speed: Int? = null
    private var turnSignal = TurnSignal.OFF

    private var announcedNight: LocalDate? = null
    private var sunCache: Pair<LocalDate, SunTimes?>? = null

    private class Approach(val enteredAt: Long) {
        var announced = false
        val turnsSaid = mutableSetOf<TurnSignal>()
    }

    private val approaches = linkedMapOf<String, Approach>()
    private val lastAnnounced = mutableMapOf<String, Long>()
    private var lastFlashAt: Long? = null
    private val insideMin = mutableMapOf<String, Int?>()
    private val insideActive = mutableSetOf<String>()

    private val _passages = mutableListOf<Passage>()
    val passages: List<Passage> get() = _passages.toList()
    private var summarized = 0
    private var pendingNotice: Passage? = null

    // 비 오는 밤
    private var wiperMode = WiperMode.OFF
    private var wiperMoving = false
    private var wipingSince: Long? = null
    private var notWipingSince: Long? = null
    private var limit = 0
    private var cruiseActive = false
    private var cruiseTarget: Int? = null
    private var overSince: Long? = null
    private val rainSaidForLimit = mutableSetOf<Int>()

    fun on(event: NightEvent): List<Guidance> {
        val out = mutableListOf<Guidance>()
        when (event) {
            is NightEvent.LocationChanged -> {
                lat = event.lat
                lon = event.lon
            }
            is NightEvent.GearChanged -> {
                gear = event.gear
                if (gear == Gear.PARK) {
                    deliverNotice(out)
                    summarize(out)
                }
            }
            is NightEvent.SpeedChanged -> {
                speed = event.kmh
                insideActive.forEach { id ->
                    val now = event.kmh
                    val prev = insideMin[id]
                    insideMin[id] = when {
                        now == null -> prev
                        prev == null -> now
                        else -> minOf(prev, now)
                    }
                }
                if (event.kmh != null && event.kmh <= STOP_KMH && insideActive.isEmpty()) deliverNotice(out)
            }
            is NightEvent.TurnSignalChanged -> {
                turnSignal = event.signal
                if (event.signal != TurnSignal.OFF) {
                    approaches.filterValues { it.announced }.keys.lastOrNull()?.let { id -> sayTurn(id, out) }
                }
            }
            is NightEvent.ZoneEntered -> onEnter(event.intersectionId, event.ring, out)
            is NightEvent.ZoneExited -> onExit(event.intersectionId, event.ring)
            is NightEvent.WiperSwitchChanged -> {
                wiperMode = event.mode
                updateWiping()
            }
            is NightEvent.WiperMotionChanged -> {
                wiperMoving = event.moving
                updateWiping()
            }
            is NightEvent.CruiseStateChanged -> cruiseActive = event.active
            is NightEvent.CruiseTargetChanged -> cruiseTarget = event.kmh
            is NightEvent.SpeedLimitChanged -> limit = event.kmh.coerceAtLeast(0)
        }
        return out + tick()
    }

    /** 시간이 지나서 바뀌는 판단(해 짐, 와이퍼 지속, 과속 지속). 1초마다 부른다. */
    fun tick(): List<Guidance> {
        val out = mutableListOf<Guidance>()
        checkNightStart(out)
        checkRain(out)
        return out
    }

    fun state(): NightState {
        val minute = clock.minuteOfDay()
        val sun = sun()
        val la = lat
        val lo = lon
        return NightState(
            night = sun?.isNight(minute) ?: false,
            sun = sun,
            hasLocation = la != null,
            nearbyTonight = if (la != null && lo != null) map().near(la, lo, NEARBY_METERS).size else null,
            approaching = approaches.filterValues { it.announced }.keys.lastOrNull(),
            wiping = isWipingSustained(),
            speedLimit = limit,
            wetLimit = wetLimit(),
        )
    }

    // ---- 점멸 교차로 ----

    private fun onEnter(id: String, ring: Ring, out: MutableList<Guidance>) {
        val p = map()[id] ?: return
        val now = clock.nowMillis()
        val active = p.window.contains(clock.minuteOfDay())
        when (ring) {
            Ring.APPROACH -> {
                if (!active || gear == Gear.PARK) return
                val a = Approach(now)
                // 점멸 교차로가 촘촘한 곳에서 안내가 연달아 나오지 않게 한다. 앞 교차로 안내 고리 안에 있거나
                // 방금 안내했으면 이번 교차로는 말하지 않는다(통과 기록은 그대로 남긴다)
                val busy = approaches.values.any { it.announced } || (lastFlashAt?.let { now - it < FLASH_GAP_MS } ?: false)
                approaches[id] = a
                val recently = lastAnnounced[id]?.let { now - it < REANNOUNCE_MS } ?: false
                if (recently || busy) return
                a.announced = true
                lastAnnounced[id] = now
                lastFlashAt = now
                out += Guidance(GuidanceId.FLASH_AHEAD, key = id)
                if (turnSignal != TurnSignal.OFF) sayTurn(id, out)
            }
            Ring.INSIDE -> {
                if (!active) return
                insideActive += id
                insideMin[id] = speed
            }
        }
    }

    private fun onExit(id: String, ring: Ring) {
        when (ring) {
            Ring.APPROACH -> approaches.remove(id)
            Ring.INSIDE -> {
                if (!insideActive.remove(id)) return
                val p = map()[id] ?: return
                val passage = Passage(id, p.name, clock.nowMillis(), p.window, insideMin.remove(id))
                _passages += passage
                if (passage.outcome == Passage.Outcome.PASSED) pendingNotice = passage
            }
        }
    }

    private fun sayTurn(id: String, out: MutableList<Guidance>) {
        val a = approaches[id] ?: return
        val signal = turnSignal
        if (signal == TurnSignal.OFF || !a.turnsSaid.add(signal)) return
        out += Guidance(if (signal == TurnSignal.LEFT) GuidanceId.FLASH_TURN_LEFT else GuidanceId.FLASH_TURN_RIGHT, key = id)
    }

    private fun deliverNotice(out: MutableList<Guidance>) {
        val p = pendingNotice ?: return
        pendingNotice = null
        out += Guidance(GuidanceId.FLASH_PASSED_WITHOUT_STOP, listOf(p.minKmh ?: 0), key = p.intersectionId)
    }

    private fun summarize(out: MutableList<Guidance>) {
        val fresh = _passages.drop(summarized)
        if (fresh.isEmpty()) return
        summarized = _passages.size
        out += Guidance(GuidanceId.NIGHT_SUMMARY, listOf(fresh.size, fresh.count { it.outcome == Passage.Outcome.STOPPED }))
    }

    // ---- 해 짐 ----

    private fun sun(): SunTimes? {
        val la = lat ?: return null
        val lo = lon ?: return null
        val date = clock.koreaDate()
        sunCache?.let { (d, s) -> if (d == date) return s }
        return SunTimes.of(date, la, lo).also { sunCache = date to it }
    }

    private fun checkNightStart(out: MutableList<Guidance>) {
        if (gear != Gear.DRIVE) return
        val la = lat ?: return
        val lo = lon ?: return
        val sun = sun() ?: return
        val minute = clock.minuteOfDay()
        if (!sun.isNight(minute)) return
        // 새벽(일출 전)은 전날 밤으로 센다
        val today = clock.koreaDate()
        val night = if (minute < sun.sunriseMinute) today.minusDays(1) else today
        if (announcedNight == night) return
        announcedNight = night
        val count = map().near(la, lo, NEARBY_METERS).size
        out += if (count > 0) Guidance(GuidanceId.NIGHT_START, listOf(count)) else Guidance(GuidanceId.NIGHT_START_NO_DATA)
    }

    // ---- 비 오는 밤 ----

    private fun updateWiping() {
        val wiping = when (wiperMode) {
            WiperMode.INTERMITTENT, WiperMode.CONTINUOUS -> true
            WiperMode.AUTO -> wiperMoving
            WiperMode.OFF, WiperMode.MIST, WiperMode.OTHER -> wiperMoving && wiperMode == WiperMode.OTHER
        }
        val now = clock.nowMillis()
        if (wiping) {
            notWipingSince = null
            if (wipingSince == null) wipingSince = now
        } else if (wipingSince != null && notWipingSince == null) {
            notWipingSince = now
        }
    }

    private fun isWipingSustained(): Boolean {
        val since = wipingSince ?: return false
        return notWipingSince == null && clock.nowMillis() - since >= WIPER_SUSTAIN_MS
    }

    private fun wetLimit(): Int? = if (isWipingSustained() && limit > 0) limit * 8 / 10 else null

    private fun checkRain(out: MutableList<Guidance>) {
        val now = clock.nowMillis()
        // 와이퍼가 한참 멈춰 있으면 비가 그친 것으로 보고 다음 비에 다시 말할 수 있게 한다
        notWipingSince?.let { off ->
            if (now - off >= WIPER_OFF_RESET_MS) {
                wipingSince = null
                notWipingSince = null
                rainSaidForLimit.clear()
            }
        }
        val wet = wetLimit()
        if (wet == null || gear != Gear.DRIVE || limit in rainSaidForLimit) {
            overSince = null
            return
        }
        val target = cruiseTarget
        if (cruiseActive && target != null && target > wet) {
            rainSaidForLimit += limit
            out += Guidance(GuidanceId.RAIN_SPEED_CRUISE, listOf(limit, wet, target))
            return
        }
        val s = speed
        if (s != null && s > wet) {
            val since = overSince ?: now.also { overSince = it }
            if (now - since >= OVER_SUSTAIN_MS) {
                rainSaidForLimit += limit
                overSince = null
                out += Guidance(GuidanceId.RAIN_SPEED, listOf(limit, wet))
            }
        } else {
            overSince = null
        }
    }

    companion object {
        /** 이 속도 이하로 떨어졌으면 멈춘 것으로 본다. */
        const val STOP_KMH = 5

        /** 이 속도 이하면 서행으로 본다. */
        const val SLOW_KMH = 20

        const val NEARBY_METERS = 10_000.0
        const val REANNOUNCE_MS = 10 * 60_000L

        /** 서로 다른 점멸 교차로 안내 사이 최소 간격. */
        const val FLASH_GAP_MS = 30_000L
        const val WIPER_SUSTAIN_MS = 10_000L
        const val WIPER_OFF_RESET_MS = 120_000L
        const val OVER_SUSTAIN_MS = 5_000L
    }
}
