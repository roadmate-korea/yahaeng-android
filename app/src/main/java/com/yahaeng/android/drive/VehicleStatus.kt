package com.yahaeng.android.drive

import com.yahaeng.android.rules.Gear

/**
 * 화면 조작 잠금에 쓰는 실제 차량 상태. 정상적으로 확인된 P에서만 조작을 허용한다.
 * 첫 기어 값을 받기 전에도, 기어를 읽지 못했다는 신호를 받은 뒤에도 잠근다.
 */
data class VehicleStatus(
    val gear: Gear = Gear.UNKNOWN,
    val error: Boolean = false,
) {
    val locked: Boolean get() = error || gear != Gear.PARK

    fun on(signal: RawSignal): VehicleStatus = when (signal) {
        is RawSignal.Vehicle.Gear -> VehicleStatus(gear = SignalMapper.gear(signal.value), error = false)
        is RawSignal.Vehicle.Unavailable -> copy(error = true)
        else -> this
    }
}
