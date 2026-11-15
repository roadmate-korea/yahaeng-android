package com.yahaeng.android.voice

import com.yahaeng.android.rules.Clock
import com.yahaeng.android.rules.Guidance
import com.yahaeng.android.rules.GuidanceId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class GuidanceSpeakerTest {

    private class FakeEngine(private val ready: Boolean = true) : TtsEngine {
        var registered: TtsEngine.Listener? = null
        val spoken = mutableListOf<String>()
        var stops = 0
        var released = false
        private var pendingInit: ((Boolean) -> Unit)? = null

        override fun initialize(onResult: (ready: Boolean) -> Unit) {
            pendingInit = onResult
        }

        fun finishInit() = pendingInit!!.invoke(ready)

        override fun setListener(listener: TtsEngine.Listener?) {
            registered = listener
        }

        override fun speak(text: String, locale: Locale) {
            spoken += text
        }

        override fun stop() {
            stops++
        }

        override fun release() {
            released = true
        }

        /** 지금 문장을 끝까지 말한 것처럼 신호를 보낸다. */
        fun complete() {
            registered!!.onStart()
            registered!!.onDone()
        }
    }

    private val fr = Locale.FRANCE
    private val en = Locale.US

    private fun TestScope.speaker(engine: FakeEngine): GuidanceSpeaker {
        val clock = Clock { testScheduler.currentTime }
        return GuidanceSpeaker(engine, backgroundScope, clock).also {
            it.start()
            engine.finishInit()
        }
    }

    private fun g(id: GuidanceId) = Guidance(id)

    @Test
    fun speaksOneAtATime_inOrder() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "B", en)
        assertEquals(listOf("A"), engine.spoken)
        assertEquals("A", s.current.value?.text)
        engine.complete()
        assertEquals(listOf("A", "B"), engine.spoken)
        engine.complete()
        assertNull(s.current.value)
        assertTrue(s.idle.value)
    }

    @Test
    fun higherPriorityWaitingItem_goesNext_withoutInterruptingCurrent() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "welcome", en)
        s.say(g(GuidanceId.FLASH_PASSED_WITHOUT_STOP), "arrived", en)
        s.say(g(GuidanceId.FLASH_AHEAD), "school", en)
        assertEquals(0, engine.stops)
        engine.complete()
        engine.complete()
        assertEquals(listOf("welcome", "school", "arrived"), engine.spoken)
    }

    @Test
    fun itemsQueuedBeforeEngineReady_areSpokenAfterInit() = runTest {
        val engine = FakeEngine()
        val s = GuidanceSpeaker(engine, backgroundScope, Clock { testScheduler.currentTime })
        s.start()
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        assertTrue(engine.spoken.isEmpty())
        assertFalse(s.idle.value)
        engine.finishInit()
        assertEquals(listOf("A"), engine.spoken)
    }

    @Test
    fun unavailableLanguage_fallsBackToCaption_andIsRemembered() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "bonjour", fr)
        engine.registered!!.onError(TtsError.UNAVAILABLE_LANGUAGE)
        assertEquals(setOf(fr), s.unavailableLocales.value)
        assertEquals(GuidanceSpeaker.Caption(g(GuidanceId.NIGHT_START), "bonjour", voiced = false), s.current.value)

        s.say(g(GuidanceId.NIGHT_SUMMARY), "volant", fr)
        advanceTimeBy(GuidanceSpeaker.captionOnlyMs("bonjour") + 1)
        // 두 번째 프랑스어 문장은 엔진에 보내지 않고 자막으로만 보여 준다
        assertEquals(listOf("bonjour"), engine.spoken)
        assertEquals(GuidanceSpeaker.Caption(g(GuidanceId.NIGHT_SUMMARY), "volant", voiced = false), s.current.value)
        advanceTimeBy(GuidanceSpeaker.captionOnlyMs("volant") + 1)
        assertTrue(s.idle.value)
    }

    @Test
    fun engineInitFailure_usesCaptionsOnly() = runTest {
        val engine = FakeEngine(ready = false)
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(false, s.current.value?.voiced)
        advanceTimeBy(GuidanceSpeaker.captionOnlyMs("A") + 1)
        assertTrue(s.idle.value)
    }

    @Test
    fun otherError_showsCaptionThenContinues() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "B", en)
        engine.registered!!.onError(TtsError.OTHER)
        assertEquals(emptySet<Locale>(), s.unavailableLocales.value)
        advanceTimeBy(GuidanceSpeaker.captionOnlyMs("A") + 1)
        assertEquals(listOf("A", "B"), engine.spoken)
    }

    @Test
    fun missingDoneSignal_timesOutAndMovesOn() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "B", en)
        advanceTimeBy(GuidanceSpeaker.speechTimeoutMs("A") + 1)
        runCurrent()
        assertEquals(listOf("A", "B"), engine.spoken)
        assertEquals(1, engine.stops)
    }

    @Test
    fun lateDoneBeforeStart_isIgnored() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "B", en)
        engine.registered!!.onDone() // onStart 없이 온 완료 신호
        assertEquals(listOf("A"), engine.spoken)
        engine.complete()
        assertEquals(listOf("A", "B"), engine.spoken)
    }

    @Test
    fun stopAll_clearsQueueAndStopsVoice() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "B", en)
        s.stopAll()
        assertEquals(1, engine.stops)
        assertNull(s.current.value)
        assertTrue(s.idle.value)
        engine.complete()
        assertEquals(listOf("A"), engine.spoken)
    }

    @Test
    fun startupGuidanceQueuedBeforeInit_keepsGreetingFirst() = runTest {
        val engine = FakeEngine()
        val s = GuidanceSpeaker(engine, backgroundScope, Clock { testScheduler.currentTime })
        s.start()
        s.say(g(GuidanceId.NIGHT_START), "welcome", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "lhd", en)
        engine.finishInit()
        engine.complete()
        assertEquals(listOf("welcome", "lhd"), engine.spoken)
    }

    @Test
    fun stoppedSignal_movesToNextLikeDone() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "B", en)
        engine.registered!!.onStart()
        engine.registered!!.onStopped()
        assertEquals(listOf("A", "B"), engine.spoken)
    }

    @Test
    fun releaseDuringPlayback_doesNotCallStopBeforeRelease() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "A", en)
        s.say(g(GuidanceId.NIGHT_SUMMARY), "B", en)
        s.release()
        assertEquals(0, engine.stops)
        assertTrue(engine.released)
        assertNull(s.current.value)
        advanceTimeBy(60_000)
        assertEquals(listOf("A"), engine.spoken)
    }

    @Test
    fun release_releasesEngine() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.release()
        assertTrue(engine.released)
        assertNull(engine.registered)
    }
    // ---- 음성 질문을 듣는 동안(hold) ----

    @Test
    fun hold_stopsCurrentGuidance_andQueuesNewOnes_untilResume() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "welcome", en)
        assertTrue(s.hold {})
        assertEquals(1, engine.stops)
        assertNull(s.current.value)
        s.say(g(GuidanceId.RAIN_SPEED), "right", en)
        assertEquals("듣는 동안은 말하지 않음", listOf("welcome"), engine.spoken)
        s.resume()
        assertEquals(listOf("welcome", "right"), engine.spoken)
    }

    @Test
    fun hold_isRefused_whileASafetyWarningIsSpeaking() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.FLASH_AHEAD), "school", en)
        assertFalse(s.hold {})
        assertEquals(0, engine.stops)
        assertEquals("school", s.current.value?.text)
    }

    @Test
    fun hold_isRefused_whileASafetyWarningIsWaitingBehindOtherGuidance() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        s.say(g(GuidanceId.NIGHT_START), "welcome", en)
        s.say(g(GuidanceId.FLASH_AHEAD), "school", en)
        advanceTimeBy(6_000)
        // 일반 안내를 끊고 기다리는 경고까지 미루면, 듣는 동안 경고의 유효 시간(15초)이 지나 사라진다
        assertFalse(s.hold {})
        assertEquals(0, engine.stops)
        engine.complete()
        assertEquals(listOf("welcome", "school"), engine.spoken)
    }

    @Test
    fun hold_isRefused_whileASafetyWarningWaitsForTheEngineToStart() = runTest {
        val engine = FakeEngine()
        val clock = Clock { testScheduler.currentTime }
        val s = GuidanceSpeaker(engine, backgroundScope, clock).also { it.start() }
        s.say(g(GuidanceId.FLASH_AHEAD), "school", en)
        assertFalse(s.hold {})
        engine.finishInit()
        assertEquals(listOf("school"), engine.spoken)
    }

    @Test
    fun safetyWarningDuringHold_endsTheHold_andIsSpokenAtOnce() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        var interrupted = 0
        assertTrue(s.hold { interrupted++ })
        s.say(g(GuidanceId.RAIN_SPEED), "right", en)
        s.say(g(GuidanceId.FLASH_AHEAD), "school", en)
        assertEquals(1, interrupted)
        assertEquals("안전 경고가 먼저", listOf("school"), engine.spoken)
        engine.complete()
        assertEquals(listOf("school", "right"), engine.spoken)
        // 이미 풀렸으므로 다시 풀어도 아무 일 없음
        s.resume()
        assertEquals(listOf("school", "right"), engine.spoken)
    }

    @Test
    fun stopAll_clearsTheHold() = runTest {
        val engine = FakeEngine()
        val s = speaker(engine)
        assertTrue(s.hold {})
        s.stopAll()
        s.say(g(GuidanceId.FLASH_PASSED_WITHOUT_STOP), "arrived", en)
        assertEquals(listOf("arrived"), engine.spoken)
    }
}
