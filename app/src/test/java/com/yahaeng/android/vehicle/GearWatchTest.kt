package com.yahaeng.android.vehicle

import android.car.VehicleGear
import com.yahaeng.android.drive.RawSignal
import com.yahaeng.android.drive.VehicleStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GearWatchTest {

    private val park = GearReading.Value(VehicleGear.GEAR_PARK, confirmed = true)

    @Test
    fun parkThenSilence_locksAfterTimeout_thenParkAgainUnlocks() = runTest {
        val readings = Channel<GearReading>(Channel.UNLIMITED)
        val out = mutableListOf<RawSignal>()
        val job = launch { readings.consumeAsFlow().toGearSignals(timeoutMs = 5_000).collect { out += it } }

        readings.send(park)
        runCurrent()
        assertFalse(out.fold(VehicleStatus()) { s, sig -> s.on(sig) }.locked)

        // 차량 서비스는 붙어 있지만 기어 확인이 끊김(콜백도 다시 읽기 결과도 없음)
        advanceTimeBy(5_001)
        runCurrent()
        val status = out.fold(VehicleStatus()) { s, sig -> s.on(sig) }
        assertTrue(status.locked)
        assertTrue(out.last() is RawSignal.Vehicle.Unavailable)

        // 정상 P를 다시 확인하면 풀린다
        readings.send(park)
        runCurrent()
        assertFalse(out.fold(VehicleStatus()) { s, sig -> s.on(sig) }.locked)
        job.cancel()
    }

    @Test
    fun repeatedPolls_doNotRepeatSignals_andLongParkingStaysUnlocked() = runTest {
        val readings = Channel<GearReading>(Channel.UNLIMITED)
        val out = mutableListOf<RawSignal>()
        val job = launch { readings.consumeAsFlow().toGearSignals(timeoutMs = 5_000).collect { out += it } }

        repeat(30) {
            readings.send(park)
            advanceTimeBy(2_000)
            runCurrent()
        }
        assertEquals(listOf<RawSignal>(RawSignal.Vehicle.Gear(VehicleGear.GEAR_PARK)), out)
        job.cancel()
    }

    @Test
    fun unavailableReading_locksOnce_evenWithDifferentReasons() = runTest {
        val readings = Channel<GearReading>(Channel.UNLIMITED)
        val out = mutableListOf<RawSignal>()
        val job = launch { readings.consumeAsFlow().toGearSignals(timeoutMs = 5_000).collect { out += it } }

        readings.send(park)
        readings.send(GearReading.Unavailable("not available"))
        readings.send(GearReading.Unavailable("read failed"))
        runCurrent()
        assertEquals(2, out.size)
        assertTrue(out.fold(VehicleStatus()) { s, sig -> s.on(sig) }.locked)
        job.cancel()
    }

    @Test
    fun gearChange_isPassedThrough() = runTest {
        val readings = Channel<GearReading>(Channel.UNLIMITED)
        val out = mutableListOf<RawSignal>()
        val job = launch { readings.consumeAsFlow().toGearSignals(timeoutMs = 5_000).collect { out += it } }

        readings.send(park)
        readings.send(GearReading.Value(VehicleGear.GEAR_DRIVE, confirmed = false))
        readings.send(GearReading.Value(VehicleGear.GEAR_DRIVE, confirmed = true))
        readings.send(park)
        runCurrent()
        assertEquals(
            listOf(VehicleGear.GEAR_PARK, VehicleGear.GEAR_DRIVE, VehicleGear.GEAR_PARK).map { RawSignal.Vehicle.Gear(it) },
            out,
        )
        job.cancel()
    }

    @Test
    fun staleParkCallbackAfterReadFailure_staysLocked_untilFreshParkRead() = runTest {
        val readings = Channel<GearReading>(Channel.UNLIMITED)
        val out = mutableListOf<RawSignal>()
        val job = launch { readings.consumeAsFlow().toGearSignals(timeoutMs = 5_000).collect { out += it } }
        fun status() = out.fold(VehicleStatus()) { s, sig -> s.on(sig) }

        readings.send(park)
        runCurrent()
        assertFalse(status().locked)

        // 다시 읽기 중 큐에 들어온 옛 P 콜백이 읽기 실패 뒤에 도착한다
        readings.send(GearReading.Unavailable("read failed"))
        readings.send(GearReading.Value(VehicleGear.GEAR_PARK, confirmed = false))
        runCurrent()
        assertTrue(status().locked)

        // 콜백의 P만으로는 시간 제한도 갱신하지 않는다
        advanceTimeBy(4_000)
        readings.send(GearReading.Value(VehicleGear.GEAR_PARK, confirmed = false))
        runCurrent()
        assertTrue(status().locked)

        // 새로 읽어 확인한 P로만 풀린다
        readings.send(park)
        runCurrent()
        assertFalse(status().locked)
        job.cancel()
    }

    @Test
    fun staleParkCallbackAfterDrive_doesNotUnlock_driveCallbackLocksImmediately() = runTest {
        val readings = Channel<GearReading>(Channel.UNLIMITED)
        val out = mutableListOf<RawSignal>()
        val job = launch { readings.consumeAsFlow().toGearSignals(timeoutMs = 5_000).collect { out += it } }
        fun status() = out.fold(VehicleStatus()) { s, sig -> s.on(sig) }

        readings.send(park)
        readings.send(GearReading.Value(VehicleGear.GEAR_DRIVE, confirmed = false))
        runCurrent()
        assertTrue(status().locked)

        readings.send(GearReading.Value(VehicleGear.GEAR_PARK, confirmed = false))
        runCurrent()
        assertTrue(status().locked)
        job.cancel()
    }
}
