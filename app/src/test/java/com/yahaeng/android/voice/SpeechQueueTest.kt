package com.yahaeng.android.voice

import com.yahaeng.android.rules.FakeClock
import com.yahaeng.android.rules.Guidance
import com.yahaeng.android.rules.GuidanceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class SpeechQueueTest {

    private val clock = FakeClock()
    private val queue = SpeechQueue(clock)

    private fun add(id: GuidanceId, vararg args: Int) = queue.enqueue(Guidance(id, args.toList()), id.name, Locale.US)

    @Test
    fun higherUrgencyFirst_thenFifoWithinTier() {
        add(GuidanceId.NIGHT_START)
        add(GuidanceId.RAIN_SPEED)
        add(GuidanceId.NIGHT_SUMMARY)
        add(GuidanceId.FLASH_TURN_LEFT, 300)
        add(GuidanceId.FLASH_AHEAD)
        val order = generateSequence { queue.poll()?.guidance?.id }.toList()
        assertEquals(
            listOf(
                GuidanceId.FLASH_TURN_LEFT,
                GuidanceId.FLASH_AHEAD,
                GuidanceId.RAIN_SPEED,
                // 시작 인사와 적응 안내는 같은 단계라 들어온 순서대로
                GuidanceId.NIGHT_START,
                GuidanceId.NIGHT_SUMMARY,
            ),
            order,
        )
    }

    @Test
    fun greetingThenLhdIntro_keepOrder() {
        add(GuidanceId.NIGHT_START)
        add(GuidanceId.NIGHT_SUMMARY)
        assertEquals(GuidanceId.NIGHT_START, queue.poll()?.guidance?.id)
        assertEquals(GuidanceId.NIGHT_SUMMARY, queue.poll()?.guidance?.id)
    }

    @Test
    fun expiredItem_doesNotBlockFreshSameGuidance() {
        add(GuidanceId.FLASH_TURN_LEFT, 300)
        clock.advanceSeconds(16)
        assertEquals(true, add(GuidanceId.FLASH_TURN_LEFT, 300))
        assertEquals(GuidanceId.FLASH_TURN_LEFT, queue.poll()?.guidance?.id)
        assertNull(queue.poll())
    }

    @Test
    fun expiredItems_areDropped_byPriority() {
        add(GuidanceId.NIGHT_START)
        add(GuidanceId.RAIN_SPEED)
        add(GuidanceId.FLASH_TURN_LEFT, 300)
        clock.advanceSeconds(16) // 점멸 안내(15초)는 만료
        assertEquals(GuidanceId.RAIN_SPEED, queue.poll()?.guidance?.id)
        add(GuidanceId.RAIN_SPEED)
        clock.advanceSeconds(21) // 비 오는 밤 안내(20초) 만료
        assertEquals(GuidanceId.NIGHT_START, queue.poll()?.guidance?.id)
        add(GuidanceId.NIGHT_SUMMARY)
        clock.advanceSeconds(61) // 그 밖의 안내(60초)도 만료
        assertNull(queue.poll())
    }

    @Test
    fun ruleExpiresAfter20s_safetyAfter15s() {
        add(GuidanceId.RAIN_SPEED)
        clock.advanceSeconds(20)
        assertEquals(GuidanceId.RAIN_SPEED, queue.poll()?.guidance?.id)
        add(GuidanceId.RAIN_SPEED)
        clock.advanceSeconds(21)
        assertNull(queue.poll())
        add(GuidanceId.FLASH_TURN_LEFT, 100)
        clock.advanceSeconds(15)
        assertEquals(GuidanceId.FLASH_TURN_LEFT, queue.poll()?.guidance?.id)
    }

    @Test
    fun sameGuidanceWaiting_isNotAddedTwice_butDifferentArgsAre() {
        add(GuidanceId.FLASH_TURN_LEFT, 300)
        assertFalse(add(GuidanceId.FLASH_TURN_LEFT, 300))
        add(GuidanceId.FLASH_TURN_LEFT, 200)
        assertEquals(2, queue.size)
    }
}
