package com.example.remind.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.remind.RemindApp
import com.example.remind.data.ReviewResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = (context.applicationContext as RemindApp).repository
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    // A rating tapped on a notification works even with reminders switched off.
                    DigestScheduler.ACTION_RATE -> {
                        val noteId = intent.getLongExtra(DigestScheduler.EXTRA_NOTE_ID, -1)
                        val result = intent.getStringExtra(DigestScheduler.EXTRA_RESULT)
                            ?.let { name -> ReviewResult.entries.firstOrNull { it.name == name } }
                        if (noteId >= 0 && result != null) repo.reviewFromNotification(noteId, result)
                        Notifications.cancelNote(context, noteId)
                    }
                    DigestScheduler.ACTION_DIGEST -> {
                        if (!repo.remindersEnabled.value) return@launch
                        Notifications.showDue(context, repo)
                        val id = intent.getLongExtra(DigestScheduler.EXTRA_DIGEST_ID, -1)
                        repo.digest(id)?.let { repo.applyDigest(it) }
                    }
                    DigestScheduler.ACTION_RANDOM -> {
                        if (!repo.remindersEnabled.value) return@launch
                        Notifications.showDue(context, repo)
                        repo.rescheduleDigests()
                    }
                    DigestScheduler.ACTION_SNOOZE -> {
                        Notifications.cancelAll(context)
                        DigestScheduler.scheduleSnooze(context)
                    }
                    DigestScheduler.ACTION_SNOOZE_FIRE -> {
                        if (!repo.remindersEnabled.value) return@launch
                        Notifications.showDue(context, repo)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Alarms are wiped on reboot and become stale after a clock/timezone change. */
class BootReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in actions) return
        val repo = (context.applicationContext as RemindApp).repository
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                repo.rescheduleDigests()
            } finally {
                pending.finish()
            }
        }
    }
}
