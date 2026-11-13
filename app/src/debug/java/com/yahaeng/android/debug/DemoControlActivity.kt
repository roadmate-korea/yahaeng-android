package com.yahaeng.android.debug

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.yahaeng.android.YahaengApplication
import com.yahaeng.android.demo.DemoSupport

/**
 * 시연 입력(디버그 빌드에만 있음). 낮에 찍는 시연 영상에서 밤 시각을 재현하고, 에뮬레이터가 0으로 주는
 * 제한 속도를 넣는다. 넣은 값은 실제 SDK 신호와 같은 [RawSignal] 경로로 판단 로직에 들어간다.
 *
 *     adb shell am start --user 10 -n com.yahaeng.android/.debug.DemoControlActivity --es time 23:30
 *     adb shell am start --user 10 -n com.yahaeng.android/.debug.DemoControlActivity --ei limit 100
 *     adb shell am start --user 10 -n com.yahaeng.android/.debug.DemoControlActivity --ez reset true
 */
class DemoControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as YahaengApplication
        intent.getStringExtra("time")?.let { t ->
            val (h, m) = t.split(':').map { it.toInt() }
            DemoSupport.setKoreaTime(h * 60 + m)
            Log.i(TAG, "demo time $t")
        }
        if (intent.hasExtra("limit")) {
            val limit = intent.getIntExtra("limit", 0)
            DemoSupport.setSpeedLimit(limit)
            Log.i(TAG, "demo speed limit $limit")
        }
        if (intent.getBooleanExtra("reset", false)) {
            DemoSupport.reset()
            Log.i(TAG, "demo reset")
        }
        // 세션이 아직 없으면 연다(시연 입력만으로 앱을 깨운 경우)
        app.controller.start()
        finish()
    }

    private companion object {
        const val TAG = "YahaengDemo"
    }
}
