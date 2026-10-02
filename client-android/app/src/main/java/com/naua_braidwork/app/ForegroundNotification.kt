package com.naua_braidwork.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Постоянное уведомление клиента.
 *
 * Существует по двум причинам, и обе обязательные.
 *
 * Первая — Android с 8-й версии убивает фоновые процессы, и без foreground-сервиса
 * ядро перестаёт получать уведомления и отвечать на heartbeat. Пользователь увидит
 * уведомление и поймёт, что приложение работает.
 *
 * Вторая — на Android 10 и выше чтение буфера обмена работает только у процесса
 * в фокусе либо у foreground-сервиса. Без него синхронизация буфера с телефона
 * молча не работает.
 *
 * Уведомление нельзя скрывать: это требование операционной системы, и обойти его
 * без accessibility-обхода, который запрещён политикой Play, нельзя.
 */
object ForegroundNotification {

    const val CHANNEL_ID = "braidwork_link"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(context: Context) {
        // На Android 5 каналов нет, уведомление показывается как обычное.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Связь устройств",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Постоянное уведомление нужно, чтобы связь работала в фоне"
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context, deviceCount: Int, channelName: String?): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("Braidwork")
            .setContentText(
                when {
                    deviceCount > 0 -> "Устройств в семье: $deviceCount"
                    channelName != null -> "Связь по каналу: $channelName"
                    else -> "Ожидание устройств"
                },
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
}