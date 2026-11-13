package com.yahaeng.android.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FlashMapTest {

    @Test
    fun window_crossingMidnight() {
        val w = FlashWindow(23 * 60, 5 * 60)
        assertTrue(w.contains(23 * 60))
        assertTrue(w.contains(0))
        assertTrue(w.contains(4 * 60 + 59))
        assertFalse("끝 시각은 이미 끝난 때", w.contains(5 * 60))
        assertFalse(w.contains(14 * 60))
        assertEquals(0, w.minutesUntilStart(1 * 60))
        assertEquals(30, w.minutesUntilStart(22 * 60 + 30))
        assertEquals("23:00~05:00", w.label())
    }

    @Test
    fun window_sameDay() {
        val w = FlashWindow(1 * 60, 6 * 60)
        assertTrue(w.contains(3 * 60))
        assertFalse(w.contains(23 * 60))
        assertEquals(120, w.minutesUntilStart(23 * 60))
    }

    @Test
    fun csv_skipsCommentsHeaderAndBrokenLines() {
        val map = FlashCsv.parse(
            sequenceOf(
                "# 출처",
                "id,lat,lon,start,end,name,region,base",
                "a1,37.59430,127.12960,1380,300,인창사거리,경기도 구리시,2026-11-05",
                "a2,37.60000,127.13000,1439,300,,경기도 구리시,2026-11-05",
                "bad,x,127,1,2,,r,d",
                "short,1,2",
            ),
        )
        assertEquals(2, map.size)
        assertEquals("인창사거리", map["a1"]?.name)
        assertEquals("", map["a2"]?.name)
        assertEquals(FlashWindow(1439, 300), map["a2"]?.window)
    }

    @Test
    fun near_returnsClosestFirst_withinTheRadius() {
        val a = FlashingIntersection("a", 37.5943, 127.1296, FlashWindow(1380, 300), "", "", "")
        val b = FlashingIntersection("b", 37.6043, 127.1296, FlashWindow(1380, 300), "", "", "")
        val c = FlashingIntersection("c", 37.9000, 127.1296, FlashWindow(1380, 300), "", "", "")
        val map = FlashMap(listOf(c, b, a))
        val near = map.near(37.5950, 127.1296, 3_000.0)
        assertEquals(listOf("a", "b"), near.map { it.first.id })
        assertTrue(near[0].second < 100)
    }

    @Test
    fun planner_picksOnlyFlashingOrSoonToFlash_andCapsTheCount() {
        val list = (0 until 20).map { i -> FlashingIntersection("p$i", 37.59 + i * 0.001, 127.13, FlashWindow(23 * 60, 5 * 60), "", "", "") } +
            FlashingIntersection("later", 37.59, 127.1301, FlashWindow(2 * 60, 5 * 60), "", "", "")
        val map = FlashMap(list)
        val at2250 = ProximityPlanner.plan(map, 37.59, 127.13, 22 * 60 + 50)
        assertEquals(ProximityPlanner.MAX_INTERSECTIONS * Ring.entries.size, at2250.size)
        assertTrue("01:00 시작은 아직 멀다", at2250.none { it.intersectionId == "later" })
        val at1400 = ProximityPlanner.plan(map, 37.59, 127.13, 14 * 60)
        assertTrue(at1400.isEmpty())
        assertEquals(setOf("p0:APPROACH", "p0:INSIDE"), at2250.filter { it.intersectionId == "p0" }.map { it.key }.toSet())
    }

    @Test
    fun sunTimes_seoulInNovember() {
        val sun = SunTimes.of(LocalDate.of(2026, 11, 13), 37.57, 126.98)!!
        // 서울 11월 중순 일몰은 17시 20분 무렵, 일출은 7시 10분 무렵
        assertTrue(sun.sunsetMinute in (17 * 60 + 10)..(17 * 60 + 30))
        assertTrue(sun.sunriseMinute in (7 * 60)..(7 * 60 + 20))
        assertTrue(sun.isNight(23 * 60))
        assertTrue(sun.isNight(3 * 60))
        assertFalse(sun.isNight(12 * 60))
    }
}
