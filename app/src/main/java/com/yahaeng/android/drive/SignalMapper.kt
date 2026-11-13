package com.yahaeng.android.drive

import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import android.car.VehicleGear
import com.yahaeng.android.rules.Gear
import com.yahaeng.android.rules.NightEvent
import com.yahaeng.android.rules.TurnSignal
import com.yahaeng.android.rules.WiperMode

/** SDK 신호 → 판단 로직 이벤트. SDK 타입을 아는 곳은 여기와 어댑터뿐이다. */
object SignalMapper {

    fun map(signal: RawSignal): NightEvent? = when (signal) {
        is RawSignal.Vehicle.Gear -> NightEvent.GearChanged(gear(signal.value))
        is RawSignal.Vehicle.TurnSignal -> NightEvent.TurnSignalChanged(turnSignal(signal.value))
        is RawSignal.Vehicle.Speed -> NightEvent.SpeedChanged(signal.kmh)
        RawSignal.Vehicle.SpeedUnavailable -> NightEvent.SpeedChanged(null)
        is RawSignal.Vehicle.Wiper -> NightEvent.WiperSwitchChanged(wiper(signal.value))
        is RawSignal.Vehicle.WiperMotion -> NightEvent.WiperMotionChanged(signal.value == WiperState.ON)
        is RawSignal.Vehicle.Cruise -> NightEvent.CruiseStateChanged(cruiseActive(signal.value))
        is RawSignal.Vehicle.CruiseTarget -> NightEvent.CruiseTargetChanged(signal.kmh)
        is RawSignal.Vehicle.Unavailable -> null
        is RawSignal.Navi.SpeedLimit -> NightEvent.SpeedLimitChanged(signal.kmh)
        is RawSignal.Navi.Unavailable -> null
        is RawSignal.Location.Fix -> signal.value
            .takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
            ?.let { NightEvent.LocationChanged(it.latitude, it.longitude) }
        is RawSignal.Location.Unavailable -> null
        is RawSignal.Location.Zone ->
            if (signal.entered) NightEvent.ZoneEntered(signal.intersectionId, signal.ring)
            else NightEvent.ZoneExited(signal.intersectionId, signal.ring)
    }

    /** 수동 1~9단은 주행으로 본다. */
    fun gear(value: Int): Gear = when (value) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE,
        VehicleGear.GEAR_FIRST, VehicleGear.GEAR_SECOND, VehicleGear.GEAR_THIRD,
        VehicleGear.GEAR_FOURTH, VehicleGear.GEAR_FIFTH, VehicleGear.GEAR_SIXTH,
        VehicleGear.GEAR_SEVENTH, VehicleGear.GEAR_EIGHTH, VehicleGear.GEAR_NINTH -> Gear.DRIVE
        else -> Gear.UNKNOWN
    }

    fun turnSignal(value: TurnSignalValue): TurnSignal = when (value) {
        TurnSignalValue.OFF -> TurnSignal.OFF
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
    }

    fun wiper(value: WiperSwitch): WiperMode = when (value) {
        WiperSwitch.OFF -> WiperMode.OFF
        WiperSwitch.MIST -> WiperMode.MIST
        WiperSwitch.INTERMITTENT_LEVEL_1, WiperSwitch.INTERMITTENT_LEVEL_2, WiperSwitch.INTERMITTENT_LEVEL_3,
        WiperSwitch.INTERMITTENT_LEVEL_4, WiperSwitch.INTERMITTENT_LEVEL_5 -> WiperMode.INTERMITTENT
        WiperSwitch.CONTINUOUS_LEVEL_1, WiperSwitch.CONTINUOUS_LEVEL_2, WiperSwitch.CONTINUOUS_LEVEL_3,
        WiperSwitch.CONTINUOUS_LEVEL_4, WiperSwitch.CONTINUOUS_LEVEL_5 -> WiperMode.CONTINUOUS
        WiperSwitch.AUTO -> WiperMode.AUTO
        WiperSwitch.OTHER, WiperSwitch.SERVICE -> WiperMode.OTHER
    }

    /** 크루즈가 켜져 있거나(목표 속도를 정해 둠) 동작 중이면 목표 속도와 비교한다. */
    fun cruiseActive(value: CruiseControl): Boolean = value == CruiseControl.ENABLED || value == CruiseControl.ACTIVATED
}
