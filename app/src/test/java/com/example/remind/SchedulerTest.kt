package com.example.remind

import com.example.remind.data.ReviewResult
import com.example.remind.data.Scheduler
import com.example.remind.notify.DigestScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulerTest {
    private val intervals = listOf(1, 2, 4, 7)
    private val today = 20_000L

    @Test fun firstDueIsTodayPlusFirstInterval() {
        assertEquals(today + 1, Scheduler.firstDueDay(intervals, today))
    }

    @Test fun goodAdvancesStage() {
        val o = Scheduler.review(0, intervals, ReviewResult.GOOD, today)
        assertEquals(1, o.stage)
        assertEquals(today + 2, o.nextDueDay)
        assertFalse(o.mastered)
    }

    @Test fun goodOnLastStageMasters() {
        val o = Scheduler.review(3, intervals, ReviewResult.GOOD, today)
        assertTrue(o.mastered)
        assertEquals(4, o.stage)
    }

    @Test fun hardRepeatsSameInterval() {
        val o = Scheduler.review(2, intervals, ReviewResult.HARD, today)
        assertEquals(2, o.stage)
        assertEquals(today + 4, o.nextDueDay)
    }

    @Test fun forgotResetsToStart() {
        val o = Scheduler.review(3, intervals, ReviewResult.FORGOT, today)
        assertEquals(0, o.stage)
        assertEquals(today + 1, o.nextDueDay)
    }

    @Test fun stageBeyondListIsClamped() {
        val o = Scheduler.review(10, intervals, ReviewResult.HARD, today)
        assertEquals(3, o.stage)
        assertEquals(today + 7, o.nextDueDay)
    }

    @Test fun parseIntervals() {
        assertEquals(listOf(1, 2, 4), Scheduler.parseIntervals(" 1, 2 ,4 "))
        assertEquals(listOf(1, 3), Scheduler.parseIntervals("1 3"))
        assertNull(Scheduler.parseIntervals(""))
        assertNull(Scheduler.parseIntervals("1, 0"))
        assertNull(Scheduler.parseIntervals("1, x"))
        assertNull(Scheduler.parseIntervals("-2"))
    }

    @Test fun streakCountsConsecutiveDays() {
        assertEquals(3, Scheduler.streak(listOf(today, today - 1, today - 2, today - 5), today))
        assertEquals(2, Scheduler.streak(listOf(today - 1, today - 2), today))
        assertEquals(0, Scheduler.streak(listOf(today - 2), today))
        assertEquals(0, Scheduler.streak(emptyList(), today))
    }

    @Test fun digestTriggerLaterTodayOrTomorrow() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = ZonedDateTime.of(2026, 11, 26, 18, 30, 0, 0, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 26, 20, 0, 0, 0, zone), DigestScheduler.nextTrigger(now, 20, 0))
        assertEquals(ZonedDateTime.of(2026, 11, 27, 8, 0, 0, 0, zone), DigestScheduler.nextTrigger(now, 8, 0))
        assertEquals(ZonedDateTime.of(2026, 11, 27, 18, 30, 0, 0, zone), DigestScheduler.nextTrigger(now, 18, 30))
    }
}
