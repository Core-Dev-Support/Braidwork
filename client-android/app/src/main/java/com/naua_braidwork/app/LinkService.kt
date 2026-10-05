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
 *
 * ## Почему канал живёт здесь, а не в Activity
 *
 * Поиск устройств обязан идти, пока приложение свёрнуто: иначе устройство
 * перестаёт объявлять себя при первом же сворачивании, и напарник не найдёт его
 * ровно тогда, когда оно лежит рядом и им пользуются. `Activity` система
 * уничтожает свободно, а этот процесс — нет, пока у него есть уведомление.
 */
class LinkService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startAsForeground(0)

        // START_STICKY: после убийства системой сервис поднимается заново.
        // Кадры при этом не восстанавливаются — их нельзя доиграть задним
        // числом, — а ключи лежат на диске и канал поднимается заново.
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        // Файла ключей может не быть только до первого запуска экрана. Пока его
        // нет, канал не поднимается: транспорт без ключей — это устройство без
        // личности, которое не сможет себя подтвердить.
        val store = DeviceIdentityStore(filesDir)
        val поиск = DeviceDiscovery { store.loadOrCreateBlob() }
        отдать(поиск)

        runCatching { поиск.start() }
    }

    override fun onDestroy() {
        // Канал освобождается здесь же, а не в финализаторе: процесс могут
        // убить без предупреждения, и тогда сокеты закроются вместе с ядром
        // процесса. Явная остановка нужна ради порядка и ради журнала.
        поиск?.stop()
        отдать(null)
        super.onDestroy()
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

    companion object {
        /**
         * Канал поиска для экранов.
         *
         * Один на процесс, и поэтому `null` до старта сервиса. Экраны обязаны
         * это учитывать: пустой канал и ещё не поднятый — разные вещи, и
         * путать их нельзя, иначе список устройств мигнёт пустым при каждом
         * открытии приложения.
         */
        @Volatile
        var поиск: DeviceDiscovery? = null
            private set

        /** Точка входа для `onDestroy`, чтобы поле осталось в companion. */
        fun отдать(экземпляр: DeviceDiscovery?) {
            поиск = экземпляр
        }
    }
}
