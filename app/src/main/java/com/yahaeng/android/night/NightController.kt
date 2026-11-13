package com.yahaeng.android.night

import com.yahaeng.android.drive.DriveSignalHub
import com.yahaeng.android.drive.RawSignal
import com.yahaeng.android.drive.SignalMapper
import com.yahaeng.android.drive.VehicleStatus
import com.yahaeng.android.location.ProximityZones
import com.yahaeng.android.rules.Clock
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
    val regions: List<RegionInfo> = emptyList(),
    val dataRegions: Int = 0,
    val dataSize: Int = 0,
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
    private val speaker: GuidanceSpeaker,
    private val texts: GuidanceTexts,
    private val settings: Settings,
    private val popup: FlashPopup?,
    private val screenVisible: () -> Boolean,
    private val clock: Clock,
    private val scope: CoroutineScope,
) {
    private val engine = NightEngine({ map.value }, clock)

    private val _ui = MutableStateFlow(NightUi())
    val ui: StateFlow<NightUi> = _ui.asStateFlow()

    val caption get() = speaker.current

    private var lat: Double? = null
    private var lon: Double? = null
    private var plannedAt: Pair<Double, Double>? = null
    private var plannedMinute = -1
    private var turn = com.yahaeng.android.rules.TurnSignal.OFF
    private var cruiseTarget: Int? = null
    private var job: Job? = null

    /** 여러 번 불러도 한 번만 시작한다. */
    fun start() {
        if (job != null) return
        hub.start()
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
            val flash = g.id in FLASH_IDS
            val rain = g.id == GuidanceId.RAIN_SPEED || g.id == GuidanceId.RAIN_SPEED_CRUISE
            if (flash && !s.flashVoice) continue
            if (rain && !s.rain) continue
            // 안내 종류만 남긴다(위치와 문장 내용은 로그에 남기지 않는다)
            Log.i(TAG, "say ${g.id}")
            speaker.say(g, texts.text(g, map.value), KOREAN)
            if (g.id == GuidanceId.FLASH_AHEAD && s.popup && !screenVisible()) {
                popup?.show(texts.popupTitle(), texts.popupText())
            }
        }
    }

    /** 처음 위치, [ProximityPlanner.REPLAN_METERS] 넘게 움직임, 또는 5분마다 근접 알림을 다시 고른다. */
    private fun replanIfNeeded() {
        val la = lat ?: return
        val lo = lon ?: return
        val minute = clock.minuteOfDay()
        val moved = plannedAt?.let { FlashMap.distanceMeters(it.first, it.second, la, lo) >= ProximityPlanner.REPLAN_METERS } ?: true
        val timeChanged = plannedMinute < 0 || Math.floorMod(minute - plannedMinute, 1440) >= 5
        if (!moved && !timeChanged) return
        plannedAt = la to lo
        plannedMinute = minute
        zones.update(ProximityPlanner.plan(map.value, la, lo, minute))
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
        val now = clock.nowMillis()
        val regions = if (la != null && lo != null) {
            m.near(la, lo, NightEngine.NEARBY_METERS).groupBy { it.first.region }
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
            // 오늘 밤 기록만(18시간 안). 기록은 메모리에만 있어 앱 프로세스가 끝나면 사라진다
            passages = engine.passages.filter { now - it.atMillis < 18 * 3_600_000L }.reversed(),
            regions = regions,
            dataRegions = m.intersections.map { it.region }.distinct().size,
            dataSize = m.size,
        )
    }

    private companion object {
        const val TAG = "YahaengNight"
        val KOREAN: Locale = Locale.KOREA
        val FLASH_IDS = setOf(GuidanceId.FLASH_AHEAD, GuidanceId.FLASH_TURN_LEFT, GuidanceId.FLASH_TURN_RIGHT, GuidanceId.FLASH_PASSED_WITHOUT_STOP)
    }
}
