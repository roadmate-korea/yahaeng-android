package com.yahaeng.android.voice

import com.yahaeng.android.rules.Clock
import com.yahaeng.android.rules.Guidance
import com.yahaeng.android.rules.GuidanceId
import com.yahaeng.android.rules.Priority
import java.util.Locale

/**
 * 말할 안내 대기열(순수 Kotlin).
 *
 * - 위험도 단계가 높은 것 먼저: 점멸 교차로 > 비 오는 밤 속도 > 그 밖(해 짐, 기록).
 *   같은 단계 안에서는 들어온 순서를 지킨다. 점멸 안내 뒤의 방향지시등 문장 순서가 바뀌지 않게 하려는 것이다.
 * - 너무 오래 기다린 안내는 버린다. 지나간 교차로를 늦게 말하면 오히려 헷갈린다.
 * - 같은 안내(ID와 인자가 같음)가 이미 기다리고 있으면 다시 넣지 않는다.
 */
class SpeechQueue(private val clock: Clock) {

    data class Item(
        val guidance: Guidance,
        val text: String,
        val locale: Locale,
        val enqueuedAtMs: Long,
        private val seq: Long,
    ) {
        internal val order get() = seq
    }

    private val items = mutableListOf<Item>()
    private var seq = 0L

    val size: Int get() = items.size

    fun enqueue(guidance: Guidance, text: String, locale: Locale): Boolean {
        // 만료된 항목이 같은 안내의 새 요청을 막지 않도록 먼저 지운다
        dropExpired()
        if (items.any { it.guidance == guidance }) return false
        items += Item(guidance, text, locale, clock.nowMillis(), seq++)
        return true
    }

    /** 다음에 말할 안내를 꺼낸다. 유효 시간이 지난 것은 이때 버린다. */
    fun poll(): Item? {
        dropExpired()
        val next = items.minWithOrNull(compareByDescending<Item> { urgency(it.guidance.priority) }.thenBy { it.order }) ?: return null
        items.remove(next)
        return next
    }

    fun clear() = items.clear()

    /** 아직 유효한(만료되지 않은) [priority] 안내가 기다리고 있는지. */
    fun hasPending(priority: Priority): Boolean {
        dropExpired()
        return items.any { it.guidance.priority == priority }
    }

    /**
     * 아직 말하지 않은 [guidance]와 같은 종류의 안내가 있으면 내용을 바꾼다(유효 시간은 처음 넣은 때부터 그대로).
     * 없으면 아무 일도 하지 않는다.
     */
    fun replace(guidance: Guidance, text: String) {
        val i = items.indexOfFirst { it.guidance.id == guidance.id }
        if (i >= 0) items[i] = items[i].copy(guidance = guidance, text = text)
    }

    /** 아직 말하지 않은(만료되지 않은) [id] 안내가 있는지. */
    fun contains(id: GuidanceId): Boolean {
        dropExpired()
        return items.any { it.guidance.id == id }
    }

    /** 아직 말하지 않은 [id] 안내를 버린다(상황이 풀려 더는 맞지 않을 때). */
    fun remove(id: GuidanceId) {
        items.removeAll { it.guidance.id == id }
    }

    private fun dropExpired() {
        val now = clock.nowMillis()
        items.removeAll { item -> ttlMs(item.guidance.priority)?.let { now - item.enqueuedAtMs > it } ?: false }
    }

    companion object {
        /** 큐 순서용 위험도 단계. 적응 안내와 시작·도착 안내는 같은 단계라 들어온 순서대로 말한다. */
        fun urgency(priority: Priority): Int = when (priority) {
            Priority.SAFETY -> 2
            Priority.RULE -> 1
            Priority.INFO -> 0
        }

        /** null이면 버리지 않는다. */
        fun ttlMs(priority: Priority): Long? = when (priority) {
            Priority.SAFETY -> 15_000
            Priority.RULE -> 20_000
            Priority.INFO -> 60_000
        }
    }
}
