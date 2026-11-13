package com.yahaeng.android.rules

/** 테스트용 시계. [now]를 직접 옮긴다. */
class FakeClock(var now: Long = 1_000_000L) : Clock {
    override fun nowMillis() = now
    fun advanceSeconds(seconds: Long) { now += seconds * 1000 }
}
