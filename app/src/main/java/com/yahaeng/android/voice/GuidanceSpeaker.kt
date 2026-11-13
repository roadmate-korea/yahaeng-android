package com.yahaeng.android.voice

import com.yahaeng.android.rules.Clock
import com.yahaeng.android.rules.Guidance
import com.yahaeng.android.rules.GuidanceId
import com.yahaeng.android.rules.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 안내를 한 번에 하나씩 말하고, 지금 말하는 문장을 자막으로 내보낸다.
 *
 * - 음성이 되면 TTS로 말하고, 끝나면 다음 안내로 넘어간다.
 * - 엔진 초기화 실패, 지원하지 않는 언어, 그 밖의 오류가 나면 그 안내는 자막만 일정 시간 보여 준다.
 *   지원하지 않는 언어는 기억해 두고 이후 그 언어는 바로 자막으로 보낸다.
 * - 엔진이 끝 신호를 주지 않아도 멈추지 않도록 감시 시간이 지나면 다음으로 넘어간다.
 * - 음성 질문을 듣는 동안에는 말하지 않는다([hold]). 그 사이 안전 경고가 오면 듣기를 끝내고 경고를 말한다.
 *
 * 메인 스레드(또는 [scope]의 단일 스레드)에서만 부른다.
 */
class GuidanceSpeaker(
    private val engine: TtsEngine,
    private val scope: CoroutineScope,
    clock: Clock = Clock.SYSTEM,
) : TtsEngine.Listener {

    data class Caption(val guidance: Guidance, val text: String, val voiced: Boolean)

    private val queue = SpeechQueue(clock)

    private val _current = MutableStateFlow<Caption?>(null)

    /** 지금 말하거나 보여 주는 안내. 없으면 null. */
    val current: StateFlow<Caption?> = _current.asStateFlow()

    private val _unavailableLocales = MutableStateFlow<Set<Locale>>(emptySet())

    /** 음성을 쓸 수 없어 자막만 보여 주는 언어. */
    val unavailableLocales: StateFlow<Set<Locale>> = _unavailableLocales.asStateFlow()

    private val _engineFailed = MutableStateFlow(false)

    /** TTS 엔진을 쓸 수 없음(초기화 실패). 모든 안내를 자막으로만 보여 준다. */
    val engineFailed: StateFlow<Boolean> = _engineFailed.asStateFlow()

    private val _idle = MutableStateFlow(true)

    /** 말하는 것도, 기다리는 것도 없음. */
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    private var engineState = EngineState.INITIALIZING
    private var playing: SpeechQueue.Item? = null
    private var playingStarted = false
    private var timer: Job? = null

    /**
     * 재생 직전에 안내를 다시 확인한다. 바꿀 안내와 문장을 돌려주거나, 더는 맞지 않으면 null(버리고 다음 것으로).
     * 상황에 따라 낡는 경고(어린이보호구역 앞 과속)를 기다리는 동안 새 신호가 없어도 맞는지 보려는 것이다. null이면 확인하지 않는다.
     */
    var revalidate: ((Guidance, String) -> Pair<Guidance, String>?)? = null

    /** 안내를 실제로 내보내기 시작할 때(음성 또는 자막). 엔진이 "말했다"고 기록하는 데 쓴다. */
    var onPlay: ((Guidance) -> Unit)? = null

    /** 기다리는(아직 말하지 않은) [id] 안내가 있는지. */
    fun isQueued(id: GuidanceId): Boolean = queue.contains(id)

    /** 음성 질문을 듣는 중. null이 아니면 말하지 않고 쌓아 둔다. 안전 경고가 오면 이것을 불러 듣기를 끝낸다. */
    private var holdInterrupt: (() -> Unit)? = null

    fun start() {
        engine.setListener(this)
        engine.initialize { ready ->
            engineState = if (ready) EngineState.READY else EngineState.FAILED
            _engineFailed.value = !ready
            if (playing == null) playNext()
        }
    }

    fun say(guidance: Guidance, text: String, locale: Locale) {
        if (!queue.enqueue(guidance, text, locale)) return
        _idle.value = false
        // 듣는 중에 안전 경고가 오면 질문보다 경고가 먼저다
        if (guidance.priority == Priority.SAFETY) holdInterrupt?.let { interrupt -> resume(); interrupt() }
        // 엔진 준비 전에는 쌓아 두었다가 준비가 끝나면 말한다.
        if (playing == null && engineState != EngineState.INITIALIZING) playNext()
    }

    /** 기다리는 같은 종류의 안내를 지금 값으로 바꾼다. 지금 말하고 있는 것은 그대로 둔다. */
    fun refreshQueued(guidance: Guidance, text: String) = queue.replace(guidance, text)

    /** 기다리는 [id] 안내를 버린다. 지금 말하고 있는 것은 그대로 둔다. */
    fun drop(id: GuidanceId) {
        queue.remove(id)
        if (playing == null && queue.size == 0) _idle.value = true
    }

    /**
     * 음성 질문을 듣는 동안 말하기를 멈춘다. 지금 말하던 안내(안전 경고가 아닌 것)는 그만두고, 새 안내는 쌓아 둔다.
     * 안전 경고를 말하는 중이거나 기다리는 중이면 멈추지 않고 false를 돌려준다(경고를 끊거나 미뤄 질문을 받지 않는다.
     * 기다리는 경고를 미루면 듣는 동안 유효 시간이 지나 사라질 수 있다).
     * 듣는 중에 안전 경고가 들어오면 [onSafetyInterrupt]를 부르고 바로 경고를 말한다.
     */
    fun hold(onSafetyInterrupt: () -> Unit): Boolean {
        if (playing?.guidance?.priority == Priority.SAFETY || queue.hasPending(Priority.SAFETY)) return false
        holdInterrupt = onSafetyInterrupt
        timer?.cancel()
        if (playing != null && _current.value?.voiced == true) engine.stop()
        playing = null
        _current.value = null
        return true
    }

    /** [hold]를 풀고 쌓인 안내를 이어서 말한다. */
    fun resume() {
        if (holdInterrupt == null) return
        holdInterrupt = null
        if (playing == null && engineState != EngineState.INITIALIZING) playNext()
    }

    /** [ids] 안내만 멈추고 버린다(기다리는 것과 지금 말하는 것). 다른 안내는 그대로 이어서 말한다. */
    fun cancel(ids: Set<GuidanceId>) {
        ids.forEach(queue::remove)
        if (playing?.guidance?.id in ids) stopCurrentAndPlayNext() else if (playing == null && queue.size == 0) _idle.value = true
    }

    private fun stopCurrentAndPlayNext() {
        timer?.cancel()
        if (playing != null && _current.value?.voiced == true) engine.stop()
        playing = null
        if (engineState != EngineState.INITIALIZING) playNext()
    }

    /** 기다리는 안내를 모두 버리고 지금 말하는 것도 멈춘다. */
    fun stopAll() {
        holdInterrupt = null
        queue.clear()
        timer?.cancel()
        if (playing?.let { _current.value?.voiced } == true) engine.stop()
        playing = null
        _current.value = null
        _idle.value = true
    }

    /** 해제할 때는 엔진 stop을 부르지 않는다([TtsEngine.release] 참고). 재생 중이던 소리는 엔진 해제로 끝난다. */
    fun release() {
        holdInterrupt = null
        queue.clear()
        timer?.cancel()
        playing = null
        _current.value = null
        _idle.value = true
        engine.setListener(null)
        engine.release()
    }

    // ---- TtsEngine.Listener ----

    override fun onStart() {
        if (playing != null) playingStarted = true
    }

    override fun onDone() {
        // 시작 신호를 받기 전의 완료 신호는 앞 문장의 늦은 신호로 보고 무시한다.
        if (playing != null && playingStarted) finishCurrent()
    }

    override fun onError(error: TtsError) {
        val item = playing ?: return
        if (error == TtsError.UNAVAILABLE_LANGUAGE) {
            _unavailableLocales.value = _unavailableLocales.value + item.locale
        }
        showCaptionOnly(item)
    }

    // ---- 내부 ----

    /** 다음 안내를 꺼내되 [revalidate]가 버린 것은 건너뛰고, 바꾼 것은 바꾼 내용으로. */
    private fun nextValid(): SpeechQueue.Item? {
        while (true) {
            val item = queue.poll() ?: return null
            val check = revalidate ?: return item
            val (guidance, text) = check(item.guidance, item.text) ?: continue
            return item.copy(guidance = guidance, text = text)
        }
    }

    private fun playNext() {
        timer?.cancel()
        if (holdInterrupt != null) {
            playing = null
            _current.value = null
            _idle.value = queue.size == 0
            return
        }
        val item = nextValid()
        playing = item
        playingStarted = false
        if (item == null) {
            _current.value = null
            _idle.value = true
            return
        }
        _idle.value = false
        onPlay?.invoke(item.guidance)
        val canSpeak = engineState == EngineState.READY && item.locale !in _unavailableLocales.value
        if (canSpeak) {
            _current.value = Caption(item.guidance, item.text, voiced = true)
            engine.speak(item.text, item.locale)
            timer = scope.launch {
                delay(speechTimeoutMs(item.text))
                engine.stop()
                finishCurrent()
            }
        } else {
            showCaptionOnly(item)
        }
    }

    private fun showCaptionOnly(item: SpeechQueue.Item) {
        timer?.cancel()
        _current.value = Caption(item.guidance, item.text, voiced = false)
        timer = scope.launch {
            delay(captionOnlyMs(item.text))
            finishCurrent()
        }
    }

    private fun finishCurrent() {
        timer?.cancel()
        playing = null
        playNext()
    }

    private enum class EngineState { INITIALIZING, READY, FAILED }

    companion object {
        /** 자막만 보여 줄 때 읽을 시간. */
        fun captionOnlyMs(text: String): Long = (text.length * 70L).coerceIn(3_000L, 12_000L)

        /** 엔진이 끝 신호를 주지 않을 때 넘어가기까지 기다리는 시간. */
        fun speechTimeoutMs(text: String): Long = (text.length * 150L + 3_000L).coerceIn(6_000L, 40_000L)
    }
}
