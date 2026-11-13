package com.yahaeng.android.drive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

/** 주행 신호 입력원. 실제 SDK 어댑터와 시연 입력이 이 인터페이스를 구현한다. */
fun interface DriveSignalSource {
    fun signals(): Flow<RawSignal>
}

class MergedSignalSource(private vararg val sources: DriveSignalSource) : DriveSignalSource {
    override fun signals(): Flow<RawSignal> = merge(*sources.map { it.signals() }.toTypedArray())
}

/**
 * 실제 차량, 내비, 위치 신호를 앱 프로세스 수명 동안 한 번만 수집해 나눠 준다.
 * Vehicle SDK 2.3.2는 `release`가 `initialize`의 연결 작업을 취소하지 않으므로 중간에 끊었다 다시 붙지 않는다.
 *
 * [vehicle]은 화면 잠금에 쓰는 실제 기어 상태다.
 */
class DriveSignalHub(
    private val source: DriveSignalSource,
    private val scope: CoroutineScope,
) {
    private val _signals = MutableSharedFlow<RawSignal>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val signals: SharedFlow<RawSignal> = _signals.asSharedFlow()

    private val _vehicle = MutableStateFlow(VehicleStatus())
    val vehicle: StateFlow<VehicleStatus> = _vehicle.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            source.signals().collect { signal ->
                _vehicle.value = _vehicle.value.on(signal)
                _signals.emit(signal)
            }
        }
    }
}
