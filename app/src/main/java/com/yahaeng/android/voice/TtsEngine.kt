package com.yahaeng.android.voice

import java.util.Locale

/**
 * 음성 합성 엔진. Pleos TTS를 감싸고, 테스트에서는 가짜 엔진으로 바꾼다.
 * 콜백은 항상 메인 스레드에서 부른다.
 */
interface TtsEngine {

    /** 준비가 끝나면 true, 엔진을 쓸 수 없으면 false로 한 번 부른다. */
    fun initialize(onResult: (ready: Boolean) -> Unit)

    fun setListener(listener: Listener?)

    /** 지금 말하는 문장이 있으면 끊고 [text]를 말한다. 순서 관리는 호출하는 쪽(큐)이 한다. */
    fun speak(text: String, locale: Locale)

    fun stop()

    /**
     * 엔진을 해제한다. 해제 뒤의 호출은 모두 무시한다.
     * SDK의 stop은 비동기라 해제 직전에 부르면 해제 뒤 서비스에 다시 붙을 수 있으므로, 해제 전에 stop을 부르지 않는다.
     */
    fun release()

    interface Listener {
        fun onStart()

        /** 끝까지 말함. */
        fun onDone()

        /** [stop] 등으로 중간에 멈춤. 기본은 [onDone]과 같이 다음으로 넘어간다. */
        fun onStopped() = onDone()

        fun onError(error: TtsError)
    }
}

enum class TtsError {
    /** 이 언어를 지원하지 않는다. 이 언어는 음성을 끄고 화면 자막만 쓴다. */
    UNAVAILABLE_LANGUAGE,

    OTHER,
}
