package com.yahaeng.android.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 시연 경로를 앱에 넣은 실제 데이터와 실제 판단 로직으로 돌려 본다. */
class DemoRouteTest {

    private val flash = File("src/main/assets/flashing_signals.csv").useLines { FlashCsv.parse(it) }
    private val crosswalks = File("src/main/assets/dark_crosswalks.csv").useLines { CrosswalkCsv.parse(it) }

    private class FakeClock(var millis: Long) : Clock {
        override fun nowMillis() = millis
    }

    /** 실제 컨트롤러처럼 이벤트를 넣고 1초마다 tick한다. */
    private fun play(): Pair<List<Guidance>, NightEngine> {
        val start = LocalDateTime.of(2026, 11, 15, 23, 30).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli()
        val clock = FakeClock(start)
        val engine = NightEngine({ flash }, clock, { crosswalks })
        val steps = DemoRoute.steps(flash)
        val out = mutableListOf<Guidance>()
        var i = 0
        var t = 0L
        while (t <= DemoRoute.durationMs(steps)) {
            while (i < steps.size && steps[i].atMs <= t) out += engine.on(steps[i++].event)
            out += engine.tick()
            t += 250
            clock.millis = start + t
        }
        return out to engine
    }

    @Test
    fun route_tellsEveryNightGuidance_inOrder() {
        val (out, engine) = play()
        val ids = out.map { it.id }
        val expected = listOf(
            GuidanceId.NIGHT_START,
            GuidanceId.DARK_CROSSWALK,
            GuidanceId.FLASH_AHEAD,
            GuidanceId.FLASH_TURN_LEFT,
            GuidanceId.FLASH_PASSED_WITHOUT_STOP,
            GuidanceId.NIGHT_SUMMARY,
            GuidanceId.RAIN_SPEED_CRUISE,
        )
        assertEquals(expected, ids)
        assertEquals(listOf(100, 80, 100), out.last().args)
        assertEquals(1, engine.passages.size)
        assertEquals("장자대로 89", engine.passages.single().name)
        assertTrue("길 위의 어두운 횡단보도를 기록한다", engine.crossings.isNotEmpty())
    }

    @Test
    fun rainSpot_isFarFromAnyFlashingIntersectionOrDarkCrosswalk() {
        assertTrue(flash.near(DemoRoute.RAIN_LAT, DemoRoute.RAIN_LON, 1_000.0).isEmpty())
        assertTrue(crosswalks.near(DemoRoute.RAIN_LAT, DemoRoute.RAIN_LON, 1_000.0).isEmpty())
    }

    @Test
    fun steps_areInTimeOrder_andEndParked() {
        val steps = DemoRoute.steps(flash)
        assertEquals(steps.map { it.atMs }, steps.map { it.atMs }.sorted())
        assertEquals(NightEvent.GearChanged(Gear.PARK), steps.last().event)
        assertTrue(DemoRoute.durationMs(steps) in 80_000L..120_000L)
    }

    @Test
    fun demoClock_startsAt2330Korea_andRunsWithRealTime() {
        val real = FakeClock(LocalDateTime.of(2026, 11, 15, 14, 5).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli())
        val demo = DemoClock(real)
        assertEquals(23 * 60 + 30, demo.minuteOfDay())
        real.millis += 90_000
        assertEquals(23 * 60 + 31, demo.minuteOfDay())
        assertEquals(Instant.ofEpochMilli(real.millis).atZone(Clock.KOREA).toLocalDate(), demo.koreaDate())
    }
}
