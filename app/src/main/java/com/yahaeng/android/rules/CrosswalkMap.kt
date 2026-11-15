package com.yahaeng.android.rules

/**
 * 보행자 신호등도 집중 조명도 없는 것으로 등록된 2차로 이상 횡단보도 하나.
 *
 * @param road 공공데이터의 도로명(예: "아차산로"). 비어 있을 수 있다.
 * @param baseDate 데이터 기준일(지자체가 적은 값).
 */
data class DarkCrosswalk(
    val id: String,
    val lat: Double,
    val lon: Double,
    val lanes: Int,
    val road: String,
    val region: String,
    val baseDate: String,
)

/** 위치로 어두운 횡단보도를 찾는다. */
class CrosswalkMap(val crosswalks: List<DarkCrosswalk>) {

    private val byId = crosswalks.associateBy { it.id }
    private val grid = GeoGrid(crosswalks, { it.lat }, { it.lon })

    operator fun get(id: String): DarkCrosswalk? = byId[id]

    val size: Int get() = crosswalks.size

    fun near(lat: Double, lon: Double, radiusMeters: Double): List<Pair<DarkCrosswalk, Double>> = grid.near(lat, lon, radiusMeters)

    companion object {
        val EMPTY = CrosswalkMap(emptyList())
    }
}

/** `assets/dark_crosswalks.csv`(tools/crosswalks/build.py) 읽기. `#`으로 시작하는 줄과 머리줄은 건너뛴다. */
object CrosswalkCsv {
    fun parse(lines: Sequence<String>): CrosswalkMap = CrosswalkMap(
        lines.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("id,") }
            .mapNotNull { line ->
                val c = line.split(',')
                if (c.size < 7) return@mapNotNull null
                runCatching {
                    DarkCrosswalk(
                        id = c[0],
                        lat = c[1].toDouble(),
                        lon = c[2].toDouble(),
                        lanes = c[3].toInt(),
                        road = c[4].trim(),
                        region = c[5].trim(),
                        baseDate = c[6].trim(),
                    )
                }.getOrNull()
            }
            .toList(),
    )
}
