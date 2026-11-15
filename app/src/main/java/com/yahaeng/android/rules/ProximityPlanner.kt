package com.yahaeng.android.rules

/** 교차로마다 거는 근접 알림 두 개. 다가갈 때 알리는 바깥 고리와, 실제로 지나는지 보는 안쪽 고리. */
enum class Ring(val radiusMeters: Float) {
    APPROACH(300f),
    INSIDE(70f),
}

/** FusedLocation 근접 알림 하나. [key]가 같으면 같은 알림이다. */
data class ZoneSpec(val intersectionId: String, val ring: Ring, val lat: Double, val lon: Double) {
    val key: String get() = "$intersectionId:${ring.name}"
    val radiusMeters: Float get() = ring.radiusMeters
}

/**
 * 지금 위치와 시각에서 근접 알림을 걸 교차로를 고른다(순수 함수).
 *
 * - [SEARCH_METERS] 안에서 지금 점멸 중이거나 [LEAD_MINUTES] 안에 점멸이 시작될 교차로만.
 * - 가까운 [MAX_INTERSECTIONS]곳까지만(알림 수를 줄여 위치 서비스 부담을 덜기 위해).
 */
object ProximityPlanner {
    const val SEARCH_METERS = 3_000.0
    const val LEAD_MINUTES = 20
    const val MAX_INTERSECTIONS = 12

    /** 이만큼 움직이면 다시 고른다. */
    const val REPLAN_METERS = 500.0

    fun plan(map: FlashMap, lat: Double, lon: Double, minuteOfDay: Int): List<ZoneSpec> =
        map.near(lat, lon, SEARCH_METERS)
            .filter { (p, _) -> p.window.minutesUntilStart(minuteOfDay) <= LEAD_MINUTES }
            .take(MAX_INTERSECTIONS)
            .flatMap { (p, _) -> Ring.entries.map { ZoneSpec(p.id, it, p.lat, p.lon) } }
}
