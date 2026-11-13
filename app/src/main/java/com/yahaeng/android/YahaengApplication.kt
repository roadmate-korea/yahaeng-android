package com.yahaeng.android

import android.app.Application
import android.content.pm.PackageManager
import android.util.Log
import com.yahaeng.android.drive.DriveSignalHub
import com.yahaeng.android.drive.MergedSignalSource
import com.yahaeng.android.drive.RawSignal
import com.yahaeng.android.location.LocationSignalSource
import com.yahaeng.android.location.ProximityZones
import com.yahaeng.android.navi.NaviSignalSource
import com.yahaeng.android.night.FlashPopup
import com.yahaeng.android.night.GuidanceTexts
import com.yahaeng.android.night.NightController
import com.yahaeng.android.night.Settings
import com.yahaeng.android.rules.FlashCsv
import com.yahaeng.android.rules.FlashMap
import com.yahaeng.android.demo.DemoSupport
import com.yahaeng.android.rules.Clock
import com.yahaeng.android.vehicle.VehicleGearSource
import com.yahaeng.android.vehicle.VehicleSignalSource
import com.yahaeng.android.vehicle.VehicleSpeedSource
import com.yahaeng.android.voice.GuidanceSpeaker
import com.yahaeng.android.voice.PleosTtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** 앱 전체에서 하나만 쓰는 객체(간단한 수동 의존성 주입). */
class YahaengApplication : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 판단 로직이 쓰는 시계. 디버그 빌드는 시연 입력이 시각을 옮길 수 있고, release는 늘 실제 시각이다. */
    val clock: Clock = DemoSupport.clock

    val settings: Settings by lazy { Settings(this) }

    val speedPermissionGranted: MutableStateFlow<Boolean> by lazy { MutableStateFlow(hasPermission(CAR_SPEED)) }
    val locationPermissionGranted: MutableStateFlow<Boolean> by lazy { MutableStateFlow(hasPermission(FUSED_LOCATION)) }

    fun hasPermission(name: String) = checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    /** 이 프로세스에서 권한 창을 이미 띄웠는지(한 번만 묻는다). */
    var permissionsAsked = false

    /** 야행 화면이 지금 보이는지. 보이면 알림 팝업을 띄우지 않는다. */
    @Volatile
    var screenVisible = false


    private val _flashMap = MutableStateFlow(FlashMap.EMPTY)
    val flashMap: StateFlow<FlashMap> = _flashMap.asStateFlow()

    private var mapLoading = false

    private fun loadFlashMap() {
        if (mapLoading) return
        mapLoading = true
        appScope.launch(Dispatchers.IO) {
            runCatching { assets.open(FLASH_FILE).bufferedReader().useLines { FlashCsv.parse(it) } }
                .onSuccess { _flashMap.value = it }
                .onFailure { Log.w("YahaengData", "flashing signals not loaded", it) }
        }
    }

    val zones: ProximityZones by lazy { ProximityZones(this) }

    val signalHub: DriveSignalHub by lazy {
        DriveSignalHub(
            MergedSignalSource(
                VehicleGearSource(this),
                VehicleSignalSource(this),
                // 시연 제한 속도가 있으면(디버그 빌드만) 에뮬레이터 내비의 0을 받지 않는다
                { NaviSignalSource(this).signals().filter { it !is RawSignal.Navi.SpeedLimit || !DemoSupport.overridesSpeedLimit } },
                VehicleSpeedSource(this, speedPermissionGranted),
                LocationSignalSource(this, locationPermissionGranted),
                zones,
                { DemoSupport.input },
            ),
            appScope,
        )
    }

    val controller: NightController by lazy {
        loadFlashMap()
        NightController(
            hub = signalHub,
            zones = zones,
            map = flashMap,
            speaker = GuidanceSpeaker(PleosTtsEngine(this), appScope, clock),
            texts = GuidanceTexts(this),
            settings = settings,
            popup = FlashPopup(this),
            screenVisible = { screenVisible },
            clock = clock,
            scope = appScope,
        )
    }

    /** 앱 데이터 요약(첫 화면 표시용). 지도 로딩 전에도 부를 수 있게 따로 읽는다. */
    fun ensureMapLoaded() = loadFlashMap()

    companion object {
        const val CAR_SPEED = "android.car.permission.CAR_SPEED"
        const val FUSED_LOCATION = "pleos.car.permission.FUSED_LOCATION"
        const val FLASH_FILE = "flashing_signals.csv"
    }
}
