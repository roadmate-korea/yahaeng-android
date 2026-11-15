package com.yahaeng.android.vehicle

import android.car.Car
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** Android Automotive 차량 속성 하나의 변화. */
sealed interface CarPropertyEvent {
    /**
     * 속성 값. 상태(`status`)가 정상이 아닐 수도 있으니 받는 쪽에서 확인한다.
     * @param polled 직접 다시 읽은 값이면 true. 변경 콜백 값(false)은 큐에서 기다리다 늦게 도착한 옛 값일 수 있다.
     */
    data class Value(val value: CarPropertyValue<*>, val polled: Boolean = false) : CarPropertyEvent

    /** 지금 값을 읽을 수 없음(차량 서비스 없음·끊김, 등록 실패, 오류 이벤트). 다시 연결되면 [Value]가 다시 온다. */
    data class Unavailable(val reason: String) : CarPropertyEvent
}

/**
 * Android Automotive 표준 `CarPropertyManager`로 차량 속성 하나를 듣는다(속도, 기어).
 *
 * Pleos 문서의 SDK API 정책: 속도와 기어 같은 표준 차량 정보는 AAOS Car API로 읽는다.
 *
 * - 차량 서비스에는 연결 상태 리스너를 붙여 연결한다. 리스너 없이 연결하면 서비스가 끊길 때 플랫폼이 앱 프로세스를 끝낸다
 *   (AOSP Car.java finishClient). 끊기면 [CarPropertyEvent.Unavailable]을 보내고, 다시 연결되면 다시 등록한다.
 * - 차량 서비스 연결, 관리자 획득, 등록은 전용 스레드에서 한다. 등록(`registerCallback`)은 차량 서비스에 동기 호출을 하는데,
 *   서비스가 멈추면 응답이 오지 않는다. 메인 스레드에서 부르면 앱이 ANR로 죽는다(2026-11-10 에뮬레이터 차량 속성 서비스가
 *   멈췄을 때 DriveGuidanceService ANR, 시스템 UI도 같은 호출에서 ANR). 전용 스레드가 막혀도 이 속성만 빠진다.
 *   남는 위험: 플랫폼이 서비스 연결 알림(ServiceConnection)을 메인에서 처리하며 Car 내부 잠금을 잡는다. 그 순간 전용 스레드가
 *   같은 잠금을 잡은 채 멈춘 차량 서비스를 기다리고 있으면 메인도 기다린다. android.car 라이브러리 구조라 앱에서 피할 수 없다.
 * - 연결(createCar)도 전용 스레드에서 한다. 메인에서 연결하면 연결 중의 Car 내부 잠금(mLock)과 전용 스레드의
 *   관리자 획득(getCarManager, 잠금을 잡은 채 차량 서비스에 동기 호출)이 겹쳐 메인이 다시 막힐 수 있다.
 * - 변할 때만 오는 속성(기어)도 등록하면 현재 값이 한 번 온다(AAOS registerCallback 동작).
 * - Android 14는 속성이 읽기 불가(UNAVAILABLE, ERROR)로 바뀌어도 변경 콜백을 보내지 않고(PropertyHalService가 AVAILABLE이
 *   아닌 이벤트를 버림), CarPropertyManager 문서도 가용성 판단을 콜백에 기대지 말라고 한다. [pollMs]를 주면 그 간격으로
 *   현재 값을 직접 다시 읽어 [CarPropertyEvent.Value]`(polled = true)`나 [CarPropertyEvent.Unavailable]로 보낸다(기어 잠금용).
 *   변경 콜백을 받거나 등록이 끝나면 곧바로 한 번 더 읽는다. 이 읽기는 콜백 처리 뒤에 시작하므로 그 시점의 현재 값이다.
 *   받는 쪽은 같은 값이 여러 번 올 수 있음을 감안한다.
 */
fun carPropertyEvents(
    context: Context,
    propertyId: Int,
    rate: Float,
    threadName: String,
    pollMs: Long? = null,
): Flow<CarPropertyEvent> = callbackFlow {
    val appContext = context.applicationContext
    if (!carSupported(appContext)) {
        trySend(CarPropertyEvent.Unavailable("no automotive feature"))
        close()
        return@callbackFlow
    }

    // 연결 상태 콜백, 속성 콜백, 등록과 해제를 모두 이 스레드에서 처리한다(메인 스레드를 막지 않게)
    val thread = HandlerThread(threadName).apply { start() }
    val worker = Handler(thread.looper)

    // 아래 상태는 전용 스레드에서만 읽고 쓴다(속성 콜백 등록과 해제, 다시 읽기, 종료)
    var manager: CarPropertyManager? = null
    var closed = false

    // 현재 값을 직접 한 번 읽는다. 차량 서비스가 멈추면 이 스레드가 막히고 결과가 오지 않는데, 받는 쪽이 시간 제한으로 판단한다
    val readNow = Runnable {
        val m = manager
        if (closed || m == null) return@Runnable
        val event = runCatching {
            if (m.isPropertyAvailable(propertyId, GLOBAL_AREA)) {
                CarPropertyEvent.Value(m.getProperty<Any>(propertyId, GLOBAL_AREA), polled = true)
            } else {
                CarPropertyEvent.Unavailable("not available")
            }
        }.getOrElse { e -> CarPropertyEvent.Unavailable("read failed: ${e.javaClass.simpleName}") }
        trySend(event)
    }

    val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            trySend(CarPropertyEvent.Value(value))
            if (pollMs != null) worker.post(readNow)
        }

        override fun onErrorEvent(propertyId: Int, areaId: Int) {
            Log.w(threadName, "error event")
            trySend(CarPropertyEvent.Unavailable("error event"))
        }
    }

    fun unregister() {
        manager?.let { m -> runCatching { m.unregisterCallback(callback) } }
        manager = null
    }

    fun onCarLifecycle(car: Car, ready: Boolean) {
        if (closed) return
        unregister()
        if (!ready) {
            Log.w(threadName, "car service disconnected")
            trySend(CarPropertyEvent.Unavailable("car service disconnected"))
            return
        }
        val m = car.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (m == null) {
            trySend(CarPropertyEvent.Unavailable("no property service"))
            return
        }
        val ok = runCatching { m.registerCallback(callback, propertyId, rate) }
            .onFailure { e -> Log.w(threadName, "register failed", e) }
            .getOrDefault(false)
        if (ok) {
            manager = m
            if (pollMs != null) worker.post(readNow)
        } else {
            Log.w(threadName, "not registered")
            trySend(CarPropertyEvent.Unavailable("not registered"))
        }
    }

    // 주기적 다시 읽기
    val poll = object : Runnable {
        override fun run() {
            if (closed) return
            readNow.run()
            pollMs?.let { worker.postDelayed(this, it) }
        }
    }

    var car: Car? = null
    pollMs?.let { worker.postDelayed(poll, it) }
    worker.post {
        if (closed) return@post
        car = try {
            // Car는 연결 상태 콜백을 넘긴 handler와 상관없이 메인 스레드로 보낸다(AOSP Car.dispatchCarReadyToMainThread).
            // 콜백에서는 전용 스레드로 넘기기만 한다
            Car.createCar(appContext, worker, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { c, ready ->
                worker.post { onCarLifecycle(c, ready) }
            }
        } catch (e: RuntimeException) {
            Log.w(threadName, "Car service unavailable", e)
            null
        } catch (e: NoClassDefFoundError) {
            Log.w(threadName, "android.car library missing", e)
            null
        }
        if (car == null) {
            closed = true
            trySend(CarPropertyEvent.Unavailable("car service unavailable"))
            thread.quitSafely()
            close()
        }
    }
    awaitClose {
        worker.post {
            closed = true
            worker.removeCallbacks(poll)
            worker.removeCallbacks(readNow)
            unregister()
            car?.let { runCatching { it.disconnect() } }
            thread.quitSafely()
        }
    }
}.buffer(capacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)

/** 차 전체에 하나뿐인 속성(기어, 속도)의 area ID. */
private const val GLOBAL_AREA = 0

private fun carSupported(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
