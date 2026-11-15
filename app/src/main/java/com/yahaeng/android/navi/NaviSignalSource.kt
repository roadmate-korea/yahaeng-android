package com.yahaeng.android.navi

import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import com.yahaeng.android.drive.DriveSignalSource
import com.yahaeng.android.drive.RawSignal
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/**
 * NaviHelper의 지금 도로 제한 속도 → [RawSignal.Navi.SpeedLimit]. 비 오는 밤 기준 속도의 바탕 값이다.
 * 에뮬레이터는 0을 주므로 시연 때는 디버그 빌드의 시연 입력으로 넣는다.
 */
class NaviSignalSource(context: Context) : DriveSignalSource {

    private val appContext = context.applicationContext

    override fun signals(): Flow<RawSignal> = callbackFlow {
        val navi = NaviHelper(appContext)
        val listener = object : NaviHelperEventListener {
            override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
                trySend(RawSignal.Navi.SpeedLimit(speedLimit))
            }
        }
        val lock = Any()
        var closed = false
        var added = false
        try {
            navi.initialize(
                {
                    synchronized(lock) {
                        if (!closed) {
                            runCatching { navi.addListener(listener); added = true }
                                .onFailure { e -> Log.w(TAG, "addListener failed", e); trySend(RawSignal.Navi.Unavailable(e.javaClass.simpleName)) }
                        }
                    }
                },
                { e -> Log.w(TAG, "NaviHelper initialize failed", e); trySend(RawSignal.Navi.Unavailable(e.javaClass.simpleName)) },
            )
        } catch (e: RuntimeException) {
            Log.w(TAG, "NaviHelper initialize threw", e)
            trySend(RawSignal.Navi.Unavailable(e.javaClass.simpleName))
        }
        awaitClose {
            synchronized(lock) {
                closed = true
                if (added) runCatching { navi.removeListener(listener) }
            }
            runCatching { navi.release() }
        }
    }.buffer(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private companion object {
        const val TAG = "YahaengNavi"
    }
}
