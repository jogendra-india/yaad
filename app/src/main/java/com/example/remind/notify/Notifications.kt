package com.example.remind.notify

import com.example.remind.ui.RichNotes

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.remind.MainActivity
import com.example.remind.R
import com.example.remind.data.Note
import com.example.remind.data.Repository
import com.example.remind.data.ReviewResult
import com.example.remind.tr

object Notifications {

    private const val CHANNEL_ID = "digest"
    private const val GROUP = "yaad_due"

    /** The group summary ("Revision time: N due"). */
    const val DIGEST_NOTIFICATION_ID = 1

    /** One notification per due note, at most this many at a time. */
    const val MAX_NOTE_NOTIFICATIONS = 10
    private const val NOTE_ID_BASE = 10_000
    private const val RATE_REQUEST_BASE = 2_000_000

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Revision reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Daily digest of notes due for revision"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Posts one notification per due note (up to [MAX_NOTE_NOTIFICATIONS]), each with
     * rating buttons, grouped under a summary. With [force] it also posts the summary
     * when nothing is due (used by the test button).
     */
    suspend fun showDue(context: Context, repo: Repository, force: Boolean = false) {
        if (!canPost(context)) return
        val due = repo.dueNotes()
        if (due.isEmpty() && !force) return

        cancelAll(context)
        val manager = NotificationManagerCompat.from(context)
        due.take(MAX_NOTE_NOTIFICATIONS).forEach { note ->
            val detail = noteDetail(note)
            @Suppress("MissingPermission") // checked in canPost
            manager.notify(noteNotificationId(note.id), noteNotification(context, note, detail))
            repo.logNotification(note, detail)
        }
        @Suppress("MissingPermission")
        manager.notify(DIGEST_NOTIFICATION_ID, summaryNotification(context, due))
        repo.pruneNotificationLog()
    }

    /** Removes one note's notification, and the summary once no note is left under it. */
    fun cancelNote(context: Context, noteId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val id = noteNotificationId(noteId)
        manager.cancel(id)
        val remaining = manager.activeNotifications.count { it.id != DIGEST_NOTIFICATION_ID && it.id != id }
        if (remaining == 0) manager.cancel(DIGEST_NOTIFICATION_ID)
    }

    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun noteNotificationId(noteId: Long) = NOTE_ID_BASE + noteId.toInt()

    private fun noteDetail(note: Note): String = buildString {
        if (note.keywords.isNotEmpty()) append(note.keywords.joinToString(" · "))
        val body = RichNotes.plain(note.body).trim()
        if (body.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(body.take(300))
        }
    }.ifEmpty { tr("Tap karke revise karo", "Tap to revise") }

    private fun noteNotification(context: Context, note: Note, detail: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(note.title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setSubText(note.subject.ifBlank { null })
            .setGroup(GROUP)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, review = true))
            .addAction(0, tr("Yaad tha", "Remembered"), rateIntent(context, note.id, ReviewResult.GOOD))
            .addAction(0, tr("Thoda", "Partly"), rateIntent(context, note.id, ReviewResult.HARD))
            .addAction(0, tr("Bhool gaya", "Forgot"), rateIntent(context, note.id, ReviewResult.FORGOT))
            .build()

    private fun summaryNotification(context: Context, due: List<Note>): Notification {
        val count = due.size
        val title = if (count == 0) tr("Aaj kuch due nahi hai", "Nothing due today") else "Revision time: $count due"
        val text = when (count) {
            0 -> tr("Sab revise ho chuka. Badhiya!", "All revised. Great job!")
            1 -> due.first().title
            else -> tr("$count cheeze revise karni hai", "$count things to revise")
        }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        due.take(5).forEach { style.addLine(it.title) }
        if (count > MAX_NOTE_NOTIFICATIONS) {
            style.setSummaryText(tr("+${count - MAX_NOTE_NOTIFICATIONS} aur app me", "+${count - MAX_NOTE_NOTIFICATIONS} more in the app"))
        } else if (count > 5) {
            style.setSummaryText("+${count - 5} more")
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setNumber(count)
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, review = count > 0))

        if (count > 0) {
            builder.addAction(0, "Revise", openIntent(context, review = true))
            builder.addAction(0, "Snooze 1h", snoozeIntent(context))
        }
        return builder.build()
    }

    private fun openIntent(context: Context, review: Boolean): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (review) action = MainActivity.ACTION_START_REVIEW
        }
        return PendingIntent.getActivity(
            context, if (review) 1 else 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun rateIntent(context: Context, noteId: Long, result: ReviewResult): PendingIntent {
        val intent = Intent(context, DigestReceiver::class.java)
            .setAction(DigestScheduler.ACTION_RATE)
            .putExtra(DigestScheduler.EXTRA_NOTE_ID, noteId)
            .putExtra(DigestScheduler.EXTRA_RESULT, result.name)
        return PendingIntent.getBroadcast(
            context, RATE_REQUEST_BASE + noteId.toInt() * 3 + result.ordinal, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun snoozeIntent(context: Context): PendingIntent {
        val intent = Intent(context, DigestReceiver::class.java).setAction(DigestScheduler.ACTION_SNOOZE)
        return PendingIntent.getBroadcast(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
