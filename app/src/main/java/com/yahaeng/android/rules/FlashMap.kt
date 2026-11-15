package com.yahaeng.android.rules

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

/** 위치로 점멸 교차로를 찾는다. */
class FlashMap(val intersections: List<FlashingIntersection>) {

    private val byId = intersections.associateBy { it.id }
    private val grid = GeoGrid(intersections, { it.lat }, { it.lon })

    operator fun get(id: String): FlashingIntersection? = byId[id]

    val size: Int get() = intersections.size

    /** [radiusMeters] 안의 교차로를 가까운 순으로. 반경은 수 km 정도까지만 쓴다(격자 탐색 범위). */
    fun near(lat: Double, lon: Double, radiusMeters: Double): List<Pair<FlashingIntersection, Double>> = grid.near(lat, lon, radiusMeters)

    companion object {
        val EMPTY = FlashMap(emptyList())

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double = Geo.distanceMeters(lat1, lon1, lat2, lon2)
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
