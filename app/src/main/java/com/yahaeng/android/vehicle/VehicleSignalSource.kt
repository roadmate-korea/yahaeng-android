package com.yahaeng.android.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.CruiseControlTargetSpeedListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener
import android.content.Context
import android.util.Log
import com.yahaeng.android.drive.DriveSignalSource
import com.yahaeng.android.drive.RawSignal
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Vehicle SDK 신호 → [RawSignal]. 방향지시등(TurnSignal), 앞 유리 와이퍼(Wiper), 크루즈 상태와 목표 속도(DrivingMode).
 *
 * 도메인마다 capability로 읽을 수 있는지 확인한 뒤 리스너를 건다. 와이퍼 스위치는 공개 API에 리스너가 없어
 * [WIPER_POLL_MS]마다 현재 값을 읽는다(와이퍼가 실제로 움직이는지는 리스너로 받는다).
 * 이 신호들은 안내에 쓰는 정보라 읽지 못해도 화면을 잠그지 않는다(로그만 남긴다).
 *
 * Vehicle SDK 2.3.2의 `release()`는 `initialize()`가 시작한 연결 작업을 취소하지 않으므로, 이 Flow는
 * [com.yahaeng.android.drive.DriveSignalHub]에서 프로세스 수명 동안 한 번만 수집한다.
 */
class VehicleSignalSource(context: Context) : DriveSignalSource {

    private val appContext = context.applicationContext

    override fun signals(): Flow<RawSignal> = callbackFlow {
        val vehicle = try {
            Vehicle(appContext).also { it.initialize() }
        } catch (e: RuntimeException) {
            Log.w(TAG, "Vehicle initialize failed", e)
            close()
            return@callbackFlow
        }
        val front = WindowArea(WindowValue.FRONT_WINDSHIELD)
        val lock = Any()
        var closed = false
        val unregister = mutableListOf<() -> Unit>()

        /** 종료 뒤에는 등록하지 않고, 등록이 끝났으면 종료 때 반드시 해제한다. */
        fun register(name: String, doRegister: () -> Unit, doUnregister: () -> Unit) = synchronized(lock) {
            if (closed) return@synchronized
            runCatching { doRegister(); unregister += doUnregister }
                .onFailure { e -> Log.w(TAG, "$name register failed", e) }
        }

        fun readable(config: CapabilityConfig) = config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE
        val fail: (String) -> (Throwable) -> Unit = { name -> { e -> Log.w(TAG, "$name failed: ${e.javaClass.simpleName}") } }

        // 방향지시등
        val turnListener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) {
                value?.let { trySend(RawSignal.Vehicle.TurnSignal(it)) }
            }
            override fun onFailed(e: Exception) = fail("turn signal")(e)
        }
        runCatching {
            val turn = vehicle.getTurnSignal()
            turn.checkTurnSignalStateCapabilities({ config ->
                if (readable(config)) {
                    register("turn signal", { turn.registerTurnSignalState(turnListener) }, { turn.unregisterTurnSignalState(turnListener) })
                    turn.getCurrentTurnSignalState({ v -> v?.let { trySend(RawSignal.Vehicle.TurnSignal(it)) } }, fail("current turn signal"))
                }
            }, fail("turn signal capability"))
        }.onFailure(fail("turn signal check"))

        // 와이퍼: 움직임은 리스너, 스위치는 주기적으로 읽기
        val wiperListener = object : WiperStateListener {
            override fun onWiperStateUpdated(value: WiperState?) {
                value?.let { trySend(RawSignal.Vehicle.WiperMotion(it)) }
            }
            override fun onFailed(e: Exception) = fail("wiper state")(e)
        }
        runCatching {
            val wiper = vehicle.getWiper()
            wiper.checkWiperStateCapabilities({ config, _ ->
                if (readable(config)) {
                    register("wiper state", { wiper.registerWiperState(front, wiperListener) }, { wiper.unregisterWiperState(front, wiperListener) })
                    wiper.getCurrentWiperState(front, { v -> v?.let { trySend(RawSignal.Vehicle.WiperMotion(it)) } }, fail("current wiper state"))
                }
            }, fail("wiper state capability"))
            wiper.checkWiperSwitchCapabilities({ config, _ ->
                if (readable(config)) {
                    launch {
                        while (isActive) {
                            runCatching {
                                wiper.getCurrentWiperSwitch(front, { v -> v?.let { trySend(RawSignal.Vehicle.Wiper(it)) } }, fail("current wiper switch"))
                            }
                            delay(WIPER_POLL_MS)
                        }
                    }
                }
            }, fail("wiper switch capability"))
        }.onFailure(fail("wiper check"))

        // 크루즈 상태와 목표 속도
        val cruiseListener = object : CruiseControlStateListener {
            override fun onCruiseControlStateUpdated(value: CruiseControl?) {
                value?.let { trySend(RawSignal.Vehicle.Cruise(it)) }
            }
            override fun onFailed(e: Exception) = fail("cruise state")(e)
        }
        val targetListener = object : CruiseControlTargetSpeedListener {
            override fun onCruiseControlTargetSpeedUpdated(value: Float?) {
                trySend(RawSignal.Vehicle.CruiseTarget(value))
            }
            override fun onFailed(e: Exception) = fail("cruise target")(e)
        }
        runCatching {
            val mode = vehicle.getDrivingMode()
            mode.checkCruiseControlStateCapabilities({ config ->
                if (readable(config)) {
                    register("cruise state", { mode.registerCruiseControlState(cruiseListener) }, { mode.unregisterCruiseControlState(cruiseListener) })
                    mode.getCruiseControlState({ v -> v?.let { trySend(RawSignal.Vehicle.Cruise(it)) } }, fail("current cruise state"))
                }
            }, fail("cruise state capability"))
            mode.checkCruiseControlTargetSpeedCapabilities({ config ->
                if (readable(config)) {
                    register("cruise target", { mode.registerCruiseControlTargetSpeed(targetListener) }, { mode.unregisterCruiseControlTargetSpeed(targetListener) })
                    mode.getCruiseControlTargetSpeed({ v -> trySend(RawSignal.Vehicle.CruiseTarget(v)) }, fail("current cruise target"))
                }
            }, fail("cruise target capability"))
        }.onFailure(fail("cruise check"))

        awaitClose {
            synchronized(lock) {
                closed = true
                unregister.forEach { runCatching { it() } }
            }
            runCatching { vehicle.release() }
        }
    }.buffer(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private companion object {
        const val TAG = "YahaengVehicle"
        const val WIPER_POLL_MS = 2_000L
    }
}
