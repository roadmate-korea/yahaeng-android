package com.yahaeng.android.vehicle

import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import com.yahaeng.android.drive.DriveSignalSource
import com.yahaeng.android.drive.RawSignal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 기어 → [RawSignal.Vehicle.Gear], 읽을 수 없으면 [RawSignal.Vehicle.Unavailable](화면을 잠근다).
 *
 * Android Automotive 표준 `CarPropertyManager`의 `GEAR_SELECTION`을 읽는다([carPropertyEvents]).
 * Pleos 공개 문서의 Vehicle SDK에는 기어 API가 없고, SDK API 정책이 기어 같은 표준 차량 정보는 AAOS Car API로 읽게 한다.
 * 권한은 `android.car.permission.CAR_POWERTRAIN`(설치 때 주는 권한).
 *
 * Android 14는 기어가 읽기 불가로 바뀌어도 콜백을 보내지 않을 수 있어 [POLL_MS]마다 다시 읽고, [TIMEOUT_MS] 동안 확인이
 * 안 되면 잠근다([toGearSignals]).
 *
 * 앱은 이 Flow를 [com.yahaeng.android.drive.DriveSignalHub]에서 프로세스 수명 동안 한 번만 수집한다.
 */
class VehicleGearSource(context: Context) : DriveSignalSource {

    private val appContext = context.applicationContext

    override fun signals(): Flow<RawSignal> =
        carPropertyEvents(appContext, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE, TAG, POLL_MS)
            .map { event ->
                when (event) {
                    is CarPropertyEvent.Value -> {
                        val gear = event.value.value as? Int
                        if (event.value.status == CarPropertyValue.STATUS_AVAILABLE && gear != null) {
                            GearReading.Value(gear, confirmed = event.polled)
                        } else {
                            GearReading.Unavailable("status ${event.value.status}")
                        }
                    }
                    is CarPropertyEvent.Unavailable -> GearReading.Unavailable(event.reason)
                }
            }
            .toGearSignals(TIMEOUT_MS)

    private companion object {
        const val TAG = "YahaengGear"
        const val POLL_MS = 2_000L
        const val TIMEOUT_MS = 5_000L
    }
}
