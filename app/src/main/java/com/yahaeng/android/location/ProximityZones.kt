package com.yahaeng.android.location

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.yahaeng.android.drive.DriveSignalSource
import com.yahaeng.android.drive.RawSignal
import com.yahaeng.android.rules.ZoneSpec
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 점멸 교차로마다 FusedLocation 근접 알림을 건다([ZoneSpec]: 300m 알림 고리, 70m 통과 고리).
 * 어떤 교차로에 걸지는 [com.yahaeng.android.rules.ProximityPlanner]가 정하고, 여기서는 지금 걸린 알림과 비교해
 * 새 것만 걸고 빠진 것만 뗀다. 진입과 이탈은 [RawSignal.Location.Zone]으로 내보낸다.
 *
 * 위치는 기기 안에서만 쓰고 로그에 좌표를 남기지 않는다. 메인 스레드에서 부른다.
 */
class ProximityZones(context: Context) : DriveSignalSource {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = MutableSharedFlow<RawSignal>(extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private var fused: FusedLocation? = null
    private var failed = false

    /** ZoneSpec.key → SDK가 준 알림 ID(등록 전이면 null). */
    private val registered = mutableMapOf<String, String?>()
    private val specByAlertId = mutableMapOf<String, ZoneSpec>()

    override fun signals(): Flow<RawSignal> = out

    /** 걸어 둘 알림 목록을 바꾼다. 같은 목록이면 아무 일도 하지 않는다. */
    fun update(wanted: List<ZoneSpec>) {
        val engine = engine() ?: return
        val wantedKeys = wanted.associateBy { it.key }
        // 빠진 것 떼기
        registered.keys.filter { it !in wantedKeys }.forEach { key ->
            val id = registered.remove(key)
            if (id != null) {
                specByAlertId.remove(id)
                runCatching { engine.removeProximityAlert(id) }.onFailure { Log.w(TAG, "remove failed: ${it.javaClass.simpleName}") }
            }
            // 판단 로직에 알려 그 교차로의 상태를 지우게 한다(이탈 이벤트가 더는 오지 않는다)
            out.tryEmit(RawSignal.Location.ZoneLost(key.substringBefore(':')))
        }
        // 새 것 걸기
        wanted.filter { it.key !in registered }.forEach { spec ->
            registered[spec.key] = null
            runCatching {
                engine.addProximityAlert(
                    ProximityAlertRequest(spec.lat, spec.lon, spec.radiusMeters, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT)),
                    listenerFor(spec),
                )
            }.onFailure {
                registered.remove(spec.key)
                Log.w(TAG, "add failed: ${it.javaClass.simpleName}")
            }
        }
    }

    fun clear() = update(emptyList())

    val activeCount: Int get() = registered.size

    private fun listenerFor(spec: ZoneSpec) = object : ProximityAlertListener {
        override fun onRegistered(alertId: String) = main.post {
            if (registered.containsKey(spec.key)) {
                registered[spec.key] = alertId
                specByAlertId[alertId] = spec
            } else {
                // 등록이 끝나기 전에 목록에서 빠졌다
                runCatching { fused?.removeProximityAlert(alertId) }
            }
        }.let { }

        override fun onTransition(event: ProximityAlertEvent) = main.post {
            if (!registered.containsKey(spec.key)) return@post
            out.tryEmit(RawSignal.Location.Zone(spec.intersectionId, spec.ring, event.transition == ProximityTransition.ENTER))
        }.let { }

        override fun onError(e: Exception) = main.post {
            Log.w(TAG, "proximity error: ${e.javaClass.simpleName}")
            registered.remove(spec.key)
            out.tryEmit(RawSignal.Location.ZoneLost(spec.intersectionId))
        }.let { }
    }

    private fun engine(): FusedLocation? {
        fused?.let { return it }
        if (failed) return null
        return runCatching {
            FusedLocation(appContext).also {
                if (!it.isSupported()) error("not supported")
                it.initialize()
            }
        }.onFailure {
            failed = true
            Log.w(TAG, "FusedLocation unavailable: ${it.javaClass.simpleName}")
        }.getOrNull().also { fused = it }
    }

    private companion object {
        const val TAG = "YahaengZones"
    }
}
