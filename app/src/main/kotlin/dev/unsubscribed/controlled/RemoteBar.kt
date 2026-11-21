package dev.unsubscribed.controlled

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.RemoteViews

/**
 * The remote in the notification shade, and on the lock screen: six keys collapsed, Back, Home and the top apps when
 * pulled down. It's a plain notification, not a service: Android keeps it after the app closes, and a tap runs
 * [RemoteWidget]'s press like the widget's, so nothing runs until then. Android 14 lets the person swipe it away; it's
 * back the next time the app opens, and after a restart ([Boot]).
 */
object RemoteBar {
    private const val CHANNEL = "remote"
    private const val ID = 1

    fun allowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Shows the bar for [tv], or removes it when null (no TV, or the person turned it off). [views] draws a layout. */
    fun update(context: Context, tv: PairedTv?, views: (Int) -> RemoteViews) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (tv == null || !allowed(context)) return manager.cancel(ID)
        val t = stringsOf(context.resources.configuration.locales[0].language)
        // Low: no sound, no pop-up, no badge; it sits in the shade.
        manager.createNotificationChannel(NotificationChannel(CHANNEL, t.barChannel, NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.sym_settings_remote)
            .setContentTitle(tv.name)
            .setContentIntent(open)
            .setStyle(Notification.DecoratedCustomViewStyle())
            .setCustomContentView(views(R.layout.bar_small))
            .setCustomBigContentView(views(R.layout.bar_big))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        manager.notify(ID, notification)
    }

    /** After a restart the shade is empty: the bar comes back by itself. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) RemoteWidget.refresh(context)
        }
    }
}
