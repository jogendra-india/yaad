package com.example.remind.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.remind.data.Digest
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Exact, doze-proof alarms. Two modes:
 * - fixed: one alarm per digest time; when it fires, DigestReceiver posts the
 *   notifications and schedules the same digest for the next day.
 * - random: a single alarm at the next random time inside the daily window;
 *   when it fires, the one after it is scheduled.
 */
object DigestScheduler {

    const val ACTION_DIGEST = "com.example.remind.DIGEST"
    const val ACTION_RANDOM = "com.example.remind.RANDOM"
    const val ACTION_SNOOZE = "com.example.remind.SNOOZE"
    const val ACTION_SNOOZE_FIRE = "com.example.remind.SNOOZE_FIRE"
    const val ACTION_RATE = "com.example.remind.RATE"
    const val EXTRA_DIGEST_ID = "digest_id"
    const val EXTRA_NOTE_ID = "note_id"
    const val EXTRA_RESULT = "result"

    private const val SNOOZE_REQUEST_CODE = 1_000_000
    private const val RANDOM_REQUEST_CODE = 1_000_001
    const val SNOOZE_MINUTES = 60L

    /** Random-mode settings. Minutes are minutes of the day (0..1439). */
    data class RandomPlan(val startMinute: Int, val endMinute: Int, val count: Int, val seed: Long)

    fun schedule(context: Context, digest: Digest) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = digestIntent(context, digest.id)
        alarms.cancel(pending)
        if (!digest.enabled) return
        val trigger = nextTrigger(ZonedDateTime.now(), digest.hour, digest.minute)
        alarms.setExactCompat(trigger.toInstant().toEpochMilli(), pending)
    }

    fun cancel(context: Context, digest: Digest) {
        context.getSystemService(AlarmManager::class.java).cancel(digestIntent(context, digest.id))
    }

    fun scheduleRandom(context: Context, plan: RandomPlan) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = randomIntent(context)
        alarms.cancel(pending)
        // A minute of slack so a slot that just fired is never picked again.
        val trigger = nextRandomTrigger(ZonedDateTime.now().plusMinutes(1), plan)
        alarms.setExactCompat(trigger.toInstant().toEpochMilli(), pending)
    }

    fun cancelRandom(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(randomIntent(context))
    }

    fun scheduleSnooze(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, DigestReceiver::class.java).setAction(ACTION_SNOOZE_FIRE)
        val pending = PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.setExactCompat(System.currentTimeMillis() + SNOOZE_MINUTES * 60_000, pending)
    }

    /** Next moment at hour:minute strictly after [now]. */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
    }

    /**
     * The day's random times, in minutes of the day. The window is split into
     * [RandomPlan.count] equal slots and one minute is picked inside each, so the
     * reminders stay spread out. Seeded by the date, so a reboot or a reschedule
     * reproduces the same plan for that day.
     */
    fun randomTimes(day: LocalDate, plan: RandomPlan): List<Int> {
        val start = plan.startMinute.coerceIn(0, 1438)
        val end = plan.endMinute.coerceIn(start + 1, 1439)
        val n = plan.count.coerceIn(1, 20)
        val span = (end - start).toDouble() / n
        val rnd = Random(plan.seed xor (day.toEpochDay() * 1_000_003L))
        return (0 until n).map { i ->
            val lo = start + (i * span).toInt()
            val hi = maxOf(start + ((i + 1) * span).toInt(), lo + 1)
            rnd.nextInt(lo, hi)
        }
    }

    fun nextRandomTrigger(now: ZonedDateTime, plan: RandomPlan): ZonedDateTime {
        var day = now.toLocalDate()
        repeat(3) {
            for (minute in randomTimes(day, plan)) {
                val time = day.atStartOfDay(now.zone).plusMinutes(minute.toLong())
                if (time.isAfter(now)) return time
            }
            day = day.plusDays(1)
        }
        return day.atStartOfDay(now.zone).plusMinutes(plan.startMinute.toLong())
    }

    /**
     * Exact alarm when allowed (USE_EXACT_ALARM is granted on install for a
     * sideloaded app), otherwise the inexact fallback so nothing ever throws.
     */
    private fun AlarmManager.setExactCompat(triggerAt: Long, pending: PendingIntent) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || canScheduleExactAlarms()) {
            setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun digestIntent(context: Context, id: Long): PendingIntent {
        val intent = Intent(context, DigestReceiver::class.java)
            .setAction(ACTION_DIGEST)
            .putExtra(EXTRA_DIGEST_ID, id)
        return PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun randomIntent(context: Context): PendingIntent {
        val intent = Intent(context, DigestReceiver::class.java).setAction(ACTION_RANDOM)
        return PendingIntent.getBroadcast(
            context, RANDOM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
