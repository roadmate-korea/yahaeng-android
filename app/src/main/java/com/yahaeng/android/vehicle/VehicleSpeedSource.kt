package com.yahaeng.android.vehicle

import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import com.yahaeng.android.drive.DriveSignalSource
import com.yahaeng.android.drive.RawSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take

/**
 * 차량 속도 → [RawSignal.Vehicle.Speed], 읽을 수 없으면 [RawSignal.Vehicle.SpeedUnavailable].
 *
 * Android Automotive 표준 `CarPropertyManager`의 `PERF_VEHICLE_SPEED`(m/s)를 읽는다([carPropertyEvents]).
 * 권한은 `android.car.permission.CAR_SPEED`(사용자 허용이 필요한 권한).
 *
 * 속도는 있으면 좋은 정보다. 읽지 못해도(권한 거부, 차량 서비스 없음·끊김) 다른 안내는 그대로 나가야 하고,
 * 기어 오류와 달리 화면을 잠그지 않는다.
 *
 * 센서는 초당 여러 번 값을 준다. km/h 정수가 바뀌거나 마지막으로 보낸 지 [HEARTBEAT_MS]가 지나면 보낸다.
 * 같은 속도라도 주기적으로 보내야 엔진이 쿨다운이 끝난 뒤 과속을 다시 판단할 수 있다.
 *
 * @param permissionGranted 권한이 허용되면 true가 되는 값. 허용될 때까지 기다렸다가 붙는다.
 */
class VehicleSpeedSource(
    context: Context,
    private val permissionGranted: StateFlow<Boolean>,
) : DriveSignalSource {

    private val appContext = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun signals(): Flow<RawSignal> =
        permissionGranted.filter { it }.take(1).flatMapLatest { speed() }

    private fun speed(): Flow<RawSignal> = flow {
        var lastKmh: Int? = null
        var lastSentAt = 0L
        carPropertyEvents(appContext, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL, TAG)
            .collect { event ->
                val mps = (event as? CarPropertyEvent.Value)?.value
                    ?.takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }
                    ?.value as? Float
                if (mps == null) {
                    if (lastKmh != null) {
                        lastKmh = null
                        emit(RawSignal.Vehicle.SpeedUnavailable)
                    }
                    return@collect
                }
                val signal = RawSignal.Vehicle.Speed(mps)
                val now = SystemClock.elapsedRealtime()
                if (signal.kmh != lastKmh || now - lastSentAt >= HEARTBEAT_MS) {
                    lastKmh = signal.kmh
                    lastSentAt = now
                    emit(signal)
                }
            }
    }

    private companion object {
        const val TAG = "YahaengSpeed"

        /** 같은 속도라도 이 간격으로 다시 보낸다(쿨다운 뒤 재경고 판단용). */
        const val HEARTBEAT_MS = 1_000L
    }
}
