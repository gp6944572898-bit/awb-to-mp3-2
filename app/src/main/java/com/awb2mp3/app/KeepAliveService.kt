package com.awb2mp3.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager

/**
 * Служба переднего плана: пока она работает, Android не выгружает приложение
 * и конвертация продолжается при выключенном экране. Сама работа идёт в MainActivity,
 * служба показывает уведомление с прогрессом и держит процессор бодрствующим.
 */
class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val notification = buildProgress(this, "Подготовка…", 0, indeterminate = true)
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "awb2mp3:convert").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // не дольше 12 часов
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val DONE_NOTIFICATION_ID = 2
        private const val CHANNEL_PROGRESS = "progress"
        private const val CHANNEL_DONE = "done"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, KeepAliveService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, KeepAliveService::class.java))
        }

        private fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Ход конвертации", NotificationManager.IMPORTANCE_LOW)
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Конвертация завершена", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        private fun openAppIntent(context: Context): PendingIntent =
            PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

        fun buildProgress(context: Context, text: String, percent: Int, indeterminate: Boolean = false): Notification =
            Notification.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("AWB → MP3")
                .setContentText(text)
                .setProgress(100, percent, indeterminate)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openAppIntent(context))
                .build()

        /** Обновляет текст и прогресс в уведомлении службы */
        fun update(context: Context, text: String, percent: Int) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildProgress(context, text, percent))
        }

        /** Итоговое уведомление, когда всё закончено */
        fun showDone(context: Context, text: String) {
            ensureChannels(context)
            val n = Notification.Builder(context, CHANNEL_DONE)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("AWB → MP3: готово")
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context))
                .build()
            context.getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, n)
        }
    }
}
