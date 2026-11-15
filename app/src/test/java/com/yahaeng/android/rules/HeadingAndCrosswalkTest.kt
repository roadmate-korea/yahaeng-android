package com.yahaeng.android.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class HeadingAndCrosswalkTest {

    private class FakeClock(var millis: Long) : Clock {
        override fun nowMillis() = millis
        fun at(h: Int, m: Int) { millis = LocalDateTime.of(2026, 11, 13, h, m).atZone(ZoneId.of("Asia/Seoul")).toInstant().toEpochMilli() }
        fun plus(ms: Long) { millis += ms }
    }

    private companion object {
        const val M_PER_DEG_LAT = 111_195.0
        const val LON_SCALE = 0.7923 // cos(37.6도)
    }

    // 구리시 근처. 23:00~05:00 점멸
    private val flash = FlashingIntersection("a", 37.5943, 127.1296, FlashWindow(23 * 60, 5 * 60), "인창사거리", "경기도 구리시", "2026-11-05")
    private val flashMap = FlashMap(listOf(flash))

    // 교차로에서 2km 떨어진 곳의 어두운 횡단보도 두 개(남북으로 100m 간격)
    private val cw = DarkCrosswalk("c1", 37.6123, 127.1296, 4, "한다리길", "경기도 구리시", "2026-07-01")
    private val cw2 = DarkCrosswalk("c2", cw.lat + 100 / M_PER_DEG_LAT, cw.lon, 2, "", "경기도 구리시", "2026-07-01")
    private var crosswalkMap = CrosswalkMap(listOf(cw))

    private lateinit var clock: FakeClock
    private lateinit var engine: NightEngine

    @Before
    fun setUp() {
        clock = FakeClock(0).apply { at(23, 30) }
        engine = NightEngine({ flashMap }, clock, { crosswalkMap })
    }

    private fun ids(list: List<Guidance>) = list.map { it.id }

    /** [base]에서 북쪽으로 [north]m, 동쪽으로 [east]m 떨어진 곳으로 1초 뒤 옮긴다. */
    private fun moveTo(baseLat: Double, baseLon: Double, north: Double, east: Double = 0.0): List<Guidance> {
        clock.plus(1_000)
        return engine.on(NightEvent.LocationChanged(baseLat + north / M_PER_DEG_LAT, baseLon + east / (M_PER_DEG_LAT * LON_SCALE)))
    }

    /** 남쪽에서 [from]m부터 [to]m까지 [step]m씩 북쪽으로 달린다(음수는 남쪽). 나온 안내를 모두 돌려준다. */
    private fun driveNorth(lat: Double, lon: Double, from: Int, to: Int, step: Int = 10): List<Guidance> =
        (from..to step step).flatMap { moveTo(lat, lon, it.toDouble()) }

    private fun startDriving(kmh: Int) {
        engine.on(NightEvent.GearChanged(Gear.DRIVE))
        engine.on(NightEvent.SpeedChanged(kmh))
    }

    // ---- 진행 방향 ----

    @Test
    fun heading_comesFromRecentFixes_andStaysWhileStopped() {
        val t = HeadingTracker()
        t.add(37.0, 127.0, 0)
        assertNull("위치 하나로는 방향을 모른다", t.heading(0))
        t.add(37.0 + 10 / M_PER_DEG_LAT, 127.0, 1_000)
        assertNull("15m 안으로는 방향을 잡지 않는다", t.heading(1_000))
        t.add(37.0 + 20 / M_PER_DEG_LAT, 127.0, 2_000)
        val north = t.heading(2_000)
        assertNotNull(north)
        assertTrue(Geo.angleBetween(north!!, 0.0) < 1)
        // 멈춰 있어도 방향은 그대로 둔다
        repeat(30) { t.add(37.0 + 20 / M_PER_DEG_LAT, 127.0, 3_000L + it * 1_000) }
        assertNotNull(t.heading(32_000))
        assertNull("멈춘 뒤 오래 지나면 버린다", t.heading(32_000 + HeadingTracker.KEEP_MS + 1))
    }

    @Test
    fun heading_turnsEast() {
        val t = HeadingTracker()
        t.add(37.0, 127.0, 0)
        t.add(37.0, 127.0 + 20 / (M_PER_DEG_LAT * LON_SCALE), 1_000)
        assertTrue(Geo.angleBetween(t.heading(1_000)!!, 90.0) < 2)
    }

    @Test
    fun flashAhead_isSaid_whenTheIntersectionIsAhead() {
        startDriving(40)
        driveNorth(flash.lat, flash.lon, -400, -320)
        moveTo(flash.lat, flash.lon, -300.0)
        assertEquals(listOf(GuidanceId.FLASH_AHEAD), ids(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))).filter { it == GuidanceId.FLASH_AHEAD })
    }

    @Test
    fun flashBehind_isSilent_untilTheCarTurnsTowardIt() {
        startDriving(40)
        // 교차로를 지나 북쪽으로 멀어지는 중(안쪽 고리는 거치지 않은 옆길이라고 본다)
        driveNorth(flash.lat, flash.lon, 100, 250)
        assertTrue(ids(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))).none { it == GuidanceId.FLASH_AHEAD })
        assertNull(engine.state().approaching)
        // 돌아서 교차로 쪽(남쪽)으로 달리면 그때 말한다
        val out = listOf(270, 250, 230, 210).flatMap { moveTo(flash.lat, flash.lon, it.toDouble()) }
        assertEquals(1, ids(out).count { it == GuidanceId.FLASH_AHEAD })
    }

    @Test
    fun flashToTheSide_isSilent() {
        startDriving(40)
        // 교차로 남쪽 200m를 동쪽으로 지나간다
        (-300..-100 step 20).forEach { moveTo(flash.lat, flash.lon, -200.0, it.toDouble()) }
        assertTrue(ids(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))).none { it == GuidanceId.FLASH_AHEAD })
    }

    @Test
    fun queuedFlashAhead_isDropped_whenTheCarTurnsAway_andSaidAgainWhenAhead() {
        startDriving(40)
        driveNorth(flash.lat, flash.lon, -350, -290)
        val g = engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH)).first { it.id == GuidanceId.FLASH_AHEAD }
        // 재생 전에 유턴해서 남쪽으로 멀어진다
        listOf(-300, -320, -340).forEach { moveTo(flash.lat, flash.lon, it.toDouble()) }
        assertNull(engine.revalidate(g))
        // 다시 북쪽으로 돌면 그때 말한다
        val out = listOf(-320, -300, -280).flatMap { moveTo(flash.lat, flash.lon, it.toDouble()) }
        clock.plus(NightEngine.FLASH_GAP_MS)
        assertEquals(1, ids(out + engine.tick()).count { it == GuidanceId.FLASH_AHEAD })
    }

    // ---- 어두운 횡단보도 ----

    @Test
    fun darkCrosswalk_isSaidOnce_whenApproachingFasterThan30() {
        startDriving(40)
        val out = driveNorth(cw.lat, cw.lon, -300, -60)
        assertEquals(listOf(GuidanceId.DARK_CROSSWALK), ids(out).filter { it == GuidanceId.DARK_CROSSWALK })
        assertEquals("c1", out.first { it.id == GuidanceId.DARK_CROSSWALK }.key)
        assertEquals("c1", engine.state().crosswalk)
    }

    @Test
    fun darkCrosswalk_isSilent_at30OrBelow() {
        startDriving(30)
        assertTrue(ids(driveNorth(cw.lat, cw.lon, -300, -60)).none { it == GuidanceId.DARK_CROSSWALK })
    }

    @Test
    fun darkCrosswalk_isSilent_inTheDaytime() {
        clock.at(14, 0)
        startDriving(50)
        assertTrue(ids(driveNorth(cw.lat, cw.lon, -300, -60)).none { it == GuidanceId.DARK_CROSSWALK })
    }

    @Test
    fun darkCrosswalk_isSilent_whenItIsBehind() {
        startDriving(50)
        assertTrue(ids(driveNorth(cw.lat, cw.lon, 40, 140)).none { it == GuidanceId.DARK_CROSSWALK })
    }

    @Test
    fun darkCrosswalk_waitsForAHeading() {
        startDriving(50)
        // 위치 하나로는 방향을 모르니 말하지 않고, 두 번째 위치로 앞쪽임을 알면 말한다
        assertTrue(ids(moveTo(cw.lat, cw.lon, -120.0)).none { it == GuidanceId.DARK_CROSSWALK })
        assertTrue(ids(moveTo(cw.lat, cw.lon, -100.0)).contains(GuidanceId.DARK_CROSSWALK))
    }

    @Test
    fun darkCrosswalk_isSilent_whenParked() {
        engine.on(NightEvent.GearChanged(Gear.PARK))
        engine.on(NightEvent.SpeedChanged(40))
        assertTrue(ids(driveNorth(cw.lat, cw.lon, -300, -60)).none { it == GuidanceId.DARK_CROSSWALK })
    }

    @Test
    fun crossing_isRecordedAsSlowed_whenTheCarSlowsWithin30m() {
        startDriving(40)
        driveNorth(cw.lat, cw.lon, -300, -40)
        moveTo(cw.lat, cw.lon, -20.0)
        engine.on(NightEvent.SpeedChanged(22))
        moveTo(cw.lat, cw.lon, 0.0)
        engine.on(NightEvent.SpeedChanged(35))
        driveNorth(cw.lat, cw.lon, 20, 80)
        val c = engine.crossings.single()
        assertEquals(22, c.minKmh)
        assertEquals(Crossing.Outcome.SLOWED, c.outcome)
        assertNull("지난 뒤에는 화면에서 내린다", engine.state().crosswalk)
    }

    @Test
    fun crossing_isRecordedAsPassed_whenTheCarKeepsItsSpeed() {
        startDriving(45)
        driveNorth(cw.lat, cw.lon, -300, 80)
        assertEquals(Crossing.Outcome.PASSED, engine.crossings.single().outcome)
    }

    @Test
    fun crossing_isNotRecorded_whenTheCarLeavesWithoutPassing() {
        startDriving(45)
        driveNorth(cw.lat, cw.lon, -300, -100)
        // 횡단보도 앞에서 동쪽 길로 빠진다
        (20..300 step 20).forEach { moveTo(cw.lat, cw.lon, -100.0, it.toDouble()) }
        assertTrue(engine.crossings.isEmpty())
        assertNull(engine.state().crosswalk)
    }

    @Test
    fun queuedCrosswalk_isDropped_afterPassingIt() {
        startDriving(40)
        val g = driveNorth(cw.lat, cw.lon, -300, -60).first { it.id == GuidanceId.DARK_CROSSWALK }
        assertEquals(g, engine.revalidate(g))
        driveNorth(cw.lat, cw.lon, -40, 60)
        assertNull(engine.revalidate(g))
    }

    @Test
    fun denseCrosswalks_areNotSaidBackToBack() {
        crosswalkMap = CrosswalkMap(listOf(cw, cw2))
        startDriving(40)
        // 첫 횡단보도를 알리고, 100m 뒤 두 번째 횡단보도는 첫 고리 안이라 말하지 않는다
        val out = driveNorth(cw.lat, cw.lon, -300, 100, step = 15)
        assertEquals(listOf("c1"), out.filter { it.id == GuidanceId.DARK_CROSSWALK }.map { it.key })
    }

    @Test
    fun crosswalk_waits_whileAFlashIntersectionWasAnnounced() {
        crosswalkMap = CrosswalkMap(listOf(cw.copy(lat = flash.lat + 120 / M_PER_DEG_LAT, lon = flash.lon)))
        startDriving(40)
        driveNorth(flash.lat, flash.lon, -400, -300)
        assertTrue(ids(engine.on(NightEvent.ZoneEntered("a", Ring.APPROACH))).contains(GuidanceId.FLASH_AHEAD))
        // 점멸 교차로를 알린 고리(300m) 안에서는 바로 뒤 횡단보도를 말하지 않는다
        assertTrue(ids(driveNorth(flash.lat, flash.lon, -290, 0)).none { it == GuidanceId.DARK_CROSSWALK })
    }

    @Test
    fun crossings_areClearedOnTheNextNight() {
        startDriving(45)
        driveNorth(cw.lat, cw.lon, -300, 80)
        assertEquals(1, engine.crossings.size)
        clock.plus(24 * 3_600_000L)
        engine.tick()
        assertTrue(engine.crossings.isEmpty())
    }

    @Test
    fun crosswalkCsv_parsesTheAssetFormat() {
        val m = CrosswalkCsv.parse(
            sequenceOf(
                "# 주석",
                "id,lat,lon,lanes,road,region,base",
                "96624260fa,37.583607,127.129367,3,한다리길,경기도 구리시,2026-07-01",
                "bad,line",
                "c92bd79689,37.584556,127.139848,2,,경기도 구리시,2026-07-01",
            ),
        )
        assertEquals(2, m.size)
        assertEquals("한다리길", m["96624260fa"]?.road)
        assertEquals(3, m["96624260fa"]?.lanes)
        assertEquals(1, m.near(37.5836, 127.1294, 50.0).size)
    }
}
