package com.example.remind.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.remind.RemindApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DigestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val repo = (context.applicationContext as RemindApp).repository
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!repo.remindersEnabled.value) return@launch
                when (intent.action) {
                    DigestScheduler.ACTION_DIGEST -> {
                        Notifications.showDigest(context, repo.dueTitles())
                        val id = intent.getLongExtra(DigestScheduler.EXTRA_DIGEST_ID, -1)
                        repo.digest(id)?.let { repo.applyDigest(it) }
                    }
                    DigestScheduler.ACTION_SNOOZE -> {
                        Notifications.cancelDigest(context)
                        DigestScheduler.scheduleSnooze(context)
                    }
                    DigestScheduler.ACTION_SNOOZE_FIRE -> Notifications.showDigest(context, repo.dueTitles())
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
