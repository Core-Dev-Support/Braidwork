package com.naua_braidwork.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Единственный сервис клиента.
 *
 * Отдельных фоновых процессов приложение не заводит намеренно: система всё
 * равно убьёт их на слабом устройстве, а память они съедают зря. Всё живёт
 * внутри этого процесса, который система не трогает, пока у него есть
 * foreground-уведомление.
 */
class LinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(0)

        // START_STICKY: после убийства системой сервис поднимается заново.
        // Данные при этом не восстанавливаются — ядро само пересоздаёт сессии,
        // а ключи лежат на диске и не теряются.
        return START_STICKY
    }

    private fun startAsForeground(deviceCount: Int) {
        val notification = ForegroundNotification.build(this, deviceCount, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                ForegroundNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(ForegroundNotification.NOTIFICATION_ID, notification)
        }
    }
}