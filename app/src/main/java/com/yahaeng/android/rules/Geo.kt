package com.yahaeng.android.rules

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** 위치 계산(순수 Kotlin). */
object Geo {
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = lat1 * PI / 180
        val p2 = lat2 * PI / 180
        val dp = (lat2 - lat1) * PI / 180
        val dl = (lon2 - lon1) * PI / 180
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(sqrt(a))
    }

    /** 1에서 2로 가는 방위각(북 0, 동 90, 0~360). */
    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * PI / 180
        val p2 = lat2 * PI / 180
        val dl = (lon2 - lon1) * PI / 180
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (atan2(y, x) * 180 / PI + 360) % 360
    }

    /** 두 방위각 사이 각도(0~180). */
    fun angleBetween(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
    }
}

/** 위도와 경도 0.01도 격자로 나눠 둔 지점 목록. 반경 검색을 빠르게 한다. */
class GeoGrid<T>(items: List<T>, private val latOf: (T) -> Double, private val lonOf: (T) -> Double) {

    private val grid: Map<Long, List<T>> = items.groupBy { cell(latOf(it), lonOf(it)) }

    /** [radiusMeters] 안의 지점을 가까운 순으로. 반경은 수 km 정도까지만 쓴다(격자 탐색 범위). */
    fun near(lat: Double, lon: Double, radiusMeters: Double): List<Pair<T, Double>> {
        val span = (radiusMeters / CELL_METERS).toInt() + 1
        val (cy, cx) = cellXY(lat, lon)
        val found = ArrayList<Pair<T, Double>>()
        for (dy in -span..span) for (dx in -span..span) {
            grid[key(cy + dy, cx + dx)]?.forEach { p ->
                val d = Geo.distanceMeters(lat, lon, latOf(p), lonOf(p))
                if (d <= radiusMeters) found += p to d
            }
        }
        return found.sortedBy { it.second }
    }

    private companion object {
        const val CELL_DEG = 0.01

        /** 격자 한 칸의 대략적인 크기(위도 방향, 경도 방향은 한국에서 이보다 조금 작다). */
        const val CELL_METERS = 880.0

        fun cellXY(lat: Double, lon: Double) = floor(lat / CELL_DEG).toLong() to floor(lon / CELL_DEG).toLong()
        fun key(y: Long, x: Long) = y * 1_000_000L + x
        fun cell(lat: Double, lon: Double) = cellXY(lat, lon).let { key(it.first, it.second) }
    }
}

/**
 * 최근 위치로 진행 방향을 구한다. FusedLocation의 heading은 에뮬레이터에서 늘 0이고 실차에서도 저속일 때 흔들려서 쓰지 않는다.
 *
 * 최근 [WINDOW_MS] 안의 위치 가운데 지금 위치에서 [MIN_METERS] 넘게 떨어진 가장 최근 위치를 기준으로 방위각을 잡는다.
 * 멈춰 있으면 방향이 바뀌지 않으므로 마지막으로 구한 방향을 [KEEP_MS] 동안 둔다.
 */
class HeadingTracker {
    private class Fix(val lat: Double, val lon: Double, val at: Long)

    private val fixes = ArrayDeque<Fix>()
    private var heading: Double? = null
    private var headingAt = 0L

    fun add(lat: Double, lon: Double, now: Long) {
        fixes.addLast(Fix(lat, lon, now))
        while (fixes.size > MAX_FIXES || (fixes.isNotEmpty() && now - fixes.first().at > WINDOW_MS)) fixes.removeFirst()
        for (i in fixes.size - 2 downTo 0) {
            val f = fixes[i]
            if (Geo.distanceMeters(f.lat, f.lon, lat, lon) >= MIN_METERS) {
                heading = Geo.bearing(f.lat, f.lon, lat, lon)
                headingAt = now
                return
            }
        }
    }

    /** 지금 진행 방향(북 0, 0~360). 아직 모르거나 오래됐으면 null. */
    fun heading(now: Long): Double? = heading?.takeIf { now - headingAt <= KEEP_MS }

    fun clear() {
        fixes.clear()
        heading = null
    }

    companion object {
        const val MIN_METERS = 15.0
        const val WINDOW_MS = 20_000L
        const val KEEP_MS = 120_000L
        const val MAX_FIXES = 120

        /** 진행 방향에서 좌우 이 각도 안이면 앞쪽으로 본다. */
        const val AHEAD_DEGREES = 60.0
    }
}
