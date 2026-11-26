package com.example.remind.notify

import android.Manifest
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

object Notifications {

    private const val CHANNEL_ID = "digest"
    const val DIGEST_NOTIFICATION_ID = 1

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "Revision reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Daily digest of notes due for revision"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Posts the digest. With [force] it also posts when nothing is due (used by the test button). */
    fun showDigest(context: Context, dueTitles: List<String>, force: Boolean = false) {
        if (dueTitles.isEmpty() && !force) return
        if (!canPost(context)) return

        val count = dueTitles.size
        val title = if (count == 0) "Aaj kuch due nahi hai" else "Revision time: $count due"
        val text = when (count) {
            0 -> "Sab revise ho chuka. Badhiya!"
            1 -> dueTitles.first()
            else -> "$count cheeze revise karni hai"
        }

        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        dueTitles.take(5).forEach { style.addLine(it) }
        if (count > 5) style.setSummaryText("+${count - 5} more")

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setNumber(count)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, review = count > 0))

        if (count > 0) {
            builder.addAction(0, "Revise", openIntent(context, review = true))
            builder.addAction(0, "Snooze 1h", snoozeIntent(context))
        }

        @Suppress("MissingPermission") // checked in canPost
        NotificationManagerCompat.from(context).notify(DIGEST_NOTIFICATION_ID, builder.build())
    }

    fun cancelDigest(context: Context) {
        NotificationManagerCompat.from(context).cancel(DIGEST_NOTIFICATION_ID)
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

    private fun snoozeIntent(context: Context): PendingIntent {
        val intent = Intent(context, DigestReceiver::class.java).setAction(DigestScheduler.ACTION_SNOOZE)
        return PendingIntent.getBroadcast(
            context, 2, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
