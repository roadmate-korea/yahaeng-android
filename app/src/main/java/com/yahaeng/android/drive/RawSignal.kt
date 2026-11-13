package com.yahaeng.android.drive

import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import com.yahaeng.android.rules.Ring
import kotlin.math.roundToInt

/**
 * SDK 콜백 값을 그대로 담은 봉투. 실제 어댑터와 시연 입력이 모두 이 형식으로 신호를 내고,
 * 같은 [SignalMapper]를 거쳐 판단 로직으로 들어간다.
 */
sealed interface RawSignal {

    sealed interface Vehicle : RawSignal {
        /** Android Automotive `GEAR_SELECTION` 값(`android.car.VehicleGear` 상수). */
        data class Gear(val value: Int) : Vehicle

        data class TurnSignal(val value: TurnSignalValue) : Vehicle

        /** `PERF_VEHICLE_SPEED`(m/s). 후진이면 음수일 수 있다. */
        data class Speed(val metersPerSecond: Float) : Vehicle {
            val kmh: Int get() = kotlin.math.abs(metersPerSecond * 3.6f).roundToInt()
        }

        data object SpeedUnavailable : Vehicle

        /** 앞 유리 와이퍼 스위치(Vehicle SDK Wiper). */
        data class Wiper(val value: WiperSwitch) : Vehicle

        /** 앞 유리 와이퍼가 실제로 움직이는지(Vehicle SDK Wiper). */
        data class WiperMotion(val value: WiperState) : Vehicle

        /** 크루즈 상태(Vehicle SDK DrivingMode). */
        data class Cruise(val value: CruiseControl) : Vehicle

        /** 크루즈 목표 속도(m/s, Vehicle SDK DrivingMode). null이면 없음. */
        data class CruiseTarget(val metersPerSecond: Float?) : Vehicle {
            val kmh: Int? get() = metersPerSecond?.takeIf { it.isFinite() && it > 0f }?.let { (it * 3.6f).roundToInt() }
        }

        /** 기어를 읽을 수 없음. 화면을 잠근다. 판단 로직에는 들어가지 않는다. */
        data class Unavailable(val reason: String) : Vehicle
    }

    sealed interface Navi : RawSignal {
        /** `onCurrentRoadSpeedLimit` */
        data class SpeedLimit(val kmh: Int) : Navi

        data class Unavailable(val reason: String) : Navi
    }

    /** FusedLocation. 위치는 기기 안에서 점멸 교차로와 견주는 데만 쓰고 밖으로 보내거나 기록하지 않는다. */
    sealed interface Location : RawSignal {
        data class Fix(val value: LocationData) : Location {
            override fun toString() = "Location.Fix"
        }

        data class Unavailable(val reason: String) : Location

        /** FusedLocation 근접 알림의 진입과 이탈. */
        data class Zone(val intersectionId: String, val ring: Ring, val entered: Boolean) : Location
    }
}
