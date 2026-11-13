package com.yahaeng.android.rules

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 점멸 운영 시간대. 분 단위(0~1439). 끝이 시작보다 작으면 자정을 넘는다(예: 23:00~05:00).
 * 끝 시각 자체는 점멸이 끝난 때라 포함하지 않는다.
 */
data class FlashWindow(val startMinute: Int, val endMinute: Int) {
    init {
        require(startMinute in 0..1439 && endMinute in 0..1439) { "minute out of range" }
        require(startMinute != endMinute) { "empty window" }
    }

    fun contains(minuteOfDay: Int): Boolean =
        if (startMinute < endMinute) minuteOfDay in startMinute until endMinute
        else minuteOfDay >= startMinute || minuteOfDay < endMinute

    /** [minuteOfDay]부터 점멸이 시작되기까지 남은 분. 이미 점멸 중이면 0. */
    fun minutesUntilStart(minuteOfDay: Int): Int =
        if (contains(minuteOfDay)) 0 else Math.floorMod(startMinute - minuteOfDay, 1440)

    fun label(): String = "%02d:%02d~%02d:%02d".format(startMinute / 60, startMinute % 60, endMinute / 60, endMinute % 60)
}

/**
 * 밤에만 점멸로 바뀌는 교차로 하나. 같은 교차로의 신호등 여러 개를 하나로 합친 것이다.
 *
 * @param name 공공데이터의 설치 장소(예: "인창동 행정복지센터 앞"). 비어 있을 수 있다.
 * @param baseDate 데이터 기준일(지자체가 적은 값).
 */
data class FlashingIntersection(
    val id: String,
    val lat: Double,
    val lon: Double,
    val window: FlashWindow,
    val name: String,
    val region: String,
    val baseDate: String,
)

/** 위치로 점멸 교차로를 찾는다. 위도와 경도 0.01도 격자로 나눠 둔다. */
class FlashMap(val intersections: List<FlashingIntersection>) {

    private val byId = intersections.associateBy { it.id }
    private val grid: Map<Long, List<FlashingIntersection>> = intersections.groupBy { cell(it.lat, it.lon) }

    operator fun get(id: String): FlashingIntersection? = byId[id]

    val size: Int get() = intersections.size

    /** [radiusMeters] 안의 교차로를 가까운 순으로. 반경은 수 km 정도까지만 쓴다(격자 탐색 범위). */
    fun near(lat: Double, lon: Double, radiusMeters: Double): List<Pair<FlashingIntersection, Double>> {
        val span = (radiusMeters / CELL_METERS).toInt() + 1
        val (cy, cx) = cellXY(lat, lon)
        val found = ArrayList<Pair<FlashingIntersection, Double>>()
        for (dy in -span..span) for (dx in -span..span) {
            grid[key(cy + dy, cx + dx)]?.forEach { p ->
                val d = distanceMeters(lat, lon, p.lat, p.lon)
                if (d <= radiusMeters) found += p to d
            }
        }
        return found.sortedBy { it.second }
    }

    companion object {
        val EMPTY = FlashMap(emptyList())

        private const val CELL_DEG = 0.01

        /** 격자 한 칸의 대략적인 크기(위도 방향, 경도 방향은 한국에서 이보다 조금 작다). */
        private const val CELL_METERS = 880.0

        private fun cellXY(lat: Double, lon: Double) = floor(lat / CELL_DEG).toLong() to floor(lon / CELL_DEG).toLong()
        private fun key(y: Long, x: Long) = y * 1_000_000L + x
        private fun cell(lat: Double, lon: Double) = cellXY(lat, lon).let { key(it.first, it.second) }

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = lat1 * PI / 180
            val p2 = lat2 * PI / 180
            val dp = (lat2 - lat1) * PI / 180
            val dl = (lon2 - lon1) * PI / 180
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(sqrt(a))
        }
    }
}

/** `assets/flashing_signals.csv`(tools/signals/build.py) 읽기. `#`으로 시작하는 줄과 머리줄은 건너뛴다. */
object FlashCsv {
    fun parse(lines: Sequence<String>): FlashMap = FlashMap(
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("id,") }
            .mapNotNull { line ->
                val c = line.split(',')
                if (c.size < 8) return@mapNotNull null
                runCatching {
                    FlashingIntersection(
                        id = c[0],
                        lat = c[1].toDouble(),
                        lon = c[2].toDouble(),
                        window = FlashWindow(c[3].toInt(), c[4].toInt()),
                        name = c[5].trim(),
                        region = c[6].trim(),
                        baseDate = c[7].trim(),
                    )
                }.getOrNull()
            }
            .toList(),
    )
}
