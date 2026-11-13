package com.yahaeng.android.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class NightEngineTest {

    /** 2026-11-13 한국 시간을 원하는 대로 옮기는 시계. */
    private class FakeClock(var millis: Long) : Clock {
        override fun nowMillis() = millis
        fun at(h: Int, m: Int) { millis = LocalDateTime.of(2026, 11, 13, h, m).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() }
        fun plus(ms: Long) { millis += ms }
    }

    // 구리시 근처. 23:00~05:00 점멸
    private val guri = FlashingIntersection("a", 37.5943, 127.1296, FlashWindow(23 * 60, 5 * 60), "인창사거리", "경기도 구리시", "2026-11-05")
    private val next = FlashingIntersection("b", 37.5953, 127.1296, FlashWindow(23 * 60, 5 * 60), "", "경기도 구리시", "2026-11-05")
    private val map = FlashMap(listOf(guri, next))
    private lateinit var clock: FakeClock
    private lateinit var engine: NightEngine

    @Before
    fun setUp() {
        clock = FakeClock(0).apply { at(23, 30) }
        engine = NightEngine({ map }, clock)
    }

    private fun ids(list: List<Guidance>) = list.map { it.id }

    private fun drive() {
        engine.on(NightEvent.LocationChanged(guri.lat - 0.01, guri.lon))
        engine.on(NightEvent.GearChanged(Gear.DRIVE))
    }

    @Test
    fun flashAhead_isSaidOnce_whenTheIntersectionIsFlashingNow() {
        drive()
        val out = engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        assertEquals(listOf(GuidanceId.FLASH_AHEAD), ids(out))
        assertEquals("a", out.single().key)
        engine.on(NightEvent.ZoneExited("a", Ring.APPROACH))
        assertTrue("10분 안에 다시 오면 말하지 않는다", ids(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))).isEmpty())
    }

    @Test
    fun flashAhead_isSilent_inTheDaytime() {
        clock.at(14, 0)
        drive()
        assertTrue(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH)).isEmpty())
        assertNull(engine.state().approaching)
    }

    @Test
    fun flashAhead_isSilent_whenParked() {
        engine.on(NightEvent.GearChanged(Gear.PARK))
        assertTrue(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH)).isEmpty())
    }

    @Test
    fun turnSignal_addsOneSentence_perDirection() {
        drive()
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        assertEquals(listOf(GuidanceId.FLASH_TURN_LEFT), ids(engine.on(NightEvent.TurnSignalChanged(TurnSignal.LEFT))))
        engine.on(NightEvent.TurnSignalChanged(TurnSignal.OFF))
        assertTrue("같은 방향은 한 번만", engine.on(NightEvent.TurnSignalChanged(TurnSignal.LEFT)).isEmpty())
        assertEquals(listOf(GuidanceId.FLASH_TURN_RIGHT), ids(engine.on(NightEvent.TurnSignalChanged(TurnSignal.RIGHT))))
    }

    @Test
    fun turnSignalAlreadyOn_isAddedRightAfterTheFlashAlert() {
        drive()
        engine.on(NightEvent.TurnSignalChanged(TurnSignal.RIGHT))
        assertEquals(listOf(GuidanceId.FLASH_AHEAD, GuidanceId.FLASH_TURN_RIGHT), ids(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))))
    }

    @Test
    fun turnSignal_awayFromAFlashingIntersection_saysNothing() {
        drive()
        assertTrue(engine.on(NightEvent.TurnSignalChanged(TurnSignal.LEFT)).isEmpty())
    }

    private fun pass(vararg speeds: Int) {
        engine.on(NightEvent.SpeedChanged(speeds.first()))
        engine.on(NightEvent.ZoneEntered("a", Ring.INSIDE))
        speeds.drop(1).forEach { engine.on(NightEvent.SpeedChanged(it)) }
        engine.on(NightEvent.ZoneExited("a", Ring.INSIDE))
    }

    @Test
    fun passage_recordsTheLowestSpeedInside_asAFact() {
        drive()
        pass(30, 12, 2, 25)
        assertEquals(2, engine.passages.single().minKmh)
        assertEquals(Passage.Outcome.STOPPED, engine.passages.single().outcome)
    }

    @Test
    fun passage_outcomes() {
        assertEquals(Passage.Outcome.STOPPED, Passage("a", "", 0, guri.window, 5).outcome)
        assertEquals(Passage.Outcome.SLOWED, Passage("a", "", 0, guri.window, 6).outcome)
        assertEquals(Passage.Outcome.SLOWED, Passage("a", "", 0, guri.window, 20).outcome)
        assertEquals(Passage.Outcome.PASSED, Passage("a", "", 0, guri.window, 21).outcome)
        assertEquals(Passage.Outcome.UNKNOWN, Passage("a", "", 0, guri.window, null).outcome)
    }

    @Test
    fun passingWithoutSlowing_isToldOnce_whenTheCarNextStops() {
        drive()
        pass(45, 42, 44)
        assertTrue("움직이는 중에는 말하지 않는다", engine.on(NightEvent.SpeedChanged(40)).isEmpty())
        val out = engine.on(NightEvent.SpeedChanged(0))
        assertEquals(listOf(GuidanceId.FLASH_PASSED_WITHOUT_STOP), ids(out))
        assertEquals(listOf(42), out.single().args)
        assertTrue("한 번만", engine.on(NightEvent.SpeedChanged(0)).isEmpty())
    }

    @Test
    fun slowingDown_isNotTold() {
        drive()
        pass(25, 15, 25)
        assertTrue(engine.on(NightEvent.SpeedChanged(0)).isEmpty())
    }

    @Test
    fun passageIsNotRecorded_inTheDaytime() {
        clock.at(12, 0)
        drive()
        pass(40, 40)
        assertTrue(engine.passages.isEmpty())
    }

    @Test
    fun parking_summarizesTonightsNewPassages_once() {
        drive()
        pass(30, 1, 20)
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        pass(30, 30)
        val out = engine.on(NightEvent.GearChanged(Gear.PARK))
        // 서행하지 않고 지난 사실이 먼저, 그다음 요약
        assertEquals(listOf(GuidanceId.FLASH_PASSED_WITHOUT_STOP, GuidanceId.NIGHT_SUMMARY), ids(out))
        assertEquals(listOf(2, 1), out.last().args)
        engine.on(NightEvent.GearChanged(Gear.DRIVE))
        assertTrue(ids(engine.on(NightEvent.GearChanged(Gear.PARK))).isEmpty())
    }

    @Test
    fun nightStart_isAnnouncedOnce_onTheFirstDriveAfterSunset() {
        engine.on(NightEvent.LocationChanged(guri.lat, guri.lon))
        assertEquals(listOf(GuidanceId.NIGHT_START), ids(engine.on(NightEvent.GearChanged(Gear.DRIVE))))
        assertEquals(1, engine.on(NightEvent.GearChanged(Gear.PARK)).size.let { 1 })
        assertTrue(ids(engine.on(NightEvent.GearChanged(Gear.DRIVE))).none { it == GuidanceId.NIGHT_START })
    }

    @Test
    fun nightStart_saysThereIsNoData_farFromAnyIntersection() {
        engine.on(NightEvent.LocationChanged(35.1, 129.0))
        assertEquals(listOf(GuidanceId.NIGHT_START_NO_DATA), ids(engine.on(NightEvent.GearChanged(Gear.DRIVE))))
    }

    @Test
    fun nightStart_isSilentBeforeSunset() {
        clock.at(15, 0)
        engine.on(NightEvent.LocationChanged(guri.lat, guri.lon))
        assertTrue(engine.on(NightEvent.GearChanged(Gear.DRIVE)).isEmpty())
        assertEquals(false, engine.state().night)
    }

    // ---- 비 오는 밤 ----

    private fun rainyDrive(limit: Int = 100) {
        drive()
        engine.on(NightEvent.SpeedLimitChanged(limit))
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.CONTINUOUS))
    }

    @Test
    fun rain_comparesCruiseTarget_withTheWetRoadLimit_afterTheWiperRunsLongEnough() {
        rainyDrive()
        engine.on(NightEvent.CruiseStateChanged(true))
        engine.on(NightEvent.CruiseTargetChanged(100))
        assertTrue("와이퍼 10초 전에는 말하지 않는다", engine.tick().isEmpty())
        clock.plus(10_000)
        val out = engine.tick()
        assertEquals(listOf(GuidanceId.RAIN_SPEED_CRUISE), ids(out))
        assertEquals(listOf(100, 80, 100), out.single().args)
        clock.plus(60_000)
        assertTrue("같은 제한 속도에서는 한 번만", engine.tick().isEmpty())
    }

    @Test
    fun rain_speedAboveTheWetLimit_isToldAfterFiveSeconds() {
        rainyDrive(80)
        clock.plus(10_000)
        engine.on(NightEvent.SpeedChanged(70))
        assertTrue(engine.tick().isEmpty())
        clock.plus(4_000)
        assertTrue(engine.tick().isEmpty())
        clock.plus(1_000)
        val out = engine.tick()
        assertEquals(listOf(GuidanceId.RAIN_SPEED), ids(out))
        assertEquals(listOf(80, 64), out.single().args)
    }

    @Test
    fun rain_belowTheWetLimit_saysNothing() {
        rainyDrive()
        clock.plus(10_000)
        engine.on(NightEvent.SpeedChanged(75))
        clock.plus(10_000)
        assertTrue(engine.tick().isEmpty())
        assertEquals(80, engine.state().wetLimit)
    }

    @Test
    fun rain_needsAKnownSpeedLimit() {
        rainyDrive(0)
        clock.plus(10_000)
        engine.on(NightEvent.SpeedChanged(120))
        clock.plus(10_000)
        assertTrue(engine.tick().isEmpty())
        assertNull(engine.state().wetLimit)
    }

    @Test
    fun mistWipe_isNotRain() {
        drive()
        engine.on(NightEvent.SpeedLimitChanged(100))
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.MIST))
        engine.on(NightEvent.WiperMotionChanged(true))
        clock.plus(30_000)
        assertNull(engine.state().wetLimit)
    }

    @Test
    fun autoWiper_countsOnlyWhileActuallyMoving() {
        drive()
        engine.on(NightEvent.SpeedLimitChanged(100))
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.AUTO))
        clock.plus(30_000)
        assertNull(engine.state().wetLimit)
        engine.on(NightEvent.WiperMotionChanged(true))
        clock.plus(10_000)
        assertEquals(80, engine.state().wetLimit)
    }

    @Test
    fun rain_canBeToldAgain_afterTheWiperStaysOffForTwoMinutes() {
        rainyDrive()
        engine.on(NightEvent.CruiseStateChanged(true))
        engine.on(NightEvent.CruiseTargetChanged(100))
        clock.plus(10_000)
        assertEquals(1, engine.tick().size)
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.OFF))
        clock.plus(NightEngine.WIPER_OFF_RESET_MS)
        engine.tick()
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.CONTINUOUS))
        clock.plus(10_000)
        assertEquals(listOf(GuidanceId.RAIN_SPEED_CRUISE), ids(engine.tick()))
    }

    @Test
    fun denseIntersections_areNotAnnouncedBackToBack() {
        drive()
        assertEquals(listOf(GuidanceId.FLASH_AHEAD), ids(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))))
        assertTrue("앞 교차로 고리 안이면 다음 교차로는 말하지 않는다", engine.on(NightEvent.ZoneEntered("b", Ring.APPROACH)).isEmpty())
        engine.on(NightEvent.ZoneExited("a", Ring.APPROACH))
        engine.on(NightEvent.ZoneExited("b", Ring.APPROACH))
        clock.plus(10_000)
        assertTrue("방금 말했으면 30초 동안 쉰다", engine.on(NightEvent.ZoneEntered("b", Ring.APPROACH)).isEmpty())
        engine.on(NightEvent.ZoneExited("b", Ring.APPROACH))
        clock.plus(NightEngine.FLASH_GAP_MS)
        assertEquals(listOf(GuidanceId.FLASH_AHEAD), ids(engine.on(NightEvent.ZoneEntered("b", Ring.APPROACH))))
    }

    // ---- 리뷰 반영(2026-11-13) ----

    @Test
    fun droppedZone_doesNotBlockTheNextIntersection() {
        drive()
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        engine.on(NightEvent.ZoneDropped("a"))
        assertNull(engine.state().approaching)
        clock.plus(NightEngine.FLASH_GAP_MS)
        assertEquals(listOf(GuidanceId.FLASH_AHEAD), ids(engine.on(NightEvent.ZoneEntered("b", Ring.APPROACH))))
    }

    @Test
    fun flashEndingWhileInside_releasesTheAnnouncedIntersection() {
        val late = FlashingIntersection("late", 37.60, 127.13, FlashWindow(23 * 60, 7 * 60), "", "", "")
        engine = NightEngine({ FlashMap(listOf(guri, late)) }, clock)
        clock.at(4, 59)
        drive()
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        clock.at(5, 5)
        engine.tick()
        assertNull("점멸이 끝난 교차로는 화면에서도 내린다", engine.state().approaching)
        assertEquals(listOf(GuidanceId.FLASH_AHEAD), ids(engine.on(NightEvent.ZoneEntered("late", Ring.APPROACH))))
    }

    @Test
    fun enteredBeforeFlashStarts_isAnnouncedWhenFlashingBegins() {
        clock.at(22, 59)
        drive()
        assertTrue(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH)).none { it.id == GuidanceId.FLASH_AHEAD })
        clock.at(23, 0)
        assertEquals(listOf(GuidanceId.FLASH_AHEAD), ids(engine.tick()))
    }

    @Test
    fun enteredWhileParked_isAnnouncedOnDeparture() {
        engine.on(NightEvent.LocationChanged(guri.lat, guri.lon))
        engine.on(NightEvent.GearChanged(Gear.PARK))
        assertTrue(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH)).isEmpty())
        assertTrue(GuidanceId.FLASH_AHEAD in ids(engine.on(NightEvent.GearChanged(Gear.DRIVE))))
    }

    @Test
    fun revalidate_dropsStaleFlashAndTurnGuidance() {
        drive()
        val ahead = engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH)).single()
        val left = engine.on(NightEvent.TurnSignalChanged(TurnSignal.LEFT)).single()
        engine.on(NightEvent.TurnSignalChanged(TurnSignal.RIGHT))
        assertNull("방향을 바꿨으면 좌회전 문장은 버린다", engine.revalidate(left))
        assertEquals(ahead, engine.revalidate(ahead))
        engine.on(NightEvent.ZoneExited("a", Ring.APPROACH))
        assertNull("교차로를 벗어났으면 버린다", engine.revalidate(ahead))
    }

    @Test
    fun revalidate_rebuildsRainGuidanceWithCurrentValues() {
        rainyDrive()
        engine.on(NightEvent.CruiseStateChanged(true))
        engine.on(NightEvent.CruiseTargetChanged(100))
        clock.plus(10_000)
        val g = engine.tick().single()
        engine.on(NightEvent.SpeedLimitChanged(80))
        assertEquals(listOf(80, 64, 100), engine.revalidate(g)?.args)
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.OFF))
        assertNull("와이퍼를 끄면 버린다", engine.revalidate(g))
    }

    @Test
    fun rain_isSilentInTheDaytime() {
        clock.at(14, 0)
        rainyDrive()
        engine.on(NightEvent.CruiseStateChanged(true))
        engine.on(NightEvent.CruiseTargetChanged(100))
        clock.plus(10_000)
        assertTrue(engine.tick().isEmpty())
        assertNull(engine.state().wetLimit)
    }

    @Test
    fun rain_withoutLocation_usesEveningHoursAsNight() {
        engine.on(NightEvent.GearChanged(Gear.DRIVE))
        engine.on(NightEvent.SpeedLimitChanged(100))
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.CONTINUOUS))
        clock.plus(10_000)
        assertEquals(80, engine.state().wetLimit)
    }

    @Test
    fun wiperPause_restartsTheTenSecondCount() {
        rainyDrive()
        engine.on(NightEvent.CruiseStateChanged(true))
        engine.on(NightEvent.CruiseTargetChanged(100))
        clock.plus(1_000)
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.OFF))
        clock.plus(9_000)
        assertTrue(engine.on(NightEvent.WiperSwitchChanged(WiperMode.CONTINUOUS)).isEmpty())
        clock.plus(9_000)
        assertTrue(engine.tick().isEmpty())
        clock.plus(1_000)
        assertEquals(listOf(GuidanceId.RAIN_SPEED_CRUISE), ids(engine.tick()))
    }

    @Test
    fun autoWiper_shortPausesBetweenWipes_doNotBreakTheCount() {
        drive()
        engine.on(NightEvent.SpeedLimitChanged(100))
        engine.on(NightEvent.WiperSwitchChanged(WiperMode.AUTO))
        engine.on(NightEvent.WiperMotionChanged(true))
        clock.plus(3_000)
        engine.on(NightEvent.WiperMotionChanged(false))
        clock.plus(4_000)
        engine.on(NightEvent.WiperMotionChanged(true))
        clock.plus(3_000)
        engine.tick()
        assertEquals(80, engine.state().wetLimit)
    }

    @Test
    fun records_areClearedTheNextNight() {
        drive()
        pass(30, 2, 30)
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        pass(40, 40)
        assertEquals(2, engine.passages.size)
        clock.at(23, 30)
        clock.plus(24 * 3_600_000L)
        engine.tick()
        assertTrue(engine.passages.isEmpty())
        assertTrue("지난 밤의 사실 안내도 버린다", engine.on(NightEvent.SpeedChanged(0)).isEmpty())
    }

    @Test
    fun recordsAfterMidnight_belongToTheSameNight() {
        clock.at(23, 50)
        drive()
        pass(30, 2, 30)
        clock.at(23, 59)
        clock.plus(2 * 3_600_000L)
        engine.tick()
        assertEquals(1, engine.passages.size)
    }

    @Test
    fun secondSummary_countsTheWholeNight() {
        drive()
        pass(30, 2, 30)
        assertEquals(listOf(1, 1), engine.on(NightEvent.GearChanged(Gear.PARK)).last().args)
        engine.on(NightEvent.GearChanged(Gear.DRIVE))
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        pass(40, 40)
        val out = engine.on(NightEvent.GearChanged(Gear.PARK))
        assertEquals(listOf(2, 1), out.last { it.id == GuidanceId.NIGHT_SUMMARY }.args)
    }

    @Test
    fun nightStart_waitsUntilTheDataIsLoaded() {
        var m = FlashMap.EMPTY
        engine = NightEngine({ m }, clock)
        engine.on(NightEvent.LocationChanged(guri.lat, guri.lon))
        assertTrue(engine.on(NightEvent.GearChanged(Gear.DRIVE)).isEmpty())
        m = map
        assertEquals(listOf(GuidanceId.NIGHT_START), ids(engine.tick()))
    }

    // ---- 2차 리뷰 반영 ----

    @Test
    fun queuedTurnGuidance_isDroppedWhenFlashingEnds() {
        clock.at(4, 59)
        drive()
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        val left = engine.on(NightEvent.TurnSignalChanged(TurnSignal.LEFT)).single()
        clock.at(5, 0)
        engine.tick()
        assertNull(engine.revalidate(left))
    }

    @Test
    fun alreadyPassedIntersection_isNotAnnouncedLate() {
        drive()
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        engine.on(NightEvent.ZoneEntered("b", Ring.APPROACH))
        pass(40, 40)
        engine.on(NightEvent.ZoneEntered("b", Ring.INSIDE))
        engine.on(NightEvent.ZoneExited("b", Ring.INSIDE))
        clock.plus(NightEngine.FLASH_GAP_MS)
        assertTrue(engine.on(NightEvent.ZoneExited("a", Ring.APPROACH)).none { it.id == GuidanceId.FLASH_AHEAD })
    }

    @Test
    fun noticeQueuedAtAStop_waitsForTheNextStopIfTheCarMovesOff() {
        drive()
        pass(45, 42, 44)
        val notice = engine.on(NightEvent.SpeedChanged(0)).single()
        engine.on(NightEvent.SpeedChanged(40))
        assertNull("다시 움직이면 지금은 말하지 않는다", engine.revalidate(notice))
        assertEquals(listOf(GuidanceId.FLASH_PASSED_WITHOUT_STOP), ids(engine.on(NightEvent.SpeedChanged(0))))
    }

    @Test
    fun summaryQueuedInPark_isToldAtTheNextParkIfTheCarLeaves() {
        drive()
        pass(30, 2, 30)
        val summary = engine.on(NightEvent.GearChanged(Gear.PARK)).last()
        engine.on(NightEvent.GearChanged(Gear.DRIVE))
        assertNull(engine.revalidate(summary))
        assertEquals(listOf(1, 1), engine.on(NightEvent.GearChanged(Gear.PARK)).last { it.id == GuidanceId.NIGHT_SUMMARY }.args)
    }

    @Test
    fun rainRevalidate_needsFiveSecondsAboveTheWetLimit() {
        rainyDrive()
        engine.on(NightEvent.CruiseStateChanged(true))
        engine.on(NightEvent.CruiseTargetChanged(100))
        clock.plus(10_000)
        val g = engine.tick().single()
        engine.on(NightEvent.CruiseStateChanged(false))
        engine.on(NightEvent.SpeedChanged(100))
        assertNull("크루즈를 끄고 막 빨라졌으면 아직 말하지 않는다", engine.revalidate(g))
        clock.plus(NightEngine.OVER_SUSTAIN_MS)
        engine.tick()
        assertEquals(GuidanceId.RAIN_SPEED, engine.revalidate(g)?.id)
    }

    @Test
    fun waitingInsideBeforeFlashStarts_isRecordedOnceItFlashes() {
        clock.at(22, 59)
        drive()
        engine.on(NightEvent.SpeedChanged(0))
        engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))
        engine.on(NightEvent.ZoneEntered("a", Ring.INSIDE))
        clock.at(23, 0)
        engine.tick()
        engine.on(NightEvent.SpeedChanged(40))
        engine.on(NightEvent.ZoneExited("a", Ring.INSIDE))
        assertEquals(1, engine.passages.size)
        assertEquals(0, engine.passages.single().minKmh)
    }
}
