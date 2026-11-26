package com.example.remind.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.example.remind.data.Digest
import java.time.ZonedDateTime

/**
 * One inexact, doze-friendly alarm per digest time. When it fires, DigestReceiver
 * posts the notification and schedules the same digest for the next day.
 */
object DigestScheduler {

    const val ACTION_DIGEST = "com.example.remind.DIGEST"
    const val ACTION_SNOOZE = "com.example.remind.SNOOZE"
    const val ACTION_SNOOZE_FIRE = "com.example.remind.SNOOZE_FIRE"
    const val EXTRA_DIGEST_ID = "digest_id"

    private const val SNOOZE_REQUEST_CODE = 1_000_000
    const val SNOOZE_MINUTES = 60L

    fun schedule(context: Context, digest: Digest) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pending = digestIntent(context, digest.id)
        alarms.cancel(pending)
        if (!digest.enabled) return
        val trigger = nextTrigger(ZonedDateTime.now(), digest.hour, digest.minute)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.toInstant().toEpochMilli(), pending)
    }

    fun cancel(context: Context, digest: Digest) {
        context.getSystemService(AlarmManager::class.java).cancel(digestIntent(context, digest.id))
    }

    fun scheduleSnooze(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val intent = Intent(context, DigestReceiver::class.java).setAction(ACTION_SNOOZE_FIRE)
        val pending = PendingIntent.getBroadcast(
            context, SNOOZE_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val trigger = System.currentTimeMillis() + SNOOZE_MINUTES * 60_000
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending)
    }

    /** Next moment at hour:minute strictly after [now]. */
    fun nextTrigger(now: ZonedDateTime, hour: Int, minute: Int): ZonedDateTime {
        val candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
        return if (candidate.isAfter(now)) candidate else candidate.plusDays(1)
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
}
