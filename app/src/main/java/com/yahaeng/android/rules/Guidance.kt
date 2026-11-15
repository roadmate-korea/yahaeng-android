package com.yahaeng.android.rules

/** 위험도 순. 값이 클수록 먼저 말한다. */
enum class Priority { INFO, RULE, SAFETY }

/** 안내 종류. 문장은 앱 리소스에서 이 ID로 찾는다. */
enum class GuidanceId(val priority: Priority) {
    /** 해가 졌다(또는 밤에 시동). 밤 안내를 켠다는 한 문장. 근처 교차로 수 같은 숫자는 말하지 않는다. */
    NIGHT_START(Priority.INFO),

    /** 지금 점멸로 운영 중인 교차로에 다가감. key: 교차로 ID */
    FLASH_AHEAD(Priority.SAFETY),

    /** 점멸 교차로 앞에서 좌측 방향지시등. key: 교차로 ID */
    FLASH_TURN_LEFT(Priority.SAFETY),

    /** 점멸 교차로 앞에서 우측 방향지시등. key: 교차로 ID */
    FLASH_TURN_RIGHT(Priority.SAFETY),

    /** 점멸 교차로를 멈추지 않고 지난 뒤, 차가 멈췄을 때 사실을 한 번 알림. key: 교차로 ID, args: [가장 낮은 속도] */
    FLASH_PASSED_WITHOUT_STOP(Priority.INFO),

    /**
     * 밤에 보행자 신호등도 집중 조명도 없는 횡단보도에 시속 30km 넘게 다가감. key: 횡단보도 ID.
     * 점멸 교차로 안내와 겹치면 점멸 안내를 먼저 말하도록 한 단계 낮춘다.
     */
    DARK_CROSSWALK(Priority.RULE),

    /** 와이퍼가 계속 돌고 차가 젖은 노면 기준 속도보다 빠름. args: [제한 속도, 20% 줄인 속도] */
    RAIN_SPEED(Priority.RULE),

    /** 와이퍼가 계속 돌고 크루즈 목표 속도가 젖은 노면 기준보다 높음. args: [제한 속도, 20% 줄인 속도, 크루즈 목표 속도] */
    RAIN_SPEED_CRUISE(Priority.RULE),

    /** P에서 오늘 밤 기록 요약. args: [지난 점멸 교차로 수, 멈춘 곳 수] */
    NIGHT_SUMMARY(Priority.INFO),
}

data class Guidance(val id: GuidanceId, val args: List<Int> = emptyList(), val key: String = "") {
    val priority: Priority get() = id.priority
}
