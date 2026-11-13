package com.yahaeng.android.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import android.util.Log
import com.yahaeng.android.drive.DriveSignalSource
import com.yahaeng.android.drive.RawSignal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.take

/**
 * Pleos FusedLocation → [RawSignal.Location.Fix]. 에뮬레이터에서는 `adb emu geo fix`를 1초마다 따라간다(2026-11-10 확인).
 *
 * 위치는 기기 안에서 공공데이터 어린이보호구역 시설과 견주는 데만 쓴다. 밖으로 보내지 않고 로그에도 남기지 않는다.
 * 위치는 있으면 좋은 정보다. 읽지 못해도(권한 거부, 서비스 없음) 다른 안내는 그대로 나간다(화면을 잠그지 않는다).
 *
 * @param permissionGranted `pleos.car.permission.FUSED_LOCATION`이 허용되면 true가 되는 값. 허용될 때까지 기다렸다가 붙는다.
 */
class LocationSignalSource(
    context: Context,
    private val permissionGranted: StateFlow<Boolean>,
) : DriveSignalSource {

    private val appContext = context.applicationContext

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun signals(): Flow<RawSignal> = permissionGranted.filter { it }.take(1).flatMapLatest { locations() }

    private fun locations(): Flow<RawSignal> = callbackFlow {
        val fused = FusedLocation(appContext)
        val listener = object : FusedLocationListener {
            override fun onChanged(location: LocationData?) {
                if (location != null) trySend(RawSignal.Location.Fix(location))
            }

            override fun onError(e: Exception) {
                Log.w(TAG, "location error: ${e.javaClass.simpleName}")
                trySend(RawSignal.Location.Unavailable(e.javaClass.simpleName))
            }
        }
        val started = runCatching {
            if (!fused.isSupported()) error("not supported")
            fused.initialize()
            fused.registerFusedLocationCallback(listener)
        }
        started.onFailure { e ->
            Log.w(TAG, "location unavailable: ${e.javaClass.simpleName}")
            trySend(RawSignal.Location.Unavailable(e.message ?: e.javaClass.simpleName))
            runCatching { fused.release() }
            close()
            return@callbackFlow
        }
        awaitClose {
            runCatching { fused.unregisterFusedLocationCallback(listener) }
            runCatching { fused.release() }
        }
    }.buffer(capacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private companion object {
        const val TAG = "YahaengLocation"
    }
}
