package com.yahaeng.android.demo

import com.yahaeng.android.drive.RawSignal
import com.yahaeng.android.rules.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** release 빌드: 시연 입력이 없다. 늘 실제 시각과 실제 SDK 신호만 쓴다. */
object DemoSupport {
    val clock: Clock = Clock.SYSTEM
    val input: Flow<RawSignal> = emptyFlow()
    val overridesSpeedLimit: Boolean = false
}
