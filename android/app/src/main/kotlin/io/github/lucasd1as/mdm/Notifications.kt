package io.github.lucasd1as.mdm

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    private const val CHANNEL_PROGRESS = "progress"
    private const val CHANNEL_DONE = "done"
    const val ID_SERVICE = 1
    private const val ID_ITEM_BASE = 1000

    fun ensureChannels(context: Context) {
        val nm = NotificationManagerCompat.from(context)
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_PROGRESS, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.channel_progress))
                .setShowBadge(false)
                .build(),
        )
        nm.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_DONE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.channel_done))
                .build(),
        )
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The ongoing notification while the service runs. */
    fun progress(context: Context, listing: Listing?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        val running = listing?.items?.filter { it.status == Status.DOWNLOADING }.orEmpty()
        if (listing == null || listing.active == 0) {
            builder.setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notif_finishing))
            return builder.build()
        }
        val title = context.resources.getQuantityString(R.plurals.notif_downloading, listing.active, listing.active)
        builder.setContentTitle(title)
        if (running.isEmpty()) {
            builder.setContentText(context.getString(R.string.status_queued))
        } else {
            val total = running.sumOf { it.size ?: 0L }
            val done = running.sumOf { it.downloaded }
            val known = running.all { it.size != null } && total > 0
            val speed = context.getString(R.string.speed_fmt, Formatter.formatShortFileSize(context, listing.speed))
            if (known) {
                builder.setProgress(100, (done * 100 / total).toInt(), false)
                builder.setContentText("${done * 100 / total}% · $speed")
            } else {
                builder.setProgress(0, 0, true)
                builder.setContentText(speed)
            }
            if (running.size == 1) builder.setSubText(running[0].name)
        }
        builder.addAction(
            0, context.getString(R.string.action_pause_all),
            PendingIntent.getService(
                context, 1,
                Intent(context, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    fun completed(context: Context, item: DownloadItem, uri: Uri?) {
        val tap = if (item.inPlace) {
            openApp(context)
        } else if (uri != null) {
            PendingIntent.getActivity(
                context, item.id.toInt(), Exporter.viewIntent(uri, item.name),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            openApp(context)
        }
        notify(
            context, item,
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(item.name)
                .setContentText(
                    context.getString(
                        when {
                            item.inPlace -> R.string.notif_complete_in_folder
                            uri != null -> R.string.notif_complete
                            else -> R.string.notif_complete_in_app
                        },
                        Formatter.formatShortFileSize(context, item.size ?: item.downloaded),
                    ),
                )
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build(),
        )
    }

    fun failed(context: Context, item: DownloadItem) {
        notify(
            context, item,
            NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(context.getString(R.string.notif_failed, item.name))
                .setContentText(item.error ?: context.getString(R.string.error_unknown))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Android stopped the service (it limits how long data transfers may run in one go). */
    fun timedOut(context: Context) {
        val n = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(context.getString(R.string.notif_timeout_title))
            .setContentText(context.getString(R.string.notif_timeout_text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()
        post(context, ID_ITEM_BASE - 1, n)
    }

    private fun notify(context: Context, item: DownloadItem, n: Notification) =
        post(context, ID_ITEM_BASE + (item.id % 100_000).toInt(), n)

    private fun post(context: Context, id: Int, n: Notification) {
        // Notifications the user turned off: downloads go on silently. (`areNotificationsEnabled`
        // is right on every Android version; the permission itself only exists from 13.)
        val nm = NotificationManagerCompat.from(context)
        if (!nm.areNotificationsEnabled()) return
        try {
            nm.notify(id, n)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}
