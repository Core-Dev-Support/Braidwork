package com.naua_braidwork.app

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
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
        следитьЗаНайденным()

        // START_STICKY: после убийства системой сервис поднимается заново.
        // Кадры при этом не восстанавливаются — их нельзя доиграть задним
        // числом, — а ключи лежат на диске и канал поднимается заново.
        return START_STICKY
    }

    /**
     * Следит за числом найденных и обновляет уведомление.
     *
     * Отдельный поток, а не вызов из цикла поиска: тот живёт в своём потоке, и
     * трогать системный сервис уведомлений оттуда — значит получить
     * исключение, если процесс уйдёт в фон между проверкой и вызовом.
     */
    private fun следитьЗаНайденным() {
        if (потокУведомлений != null) return

        val worker = Thread({
            val поиск = поиск ?: return@Thread
            while (запущен) {
                обновитьУведомление(поиск.snapshot().devices.size)
                Thread.sleep(1000)
            }
        }, "braidwork-notify")
        worker.priority = Thread.MIN_PRIORITY
        потокУведомлений = worker
        запущен = true
        worker.start()
    }

    private var потокУведомлений: Thread? = null

    @Volatile
    private var запущен = false

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

    /**
     * Пользователь смахнул приложение из недавних.
     *
     * Система останавливает сервис, но `START_STICKY` его поднимает заново — а
     * без этого обработчика канал умирал вместе с задачей, и напарник видел
     * «устройство пропало» через несколько секунд после того, как пользователь
     * просто закрыл приложение.
     *
     * ## Чего здесь нет и почему
     *
     * «Остановить» в настройках приложения обойти нельзя: это прямое указание
     * пользователя, система выполняет его всегда, и любая попытка подняться
     * после него считалась бы вредоносным поведением. Поэтому приложение не
     * пытается, а пользователь узнаёт о последствиях из экрана настроек: связь
     * работает, пока приложение не остановлено.
     *
     * Уведомление при этом нельзя смахнуть: иначе устройство молча перестало бы
     * объявлять себя, и пользователь решил бы, что сломалось приложение.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Канал намеренно не трогаем: сервис поднимется заново по START_STICKY,
        // а держать второй канал на том же порту было бы нельзя — первый ещё
        // не освободил сокет, и подъём второго упал бы.
        Log.i(TAG, "задача смахнута, канал остаётся поднят")
    }

    override fun onDestroy() {
        запущен = false
        потокУведомлений?.interrupt()
        потокУведомлений = null

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

    /**
     * Обновляет уведомление найденными устройствами.
     *
     * Счётчик в уведомлении — единственное место, где видно, что канал работает,
     * когда экран закрыт. Без него связь выглядит включённой по одному только
     * факту уведомления, и отличить «ищу» от «молча сломано» нельзя.
     *
     * Пересчёт идёт только при изменении числа: обновлять уведомление каждые
     * полсекунды незачем, система всё равно отдаёт его перерисовку не чаще
     * раза в секунду.
     */
    private fun обновитьУведомление(найдено: Int) {
        if (найдено == последнийСчётчик) return
        последнийСчётчик = найдено

        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        // Обновление, а не startForeground: повторный вызов пересоздал бы
        // уведомление и на Android 12+ уронил бы сервис за вызов из фона.
        runCatching {
            manager.notify(
                ForegroundNotification.NOTIFICATION_ID,
                ForegroundNotification.build(this, найдено, null),
            )
        }
    }

    private var последнийСчётчик = -1

    companion object {
        private const val TAG = "LinkService"

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
