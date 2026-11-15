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

/** 밤에 어두운 횡단보도를 지난 사실 한 건(안내했는지와 상관없이). 점수가 아니라 기록이다. */
data class Crossing(
    val crosswalkId: String,
    val road: String,
    val atMillis: Long,
    /** 횡단보도 [NightEngine.CROSSWALK_INSIDE_METERS] 안에서 가장 낮았던 속도. 속도를 읽지 못했으면 null. */
    val minKmh: Int?,
) {
    enum class Outcome { SLOWED, PASSED, UNKNOWN }

    val outcome: Outcome
        get() = when {
            minKmh == null -> Outcome.UNKNOWN
            minKmh <= NightEngine.CROSSWALK_SLOW_KMH -> Outcome.SLOWED
            else -> Outcome.PASSED
        }
}

/** 화면에 보여 줄 판단 상태. */
data class NightState(
    val night: Boolean = false,
    val sun: SunTimes? = null,
    val hasLocation: Boolean = false,
    /** 지금 다가가는 점멸 교차로. */
    val approaching: String? = null,
    /** 안내한 뒤 지금 다가가는 어두운 횡단보도. */
    val crosswalk: String? = null,
    val wiping: Boolean = false,
    val speedLimit: Int = 0,
    /** 와이퍼가 계속 돌 때의 젖은 노면 기준 속도. 조건이 안 되면 null. */
    val wetLimit: Int? = null,
)

/**
 * 밤에 바뀌는 규칙을 판단한다(순수 Kotlin, Android import 금지).
 *
 * 1. 점멸 교차로: 바깥 고리(300m) 안에 있는 동안 그 교차로가 점멸 중이고 차가 P가 아니면 한 번 알린다.
 *    진행 방향 앞쪽(좌우 [HeadingTracker.AHEAD_DEGREES] 안)에 있는 교차로만 알린다. 옆 골목이나 지나온 교차로는 말하지 않는다.
 *    고리에 들어온 뒤에 점멸이 시작되거나 출발하거나 방향을 틀어 앞쪽이 되면 그때 알린다. 방향지시등에 따라 한 문장을 더한다.
 *    안쪽 고리(70m) 안에서 가장 낮은 속도를 기록하고, 서행도 하지 않고 지났으면 차가 멈췄을 때 사실을 한 번 말한다.
 * 2. 비 오는 밤: 밤에 와이퍼가 [WIPER_SUSTAIN_MS] 넘게 쉬지 않고 돌고 내비 제한 속도를 알면 20% 줄인 속도를 계산한다
 *    (도로교통법 시행규칙 제19조 제2항, 비가 내려 노면이 젖은 경우). 크루즈 목표 속도나 지금 속도가 그보다 높을 때 한 번 말한다.
 *    앱은 실제 노면을 모르므로 "노면이 젖었다면"이라는 조건을 붙여 말한다.
 * 3. 어두운 횡단보도: 밤에 보행자 신호등도 집중 조명도 없는 횡단보도가 진행 방향 앞쪽 [CROSSWALK_RING_METERS] 안에 있고
 *    차가 시속 [CROSSWALK_MIN_KMH]km 넘게 달리면 한 번 알린다. 점멸 안내와 같은 규칙으로 연달아 말하지 않는다.
 *    횡단보도 [CROSSWALK_INSIDE_METERS] 안에서 가장 낮은 속도를 기록한다.
 * 4. 해가 지고 처음 주행할 때 밤 안내를 켠다고 한 번 말한다. 근처 교차로 수 같은 숫자는 말하지 않는다.
 * 5. P에 두면 오늘 밤 지난 점멸 교차로를 사실로 요약한다. 기록은 밤 단위(정오~다음 날 정오)로 두고 다음 밤이 되면 지운다.
 *
 * 시간에 따른 판단을 위해 1초마다 [tick]을 부른다. 음성 대기열이 재생 직전에 [revalidate]로 안내가 아직 맞는지 묻는다.
 */
class NightEngine(
    private val map: () -> FlashMap,
    private val clock: Clock = Clock.SYSTEM,
    private val crosswalks: () -> CrosswalkMap = { CrosswalkMap.EMPTY },
) {
    private val headingTracker = HeadingTracker()
    private var lat: Double? = null
    private var lon: Double? = null
    private var gear = Gear.UNKNOWN
    private var speed: Int? = null
    private var turnSignal = TurnSignal.OFF

    private var announcedNight: LocalDate? = null

    // 어두운 횡단보도
    private val crosswalkSaidAt = mutableMapOf<String, Long>()
    private var lastCrosswalkAt: Long? = null

    /** 안내한 뒤 아직 고리 안에 있는 횡단보도. 한 번에 하나만 둔다(고리 안에서는 다른 횡단보도를 말하지 않는다). */
    private var crosswalk: String? = null
    private var crosswalkPassed = false

    /** 지금 지나는 중인 횡단보도(안내와 상관없이). [within]일 때만 속도를 잰다. */
    private class CrossingTrack(var minKmh: Int?, var within: Boolean)

    private val crossingTracks = linkedMapOf<String, CrossingTrack>()
    /** 기록을 마친 뒤 아직 [RECROSS_CLEAR_METERS] 밖으로 나가지 않은 횡단보도. 위치가 흔들려 다시 들어와도 한 번 더 기록하지 않는다. */
    private val justCrossed = mutableSetOf<String>()
    private val _crossings = mutableListOf<Crossing>()

    /** 오늘 밤 지난 어두운 횡단보도. */
    val crossings: List<Crossing> get() = _crossings.toList()
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
                headingTracker.add(event.lat, event.lon, clock.nowMillis())
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
                if (event.kmh != null) crossingTracks.values.filter { it.within }.forEach { t -> t.minKmh = t.minKmh?.let { minOf(it, event.kmh) } ?: event.kmh }
                if (event.kmh != null && event.kmh <= STOP_KMH && insideActive.isEmpty()) deliverNotice(out)
            }
            is NightEvent.TurnSignalChanged -> {
                turnSignal = event.signal
                if (event.signal != TurnSignal.OFF) announcedApproach()?.takeIf { isAhead(intersectionPoint(it)) }?.let { id -> sayTurn(id, out) }
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
        refreshCrosswalk(out)
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
        GuidanceId.FLASH_AHEAD -> when {
            approaches[g.key]?.announced != true || !isFlashing(g.key) -> null
            !isAhead(intersectionPoint(g.key)) -> {
                // 기다리는 동안 방향을 틀어 교차로가 뒤나 옆이 됐다. 다시 앞쪽이 되면 그때 알린다(방향지시등 문장도 그때 다시)
                approaches[g.key]?.let { it.announced = false; it.turnsSaid.clear() }
                lastAnnounced.remove(g.key)
                null
            }
            else -> g
        }
        GuidanceId.DARK_CROSSWALK -> g.takeIf { crosswalkStillValid(g.key) }
        GuidanceId.FLASH_TURN_LEFT -> g.takeIf { turnStillValid(g.key, TurnSignal.LEFT) }
        GuidanceId.FLASH_TURN_RIGHT -> g.takeIf { turnStillValid(g.key, TurnSignal.RIGHT) }
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
        GuidanceId.NIGHT_START -> g
    }

    /** 방향지시등 문장은 그 교차로를 알린 상태이고, 아직 점멸 중이고, 아직 앞쪽이고, 같은 방향일 때만. */
    private fun turnStillValid(id: String, signal: TurnSignal): Boolean =
        approaches[id]?.announced == true && isFlashing(id) && turnSignal == signal && isAhead(intersectionPoint(id))

    /** 기다리던 횡단보도 안내가 지금도 맞는지. 만들 때의 조건을 다시 보고, 그 뒤에 점멸 교차로를 알렸으면 버린다. */
    private fun crosswalkStillValid(id: String): Boolean {
        if (crosswalk != id || crosswalkPassed || id in crossingTracks || !isNight()) return false
        if (gear == Gear.PARK || gear == Gear.UNKNOWN) return false
        if ((speed ?: return false) <= CROSSWALK_MIN_KMH) return false
        if (approaches.values.any { it.announced }) return false
        val saidAt = crosswalkSaidAt[id] ?: return false
        if (lastFlashAt?.let { it >= saidAt } == true) return false
        return isAhead(crosswalks()[id]?.let { it.lat to it.lon }, whenUnknown = false)
    }

    private fun intersectionPoint(id: String): Pair<Double, Double>? = map()[id]?.let { it.lat to it.lon }

    private fun stopped(): Boolean = gear == Gear.PARK || (speed?.let { it <= STOP_KMH } == true)

    fun state(): NightState {
        val la = lat
        val lo = lon
        return NightState(
            night = isNight(),
            sun = sun(),
            hasLocation = la != null,
            approaching = announcedApproach(),
            crosswalk = crosswalk?.takeIf { !crosswalkPassed },
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
        _crossings.clear()
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
            !value.announced && !value.passed && isFlashing(key) && lastAnnounced[key]?.let { now - it < REANNOUNCE_MS } != true &&
                isAhead(intersectionPoint(key))
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

    // ---- 진행 방향 ----

    /**
     * [target]이 진행 방향 앞쪽인지. 진행 방향을 아직 모르면(위치가 하나뿐이거나 오래 멈춰 있었다) [whenUnknown]을 돌려준다.
     * 점멸 교차로는 규칙이 바뀌는 곳이라 방향을 모를 때도 말하는 편을 고르고, 어두운 횡단보도는 보조 안내라 말하지 않는다
     * (시속 30km 넘게 달리면 1~2초 안에 방향이 잡힌다). 아주 가까우면 방위각이 흔들려 앞쪽으로 본다.
     */
    private fun isAhead(target: Pair<Double, Double>?, whenUnknown: Boolean = true): Boolean {
        target ?: return false
        val la = lat ?: return whenUnknown
        val lo = lon ?: return whenUnknown
        val h = headingTracker.heading(clock.nowMillis()) ?: return whenUnknown
        if (Geo.distanceMeters(la, lo, target.first, target.second) < HeadingTracker.MIN_METERS) return true
        return Geo.angleBetween(h, Geo.bearing(la, lo, target.first, target.second)) <= HeadingTracker.AHEAD_DEGREES
    }

    // ---- 어두운 횡단보도 ----

    /** 지나는 횡단보도를 기록하고, 안내한 횡단보도를 따라가고, 지금 알릴 횡단보도가 있으면 알린다. */
    private fun refreshCrosswalk(out: MutableList<Guidance>) {
        val la = lat ?: return
        val lo = lon ?: return
        val cm = crosswalks()
        trackCrossings(cm, la, lo)
        crosswalk?.let { id -> followCrosswalk(id, cm, la, lo) }
        if (crosswalk != null || cm.size == 0) return
        if (gear == Gear.PARK || gear == Gear.UNKNOWN || !isNight()) return
        val s = speed ?: return
        if (s <= CROSSWALK_MIN_KMH) return
        // 점멸 교차로를 알린 고리 안이거나, 바로 앞에 다른 안내를 했으면 쉰다
        if (approaches.values.any { it.announced }) return
        val now = clock.nowMillis()
        if (listOfNotNull(lastFlashAt, lastCrosswalkAt).any { now - it < FLASH_GAP_MS }) return
        val (c, _) = cm.near(la, lo, CROSSWALK_RING_METERS).firstOrNull { (c, d) ->
            d > CROSSWALK_INSIDE_METERS && c.id !in crossingTracks && crosswalkSaidAt[c.id]?.let { now - it < REANNOUNCE_MS } != true &&
                isAhead(c.lat to c.lon, whenUnknown = false)
        } ?: return
        crosswalk = c.id
        crosswalkPassed = false
        crosswalkSaidAt[c.id] = now
        lastCrosswalkAt = now
        out += Guidance(GuidanceId.DARK_CROSSWALK, key = c.id)
    }

    /**
     * 밤에 어두운 횡단보도 [CROSSWALK_INSIDE_METERS] 안에 들어가면 속도를 재기 시작하고, [EXIT_MARGIN_METERS]만큼 더 멀어지면 기록한다.
     * 속도는 30m 안에 있을 때만 잰다(기록을 확정하는 여유 거리 안에서 줄인 속도는 넣지 않는다). 안내했는지와 상관없이 기록한다.
     */
    private fun trackCrossings(cm: CrosswalkMap, la: Double, lo: Double) {
        val now = clock.nowMillis()
        val iter = crossingTracks.entries.iterator()
        while (iter.hasNext()) {
            val (id, t) = iter.next()
            val c = cm[id]
            if (c == null) {
                iter.remove()
                continue
            }
            val d = Geo.distanceMeters(la, lo, c.lat, c.lon)
            val within = d <= CROSSWALK_INSIDE_METERS
            // 30m 밖에 나갔다가 다시 들어오면 그 순간의 속도도 넣는다
            if (within && !t.within) speed?.let { v -> t.minKmh = t.minKmh?.let { minOf(it, v) } ?: v }
            t.within = within
            if (d > CROSSWALK_INSIDE_METERS + EXIT_MARGIN_METERS) {
                _crossings += Crossing(id, c.road, now, t.minKmh)
                justCrossed += id
                if (crosswalk == id) crosswalkPassed = true
                iter.remove()
            }
        }
        // 충분히 멀어진 뒤 다시 오면 새로 지나는 것으로 본다(10분 안이어도)
        justCrossed.removeAll { id -> cm[id]?.let { Geo.distanceMeters(la, lo, it.lat, it.lon) > RECROSS_CLEAR_METERS } ?: true }
        if (gear == Gear.PARK || gear == Gear.UNKNOWN || !isNight()) return
        cm.near(la, lo, CROSSWALK_INSIDE_METERS).forEach { (c, _) ->
            if (c.id !in crossingTracks && c.id !in justCrossed) {
                crossingTracks[c.id] = CrossingTrack(speed, within = true)
            }
        }
    }

    /** 안내한 횡단보도의 고리를 벗어나면 끝낸다(지나지 않고 다른 길로 빠졌으면 기록도 없다). */
    private fun followCrosswalk(id: String, cm: CrosswalkMap, la: Double, lo: Double) {
        val c = cm[id]
        if (c == null || Geo.distanceMeters(la, lo, c.lat, c.lon) > CROSSWALK_RING_METERS + EXIT_MARGIN_METERS) {
            crosswalk = null
            crosswalkPassed = false
        }
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
        val sun = sun() ?: return
        val minute = clock.minuteOfDay()
        if (!sun.isNight(minute)) return
        // 새벽(일출 전)은 전날 밤으로 센다
        val today = clock.koreaDate()
        val night = if (minute < sun.sunriseMinute) today.minusDays(1) else today
        if (announcedNight == night) return
        announcedNight = night
        out += Guidance(GuidanceId.NIGHT_START)
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

        /** 데이터 화면에서 이 근처 데이터로 보여 주는 반경. 음성으로는 말하지 않는다. */
        const val NEARBY_METERS = 10_000.0
        const val REANNOUNCE_MS = 10 * 60_000L

        /** 서로 다른 점멸 교차로 안내 사이 최소 간격. */
        const val FLASH_GAP_MS = 30_000L
        const val WIPER_SUSTAIN_MS = 10_000L
        const val WIPER_OFF_RESET_MS = 120_000L
        const val AUTO_GAP_MS = 8_000L
        const val OVER_SUSTAIN_MS = 5_000L

        const val CROSSWALK_RING_METERS = 150.0
        const val CROSSWALK_INSIDE_METERS = 30.0

        /** 이 속도 이하로 다가가면 말하지 않는다. 기록에서도 이 속도 이하면 줄인 것으로 본다. */
        const val CROSSWALK_MIN_KMH = 30
        const val CROSSWALK_SLOW_KMH = CROSSWALK_MIN_KMH

        /** 고리를 나갔다고 보기 전에 더 멀어져야 하는 거리(위치 흔들림 때문). */
        const val EXIT_MARGIN_METERS = 10.0

        /** 기록한 횡단보도에서 이만큼 멀어져야 다음에 다시 지날 때 새로 기록한다. */
        const val RECROSS_CLEAR_METERS = 100.0
    }
}
