package com.yahaeng.android.night

import com.yahaeng.android.drive.DriveSignalHub
import com.yahaeng.android.drive.RawSignal
import com.yahaeng.android.drive.SignalMapper
import com.yahaeng.android.drive.VehicleStatus
import com.yahaeng.android.location.ProximityZones
import com.yahaeng.android.rules.Clock
import com.yahaeng.android.rules.Crossing
import com.yahaeng.android.rules.CrosswalkMap
import com.yahaeng.android.rules.DarkCrosswalk
import com.yahaeng.android.rules.FlashMap
import com.yahaeng.android.rules.FlashingIntersection
import com.yahaeng.android.rules.Guidance
import com.yahaeng.android.rules.GuidanceId
import com.yahaeng.android.rules.NightEngine
import com.yahaeng.android.rules.NightEvent
import com.yahaeng.android.rules.NightState
import com.yahaeng.android.rules.Passage
import com.yahaeng.android.rules.ProximityPlanner
import com.yahaeng.android.rules.minuteOfDay
import com.yahaeng.android.voice.GuidanceSpeaker
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/** 이 근처 데이터 한 지역. */
data class RegionInfo(val region: String, val baseDate: String, val count: Int)

/** 화면에 보여 줄 상태. */
data class NightUi(
    val engine: NightState = NightState(),
    val vehicle: VehicleStatus = VehicleStatus(),
    val approaching: FlashingIntersection? = null,
    /** 다가가는 교차로까지 직선거리(m). */
    val distanceMeters: Int? = null,
    val turnLeft: Boolean = false,
    val turnRight: Boolean = false,
    val cruiseTarget: Int? = null,
    val passages: List<Passage> = emptyList(),
    /** 안내한 뒤 다가가는 어두운 횡단보도와 거리(m). */
    val crosswalk: DarkCrosswalk? = null,
    val crosswalkDistanceMeters: Int? = null,
    val crossings: List<Crossing> = emptyList(),
    /** 이 근처(10km) 점멸 데이터 지역. 비었으면 이 지역은 점멸 정보가 없다. */
    val regions: List<RegionInfo> = emptyList(),
    /** 이 근처(10km) 어두운 횡단보도 데이터 지역. */
    val crosswalkRegions: List<RegionInfo> = emptyList(),
    val dataRegions: Int = 0,
    val dataSize: Int = 0,
    val crosswalkDataSize: Int = 0,
)

/**
 * 밤 안내 세션. 앱 프로세스에 하나이고 화면과 상관없이 돈다(포그라운드 서비스가 프로세스를 붙잡는다).
 *
 * 신호 → [SignalMapper] → [NightEngine] → 문장([GuidanceTexts]) → [GuidanceSpeaker]. 위치가 바뀌면
 * [ProximityPlanner]로 근접 알림을 다시 고르고 [ProximityZones]에 건다.
 * 메인 스레드에서만 동작한다.
 */
class NightController(
    private val hub: DriveSignalHub,
    private val zones: ProximityZones,
    private val map: StateFlow<FlashMap>,
    private val crosswalks: StateFlow<CrosswalkMap>,
    private val speaker: GuidanceSpeaker,
    private val texts: GuidanceTexts,
    private val settings: Settings,
    private val popup: FlashPopup?,
    private val screenVisible: () -> Boolean,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val engine = NightEngine({ map.value }, clock, { crosswalks.value })

    private val _ui = MutableStateFlow(NightUi())
    val ui: StateFlow<NightUi> = _ui.asStateFlow()

    val caption get() = speaker.current

    private var lat: Double? = null
    private var lon: Double? = null
    private var plannedAt: Pair<Double, Double>? = null
    private var plannedMinute = -1
    private var plannedMap: FlashMap? = null
    private var turn = com.yahaeng.android.rules.TurnSignal.OFF
    private var cruiseTarget: Int? = null
    private var job: Job? = null

    /** 여러 번 불러도 한 번만 시작한다. */
    fun start() {
        if (job != null) return
        hub.start()
        // 기다리던 안내는 재생 직전에 지금도 맞는지 다시 본다(교차로를 벗어났거나 방향, 와이퍼, 제한 속도가 바뀌었을 수 있다)
        speaker.revalidate = { g, _ ->
            engine.revalidate(g)?.takeIf { allowedVoice(it) }?.let { it to texts.text(it, map.value) }
        }
        speaker.start()
        job = scope.launch {
            launch {
                hub.signals.collect { signal -> onSignal(signal) }
            }
            while (isActive) {
                delay(1_000)
                handle(engine.tick())
                replanIfNeeded()
                publish()
            }
        }
    }

    private fun onSignal(signal: RawSignal) {
        val event = SignalMapper.map(signal) ?: return publish()
        when (event) {
            is NightEvent.LocationChanged -> {
                lat = event.lat
                lon = event.lon
            }
            is NightEvent.TurnSignalChanged -> turn = event.signal
            is NightEvent.CruiseTargetChanged -> cruiseTarget = event.kmh
            else -> Unit
        }
        handle(engine.on(event))
        if (event is NightEvent.LocationChanged) replanIfNeeded()
        publish()
    }

    private fun handle(guidance: List<Guidance>) {
        val s = settings.state.value
        for (g in guidance) {
            // 음성과 팝업은 따로 켜고 끈다
            if (g.id == GuidanceId.FLASH_AHEAD && s.popup && !screenVisible()) {
                popup?.show(texts.popupTitle(), texts.popupText())
            }
            if (!allowedVoice(g)) continue
            // 비 오는 밤 안내는 기다리는 것을 하나만 둔다(제한 속도가 바뀌어 새로 나오면 앞의 것을 버린다)
            if (g.id in RAIN_IDS) RAIN_IDS.forEach(speaker::drop)
            // 안내 종류만 남긴다(위치와 문장 내용은 로그에 남기지 않는다)
            Log.i(TAG, "say ${g.id}")
            speaker.say(g, texts.text(g, map.value), KOREAN)
        }
    }

    private fun allowedVoice(g: Guidance): Boolean {
        val s = settings.state.value
        return when (g.id) {
            in FLASH_IDS -> s.flashVoice
            GuidanceId.RAIN_SPEED, GuidanceId.RAIN_SPEED_CRUISE -> s.rain
            GuidanceId.DARK_CROSSWALK -> s.crosswalk
            else -> true
        }
    }

    /** 처음 위치, [ProximityPlanner.REPLAN_METERS] 넘게 움직임, 또는 5분마다 근접 알림을 다시 고른다. */
    private fun replanIfNeeded() {
        val la = lat ?: return
        val lo = lon ?: return
        val minute = clock.minuteOfDay()
        val m = map.value
        val moved = plannedAt?.let { FlashMap.distanceMeters(it.first, it.second, la, lo) >= ProximityPlanner.REPLAN_METERS } ?: true
        val timeChanged = plannedMinute < 0 || Math.floorMod(minute - plannedMinute, 1440) >= 5
        // 데이터를 다 읽어 들인 직후에도 바로 다시 고른다
        if (!moved && !timeChanged && plannedMap === m) return
        plannedAt = la to lo
        plannedMinute = minute
        plannedMap = m
        zones.update(ProximityPlanner.plan(m, la, lo, minute))
    }

    private fun publish() {
        val state = engine.state()
        val m = map.value
        val approaching = state.approaching?.let { m[it] }
        val la = lat
        val lo = lon
        val distance = if (approaching != null && la != null && lo != null) {
            FlashMap.distanceMeters(la, lo, approaching.lat, approaching.lon).toInt()
        } else null
        val regions = if (la != null && lo != null) {
            m.near(la, lo, NightEngine.NEARBY_METERS).groupBy { it.first.region }
                .map { (region, list) -> RegionInfo(region, list.first().first.baseDate, list.size) }
                .sortedByDescending { it.count }
        } else emptyList()
        val cm = crosswalks.value
        val crosswalk = state.crosswalk?.let { cm[it] }
        val crosswalkDistance = if (crosswalk != null && la != null && lo != null) {
            FlashMap.distanceMeters(la, lo, crosswalk.lat, crosswalk.lon).toInt()
        } else null
        val crosswalkRegions = if (la != null && lo != null) {
            cm.near(la, lo, NightEngine.NEARBY_METERS).groupBy { it.first.region }
                .map { (region, list) -> RegionInfo(region, list.first().first.baseDate, list.size) }
                .sortedByDescending { it.count }
        } else emptyList()
        _ui.value = NightUi(
            engine = state,
            vehicle = hub.vehicle.value,
            approaching = approaching,
            distanceMeters = distance,
            turnLeft = approaching != null && turn == com.yahaeng.android.rules.TurnSignal.LEFT,
            turnRight = approaching != null && turn == com.yahaeng.android.rules.TurnSignal.RIGHT,
            cruiseTarget = cruiseTarget,
            // 오늘 밤 기록(엔진이 밤 단위로 지운다). 기록은 메모리에만 있어 앱 프로세스가 끝나면 사라진다
            passages = engine.passages.reversed(),
            crosswalk = crosswalk,
            crosswalkDistanceMeters = crosswalkDistance,
            crossings = engine.crossings.reversed(),
            regions = regions,
            crosswalkRegions = crosswalkRegions,
            dataRegions = m.intersections.map { it.region }.distinct().size,
            dataSize = m.size,
            crosswalkDataSize = cm.size,
        )
    }

    private companion object {
        const val TAG = "YahaengNight"
        val KOREAN: Locale = Locale.KOREA
        val RAIN_IDS = setOf(GuidanceId.RAIN_SPEED, GuidanceId.RAIN_SPEED_CRUISE)
        val FLASH_IDS = setOf(GuidanceId.FLASH_AHEAD, GuidanceId.FLASH_TURN_LEFT, GuidanceId.FLASH_TURN_RIGHT, GuidanceId.FLASH_PASSED_WITHOUT_STOP)
    }
}
