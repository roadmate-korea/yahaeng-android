package com.yahaeng.android.rules

import java.time.Instant
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

    /** 근접 알림을 뗐다(재계획에서 빠짐, 등록 오류). 실제로 지난 것이 아니라서 통과 기록을 남기지 않고 상태만 지운다. */
    data class ZoneDropped(val intersectionId: String) : NightEvent

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
 * 1. 점멸 교차로: 바깥 고리(300m) 안에 있는 동안 그 교차로가 점멸 중이고 차가 P가 아니면 한 번 알린다.
 *    고리에 들어온 뒤에 점멸이 시작되거나 출발해도 그때 알린다. 방향지시등에 따라 한 문장을 더한다.
 *    안쪽 고리(70m) 안에서 가장 낮은 속도를 기록하고, 서행도 하지 않고 지났으면 차가 멈췄을 때 사실을 한 번 말한다.
 * 2. 비 오는 밤: 밤에 와이퍼가 [WIPER_SUSTAIN_MS] 넘게 쉬지 않고 돌고 내비 제한 속도를 알면 20% 줄인 속도를 계산한다
 *    (도로교통법 시행규칙 제19조 제2항, 비가 내려 노면이 젖은 경우). 크루즈 목표 속도나 지금 속도가 그보다 높을 때 한 번 말한다.
 *    앱은 실제 노면을 모르므로 "노면이 젖었다면"이라는 조건을 붙여 말한다.
 * 3. 해가 지고 처음 주행할 때 오늘 밤 근처 점멸 교차로 수를 알린다. 정보가 없는 지역이면 없다고 말한다.
 * 4. P에 두면 오늘 밤 지난 점멸 교차로를 사실로 요약한다. 기록은 밤 단위(정오~다음 날 정오)로 두고 다음 밤이 되면 지운다.
 *
 * 시간에 따른 판단을 위해 1초마다 [tick]을 부른다. 음성 대기열이 재생 직전에 [revalidate]로 안내가 아직 맞는지 묻는다.
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

    /** 바깥 고리 안에 있는 교차로. 알렸는지와 별개로 실제로 고리 안에 있는 동안 둔다. */
    private class Approach {
        var announced = false

        /** 이번 접근에서 이미 안쪽 고리를 지났다. 뒤늦게 "곧 교차로"라고 말하지 않는다. */
        var passed = false
        val turnsSaid = mutableSetOf<TurnSignal>()
    }

    private val approaches = linkedMapOf<String, Approach>()
    private val lastAnnounced = mutableMapOf<String, Long>()
    private var lastFlashAt: Long? = null
    private val insideMin = mutableMapOf<String, Int?>()

    /** 안쪽 고리 안에 있는 교차로(점멸 여부와 상관없이). */
    private val insidePresent = mutableSetOf<String>()

    /** 안쪽 고리 안에서 점멸 중이라 속도를 재고 있는 교차로. */
    private val insideActive = mutableSetOf<String>()

    private var session: LocalDate? = null
    private val _passages = mutableListOf<Passage>()

    /** 오늘 밤 기록. */
    val passages: List<Passage> get() = _passages.toList()
    private var summarized = 0
    private var summarizedBefore = 0
    private var pendingNotice: Passage? = null

    /** 대기열에 넘긴 사실 안내. 재생 직전에 차가 다시 움직이면 다음 정차 때 다시 말하려고 둔다. */
    private var queuedNotice: Passage? = null

    // 비 오는 밤
    private var wiperMode = WiperMode.OFF
    private var wiperMoving = false
    private var motionOffSince: Long? = null
    private var wipingSince: Long? = null
    private var notWipingSince: Long? = null
    private var limit = 0
    private var cruiseActive = false
    private var cruiseTarget: Int? = null
    /** 지금 속도가 젖은 노면 기준보다 높아진 때(낮아지면 null). 안내를 냈는지와 상관없이 잰다. */
    private var overSince: Long? = null
    private val rainSaidForLimit = mutableSetOf<Int>()

    fun on(event: NightEvent): List<Guidance> {
        rollSession()
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
                if (event.signal != TurnSignal.OFF) announcedApproach()?.let { id -> sayTurn(id, out) }
            }
            is NightEvent.ZoneEntered -> onEnter(event.intersectionId, event.ring)
            is NightEvent.ZoneExited -> onExit(event.intersectionId, event.ring)
            is NightEvent.ZoneDropped -> forget(event.intersectionId)
            is NightEvent.WiperSwitchChanged -> wiperMode = event.mode
            is NightEvent.WiperMotionChanged -> {
                if (wiperMoving && !event.moving) motionOffSince = clock.nowMillis()
                if (event.moving) motionOffSince = null
                wiperMoving = event.moving
            }
            is NightEvent.CruiseStateChanged -> cruiseActive = event.active
            is NightEvent.CruiseTargetChanged -> cruiseTarget = event.kmh
            is NightEvent.SpeedLimitChanged -> limit = event.kmh.coerceAtLeast(0)
        }
        return out + tick()
    }

    /** 시간이 지나서 바뀌는 판단(점멸 시작과 끝, 해 짐, 와이퍼 지속, 과속 지속). 1초마다 부른다. */
    fun tick(): List<Guidance> {
        rollSession()
        val out = mutableListOf<Guidance>()
        refreshApproaches(out)
        checkNightStart(out)
        updateWiping()
        checkRain(out)
        return out
    }

    /**
     * 기다리던 안내가 지금도 맞는지. 맞으면 지금 값으로 바꾼 안내를, 더는 맞지 않으면 null을 돌려준다.
     * 음성 대기열이 재생 직전에 부른다(기다리는 동안 교차로를 벗어나거나 방향, 와이퍼, 제한 속도가 바뀔 수 있다).
     */
    fun revalidate(g: Guidance): Guidance? = when (g.id) {
        GuidanceId.FLASH_AHEAD -> g.takeIf { approaches[g.key]?.announced == true && isFlashing(g.key) }
        GuidanceId.FLASH_TURN_LEFT -> g.takeIf { approaches.containsKey(g.key) && isFlashing(g.key) && turnSignal == TurnSignal.LEFT }
        GuidanceId.FLASH_TURN_RIGHT -> g.takeIf { approaches.containsKey(g.key) && isFlashing(g.key) && turnSignal == TurnSignal.RIGHT }
        GuidanceId.RAIN_SPEED, GuidanceId.RAIN_SPEED_CRUISE -> rainGuidance()
        GuidanceId.FLASH_PASSED_WITHOUT_STOP -> if (stopped()) g else {
            // 다시 움직이면 지금은 말하지 않고 다음 정차 때 다시 말한다
            if (pendingNotice == null) pendingNotice = queuedNotice
            null
        }
        GuidanceId.NIGHT_SUMMARY -> if (gear == Gear.PARK) g else {
            summarized = summarizedBefore
            null
        }
        GuidanceId.NIGHT_START, GuidanceId.NIGHT_START_NO_DATA -> g
    }

    private fun stopped(): Boolean = gear == Gear.PARK || (speed?.let { it <= STOP_KMH } == true)

    fun state(): NightState {
        val la = lat
        val lo = lon
        return NightState(
            night = isNight(),
            sun = sun(),
            hasLocation = la != null,
            nearbyTonight = if (la != null && lo != null) map().near(la, lo, NEARBY_METERS).size else null,
            approaching = announcedApproach(),
            wiping = isWipingSustained(),
            speedLimit = limit,
            wetLimit = wetLimit(),
        )
    }

    // ---- 밤 단위 기록 ----

    /** 정오부터 다음 날 정오까지를 한 밤으로 본다. */
    private fun nightOf(): LocalDate = Instant.ofEpochMilli(clock.nowMillis() - 12 * 3_600_000L).atZone(Clock.KOREA).toLocalDate()

    private fun rollSession() {
        val night = nightOf()
        if (session == night) return
        session = night
        _passages.clear()
        summarized = 0
        summarizedBefore = 0
        pendingNotice = null
        queuedNotice = null
    }

    // ---- 점멸 교차로 ----

    private fun isFlashing(id: String): Boolean = map()[id]?.window?.contains(clock.minuteOfDay()) == true

    private fun announcedApproach(): String? = approaches.entries.lastOrNull { it.value.announced }?.key

    private fun onEnter(id: String, ring: Ring) {
        map()[id] ?: return
        when (ring) {
            Ring.APPROACH -> if (!approaches.containsKey(id)) approaches[id] = Approach()
            Ring.INSIDE -> {
                insidePresent += id
                if (isFlashing(id)) {
                    insideActive += id
                    insideMin[id] = speed
                }
            }
        }
    }

    private fun onExit(id: String, ring: Ring) {
        when (ring) {
            Ring.APPROACH -> approaches.remove(id)
            Ring.INSIDE -> {
                insidePresent.remove(id)
                approaches[id]?.passed = true
                if (!insideActive.remove(id)) return
                val p = map()[id] ?: return
                val passage = Passage(id, p.name, clock.nowMillis(), p.window, insideMin.remove(id))
                _passages += passage
                if (passage.outcome == Passage.Outcome.PASSED) pendingNotice = passage
            }
        }
    }

    private fun forget(id: String) {
        approaches.remove(id)
        insidePresent.remove(id)
        insideActive.remove(id)
        insideMin.remove(id)
    }

    /**
     * 고리 안 교차로를 다시 본다. 점멸이 끝난 교차로는 지우고(통과 기록 없이), 아직 알리지 않았는데 지금 알릴 수 있으면 알린다.
     * 점멸 교차로가 촘촘한 곳에서 안내가 연달아 나오지 않게, 앞 교차로를 알린 고리 안에 있거나 [FLASH_GAP_MS] 안에 알렸으면 기다린다.
     */
    private fun refreshApproaches(out: MutableList<Guidance>) {
        // 고리 안에 있다는 사실은 남기고(점멸이 곧 시작될 수 있다), 점멸이 끝난 교차로는 알린 상태만 푼다
        approaches.forEach { (key, value) -> if (!isFlashing(key)) value.announced = false }
        insideActive.filter { !isFlashing(it) }.forEach { insideActive.remove(it); insideMin.remove(it) }
        // 안쪽 고리 안에서 기다리는 동안 점멸이 시작되면 그때부터 잰다
        insidePresent.filter { isFlashing(it) && it !in insideActive }.forEach { insideActive += it; insideMin[it] = speed }
        if (gear == Gear.PARK || gear == Gear.UNKNOWN) return
        val now = clock.nowMillis()
        if (approaches.values.any { it.announced }) return
        if (lastFlashAt?.let { now - it < FLASH_GAP_MS } == true) return
        val (id, a) = approaches.entries.firstOrNull { (key, value) ->
            !value.announced && !value.passed && isFlashing(key) && lastAnnounced[key]?.let { now - it < REANNOUNCE_MS } != true
        }?.toPair() ?: return
        a.announced = true
        lastAnnounced[id] = now
        lastFlashAt = now
        out += Guidance(GuidanceId.FLASH_AHEAD, key = id)
        if (turnSignal != TurnSignal.OFF) sayTurn(id, out)
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
        queuedNotice = p
        out += Guidance(GuidanceId.FLASH_PASSED_WITHOUT_STOP, listOf(p.minKmh ?: 0), key = p.intersectionId)
    }

    /** 새 기록이 있을 때만 말하고, 숫자는 오늘 밤 전체로 센다. */
    private fun summarize(out: MutableList<Guidance>) {
        if (_passages.size <= summarized) return
        summarizedBefore = summarized
        summarized = _passages.size
        out += Guidance(GuidanceId.NIGHT_SUMMARY, listOf(_passages.size, _passages.count { it.outcome == Passage.Outcome.STOPPED }))
    }

    // ---- 해 짐 ----

    private fun sun(): SunTimes? {
        val la = lat ?: return null
        val lo = lon ?: return null
        val date = clock.koreaDate()
        sunCache?.let { (d, s) -> if (d == date) return s }
        return SunTimes.of(date, la, lo).also { sunCache = date to it }
    }

    /** 위치를 모르면 18시~06시를 밤으로 본다(비 오는 밤 안내는 위치 없이도 동작한다). */
    private fun isNight(): Boolean {
        val minute = clock.minuteOfDay()
        return sun()?.isNight(minute) ?: (minute >= 18 * 60 || minute < 6 * 60)
    }

    private fun checkNightStart(out: MutableList<Guidance>) {
        if (gear != Gear.DRIVE) return
        val la = lat ?: return
        val lo = lon ?: return
        val m = map()
        // 데이터를 읽어 들이기 전에는 판단하지 않는다(빈 지도로 "정보 없음"을 말하지 않게)
        if (m.size == 0) return
        val sun = sun() ?: return
        val minute = clock.minuteOfDay()
        if (!sun.isNight(minute)) return
        // 새벽(일출 전)은 전날 밤으로 센다
        val today = clock.koreaDate()
        val night = if (minute < sun.sunriseMinute) today.minusDays(1) else today
        if (announcedNight == night) return
        announcedNight = night
        val count = m.near(la, lo, NEARBY_METERS).size
        out += if (count > 0) Guidance(GuidanceId.NIGHT_START, listOf(count)) else Guidance(GuidanceId.NIGHT_START_NO_DATA)
    }

    // ---- 비 오는 밤 ----

    /**
     * 지금 와이퍼가 돌고 있는지. 간헐, 연속 스위치는 돌고 있는 것으로 본다. 자동은 실제로 움직일 때만인데,
     * 자동 와이퍼는 닦는 사이에 잠깐 멈추므로 멈춘 지 [AUTO_GAP_MS]가 지나야 멈춘 것으로 본다. 한 번 닦기(MIST)는 비로 보지 않는다.
     */
    private fun wipingNow(now: Long): Boolean = when (wiperMode) {
        WiperMode.INTERMITTENT, WiperMode.CONTINUOUS -> true
        WiperMode.AUTO, WiperMode.OTHER -> wiperMoving || (motionOffSince?.let { now - it < AUTO_GAP_MS } == true)
        WiperMode.OFF, WiperMode.MIST -> false
    }

    /** 연속 작동 시간은 멈추면 처음부터 다시 센다. 2분 넘게 멈추면 비가 그친 것으로 보고 다음 비에 다시 말할 수 있게 한다. */
    private fun updateWiping() {
        val now = clock.nowMillis()
        if (wipingNow(now)) {
            notWipingSince = null
            if (wipingSince == null) wipingSince = now
        } else {
            wipingSince = null
            val off = notWipingSince ?: now.also { notWipingSince = it }
            if (now - off >= WIPER_OFF_RESET_MS) rainSaidForLimit.clear()
        }
    }

    private fun isWipingSustained(): Boolean {
        val since = wipingSince ?: return false
        return clock.nowMillis() - since >= WIPER_SUSTAIN_MS
    }

    private fun wetLimit(): Int? = if (isNight() && isWipingSustained() && limit > 0) limit * 8 / 10 else null

    /** 지금 상황의 비 오는 밤 안내. 조건이 안 되면 null. */
    private fun rainGuidance(): Guidance? {
        val wet = wetLimit() ?: return null
        if (gear != Gear.DRIVE) return null
        val target = cruiseTarget
        if (cruiseActive && target != null && target > wet) return Guidance(GuidanceId.RAIN_SPEED_CRUISE, listOf(limit, wet, target))
        // 지금 속도가 기준보다 높은 상태가 [OVER_SUSTAIN_MS] 넘게 이어질 때만(잠깐 넘는 것은 말하지 않는다)
        val since = overSince ?: return null
        if (clock.nowMillis() - since >= OVER_SUSTAIN_MS) return Guidance(GuidanceId.RAIN_SPEED, listOf(limit, wet))
        return null
    }

    private fun checkRain(out: MutableList<Guidance>) {
        val now = clock.nowMillis()
        val wet = wetLimit()
        val s = speed
        overSince = if (wet != null && gear == Gear.DRIVE && s != null && s > wet) (overSince ?: now) else null
        if (limit in rainSaidForLimit) return
        val g = rainGuidance() ?: return
        rainSaidForLimit += limit
        out += g
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
        const val AUTO_GAP_MS = 8_000L
        const val OVER_SUSTAIN_MS = 5_000L
    }
}
