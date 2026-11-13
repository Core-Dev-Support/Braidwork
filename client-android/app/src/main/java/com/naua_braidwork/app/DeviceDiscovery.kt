package com.naua_braidwork.app

import android.content.ClipData
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import com.naua_braidwork.app.ui.state.ClipboardItem
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.naua_braidwork.app.ui.state.Transfer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Владелец канала локальной сети.
 *
 * Существует один на приложение и живёт столько же, сколько [LinkService]:
 * система не трогает процесс с foreground-уведомлением, а канал, поднятый в
 * Activity, умер бы вместе с экраном — устройство перестало бы объявлять себя
 * при первом же сворачивании.
 *
 * Опрос идёт в своём потоке. Причина не в скорости, а в блокировках: ядро
 * зовёт сокеты, а ждать их отклик на главном потоке означало бы заморозить
 * интерфейс на всё время ожидания.
 *
 * ## Что здесь есть
 *
 * Первая настоящая передача: сердцебиение уходит сразу по рукопожатии,
 * содержимое буфера — по просьбе пользователя, а принятые кадры разбираются и
 * попадают в журнал. Раньше принятые кадры просто выбрасывались, и по журналу
 * нельзя было сказать, дошло ли что-нибудь.
 *
 * Сопряжения здесь нет: им занимается хранилище доверия, а решение человека
 * хранится отдельно от канала. Второй путь к рукопожатию из клиента разошёлся бы
 * с первым, а рукопожатие в `fl_session_*` намеренно оставлено для настольных
 * клиентов.
 */
class DeviceDiscovery(
    private val keysBlobProvider: () -> ByteArray,
    /**
     * Контекст для чтения состояния батареи.
     *
     * Отдельным параметром, а не доступом извне: заряд берётся у системы, и
     * свой счётчик разряда разошёлся бы с системным. Человек увидел бы на
     * экране соседа не тот процент, что показывает его собственный телефон.
     */
    private val context: Context,
) {

    /** Что видно пользователю. */
    data class Device(
        val deviceId: ByteArray,
        val port: Int,
        /**
         * Имя, которое устройство назвало само.
         *
         * Заявление, а не проверенный факт: подпись сверяется только на
         * соединении. Но показывать нужно именно его — «Устройство» в списке
         * не отличить ни от соседского телефона, ни от своего второго.
         */
        val name: String,
        /** Адрес в подсети, четыре байта в сетевом порядке. */
        val ipv4: ByteArray,
        /** Соединение установлено и рукопожатие прошло. */
        val connected: Boolean,
        /** Когда видели в последний раз, миллисекунды системного времени. */
        val lastSeenMs: Long,
        /**
         * Отпечаток ключа подписи: четыре группы по четыре символа.
         *
         * Приходит из объявления, где ядро уже сверило его с идентификатором.
         * Поэтому он годится для показа и сверки, а идентификатор из
         * объявления — нет: тот проверен лишь на соответствие ключу.
         *
         * Пусто у устройства с более старым ядром.
         */
        val fingerprint: String = "",
        /** Имя системы напарника, например «Android 13». Пусто у старого ядра. */
        val platform: String = "",
        /** Версия ядра напарника. Пусто у старого ядра. */
        val coreVersion: String = "",
        /**
         * Тип устройства напарника, каким он назвал себя сам.
         *
         * Пусто у старого ядра или когда клиент не смог определить тип. На
         * карточке это читается как «неизвестно».
         *
         * Значение по умолчанию ставить нельзя: ноутбук на карточке телефона —
         * правдоподобное враньё, которому человек поверит.
         */
        val deviceKind: String = "",
    ) {
        // `lastSeenMs` в сравнение не входит: он меняется при каждом объявлении,
        // и с ним устройство никогда не было бы «тем же самым». Список
        // перерисовывался бы каждые две секунды ради ничего не изменившегося.
        override fun equals(other: Any?) =
            other is Device &&
                deviceId.contentEquals(other.deviceId) &&
                port == other.port &&
                name == other.name &&
                ipv4.contentEquals(other.ipv4) &&
                fingerprint == other.fingerprint &&
                platform == other.platform &&
                coreVersion == other.coreVersion &&
                deviceKind == other.deviceKind &&
                connected == other.connected

        override fun hashCode(): Int =
            31 * (31 * ((31 * (31 * (31 * (31 * (31 * deviceId.contentHashCode() + port) +
                name.hashCode()) + ipv4.contentHashCode()) * 31 + fingerprint.hashCode()) * 31 +
                platform.hashCode()) * 31 + coreVersion.hashCode()) * 31 + deviceKind.hashCode()) *
                31 + connected.hashCode()
    }

    /**
     * Что прислал сосед последним сердцебиением.
     *
     * Отдельная карта, а не поле в [Device]: устройство обновляется каждые две
     * секунды по объявлению, и заряд соседа в нём про месте не с того момента.
     */
    data class PeerPulse(
        /** Заряд соседа в процентах, 0 — разряжен, а не «неизвестно». */
        val заряд: Int,
        /** На зарядке ли сосед. */
        val зарядка: Boolean,
        /** Когда пришло, миллисекунды Unix. */
        val вМс: Long,
    ) {
        /**
         * Свежее ли это сообщение.
         *
         * Граница втрое больше периода отправки. Вдвое означало бы, что положение
         * делает одно пропущенное сообщение, и точка гасла бы при обычном
         * кратковременном задержании сети — то есть мигала бы без причины.
         */
        fun свежее(сейчас: Long) = сейчас - вМс < СВЕЖЕСТЬ_СЕРДЦЕБИЕНИЯ_МС
    }

    /** Сводка обмена за сегодня для экрана «Обмен». */
    data class ShareStats(
        val todayBytesText: String = "0 МБ",
        val clipboardCount: Int = 0,
        val filesCount: Int = 0,
        val notifsCount: Int = 0,
        val queueCount: Int = 0,
        val clipboardProgress: Float = 0f,
        val filesProgress: Float = 0f,
        val queueProgress: Float = 0f,
    ) {
        companion object {
            val EMPTY = ShareStats()
        }
    }

    /** Завершённая передача файла для истории на экране «Файлы». */
    data class CompletedTransfer(
        val fileName: String,
        val peerName: String,
        val totalSize: Long,
        val isIncoming: Boolean,
        val success: Boolean,
        val error: String? = null,
        val timestamp: Long = System.currentTimeMillis(),
    )

    /** Состояние для интерфейса. */
    data class Snapshot(
        /** Найденные устройства. */
        val devices: List<Device>,
        /** Порт, на котором нас принимают. Ноль — канал не поднят. */
        val port: Int,
        /** Жив ли канал. */
        val running: Boolean,
        /** Последняя ошибка, если была. Пустая строка — ошибок не было. */
        val problem: String,
        /**
         * Последнее сердцебиение по каждому устройству, ключ — идентификатор.
         *
         * Пусто для устройства, которое ещё ничего не прислало. Показывать
         * «неизвестно» честнее, чем брать заряд из объявления: объявление о батарее
         * не говорит ничего.
         */
        val пульсы: Map<String, PeerPulse> = emptyMap(),
        val shareStats: ShareStats = ShareStats.EMPTY,
        val transfers: List<Transfer> = emptyList(),
    ) {
        companion object {
            val EMPTY = Snapshot(emptyList(), 0, false, "")
        }
    }

    /** Что происходит с каналом. */
    enum class State { ОСТАНОВЛЕН, ЗАПУСКАЕТСЯ, РАБОТАЕТ, ОШИБКА }

    /**
     * Что происходило с подключениями, последние шаги.
     *
     * Показывается пользователю в карточке устройства. Причина не в удобстве:
     * журнал ядра и клиента читается только с кабелем, а на телефоне из рук
     * пользователя подключение либо работает, либо нет — и без этой полосы
     * «не работает» неотличимо от «не нажали» или «нажали не туда».
     *
     * Новые записи сверху, старые снизу, глубина ограничена: полоса не должна
     * расти без конца и выталкивать карточку за экран.
     */
    private val след = java.util.concurrent.CopyOnWriteArrayList<String>()

    private fun записать(строка: String) {
        Log.i(TAG, строка)
        if (след.size >= ГЛУБИНА_СЛЕДА) след.removeAt(след.size - 1)
        след.add(0, строка)
    }

    /** Последние шаги подключения, новые сверху. */
    fun trail(): List<String> = след.toList()

    // =====================================================================
    // Первая настоящая передача
    //
    // Отправка и разбор разведены по разным файлам не по вкусу, а по границе:
    // protobuf разбирает только ядро, и клиент получает готовые значения.
    // Вторая реализация разбора на стороне приложения разъехалась бы с ядром
    // при первом изменении схемы, и в журнале появилось бы правдоподобное, но не
    // то, что прислал напарник.
    // =====================================================================

    // Типы сообщений — в `companion object`: `const val` разрешён только там, в
    // объекте и на верхнем уровне, а в теле класса компилятор его не примет.

    /**
     * Отправляет сердцебиение по соединению.
     *
     * Заряд и зарядку приложение знает само; время берётся системное. Всё это
     * настоящие значения: подставлять что-то другое нельзя, потому что человек
     * сверяет их с экраном своего телефона.
     *
     * Ошибка пишется в журнал, а не пробрасывается: вызов идёт из цикла опроса
     * событий, и исключение здесь остановило бы чтение событий, то есть
     * отключило бы связь целиком.
     *
     * [безЗаписиВЖурнал] убирает повторяющуюся строку: сердцебиение ходит каждые
     * несколько секунд, и в журнале на восемь строк оно вытеснило бы всё
     * остальное. Отправку и неотправку при этом видно — по тому, гаснет ли
     * точка.
     */
    private fun послатьСердцебиение(conn: Long, безЗаписиВЖурнал: Boolean = false) {
        val h = хэндл.get()
        if (h == 0L) return

        // Не первая попытка по этому соединению: повторы не пишутся в журнал.
        val прошлоНеОдинРаз = отправленоСердцебие[conn] != null

        val заряд = процентЗаряда()
        if (заряд == null) {
            // Не отправляем вовсе. Отправить «заряд 0 %» — значит написать на
            // экране соседа, что его телефон разряжен, и человек начал бы
            // искать, где его зарядить. Молчание здесь честнее неправды.
            if (!безЗаписиВЖурнал) {
                записать("состояние батареи неизвестно — сердцебиение не отправлено")
            }
            return
        }

        val кадр = try {
            BraidworkCore.heartbeatFrame(
                unixTimeMs = System.currentTimeMillis(),
                batteryPercent = заряд.percent,
                charging = заряд.charging,
            )
        } catch (e: BraidworkException) {
            if (!безЗаписиВЖурнал) {
                записать("сердцебиение не собрано: ${e.message}")
            }
            return
        }

        try {
            BraidworkCore.transportSend(h, conn, кадр)
        } catch (e: BraidworkException) {
            // «Рукопожатие не завершено» здесь означает, что событие Connected
            // пришло, а сессия ещё не готова. Сообщение это говорит прямо, чтобы
            // в журнале не было безымянного «не отправилось».
            //
            // Отказ после первой попытки пишется всегда: повторное молчание
            // выглядело бы как «всё в порядке», а точка на экране погасла бы
            // без единого слова.
            if (!безЗаписиВЖурнал || прошлоНеОдинРаз) {
                записать("сердцебиение не отправлено: ${e.message}")
            }
        }
    }

    /**
     * Отправляет содержимое буфера обмена по соединению.
     *
     * Возвращает `null` при успехе и текст ошибки при отказе. Пользователь должен
     * увидеть отказ: молча неудачная отправка выглядела бы как «отправилось».
     */
    fun послатьБуфер(conn: Long, текст: String): String? {
        val h = хэндл.get()
        if (h == 0L) return "канал не поднят"

        val кадр = try {
            BraidworkCore.clipboardFrame(текст, "text/plain")
        } catch (e: BraidworkException) {
            return e.message
        }

        return try {
            BraidworkCore.transportSend(h, conn, кадр)
            sentClipboardCount.incrementAndGet()
            saveStats()
            записать("буфер отправлен: ${текст.кратко()} (${текст.length} символов)")
            mainHandler.post {
                Toast.makeText(context, "Буфер отправлен (${текст.length} симв.)", Toast.LENGTH_SHORT).show()
            }
            null
        } catch (e: BraidworkException) {
            val причина = e.message
            записать("буфер не отправлен: $причина")
            mainHandler.post {
                Toast.makeText(context, "Ошибка отправки буфера: $причина", Toast.LENGTH_SHORT).show()
            }
            причина
        }
    }

    /** Разбирает принятый кадр и пишет в журнал то, что в нём было на самом деле. */
    private fun разобратьКадр(
        кадр: BraidworkCore.TransportEvent.Frame,
        соединения: Map<Long, String>,
        прежние: MutableMap<String, Device>,
    ) {
        val кто = соединения[кадр.conn] ?: "неизвестное устройство"
        прежние[кто]?.let {
            прежние[кто] = it.copy(lastSeenMs = System.currentTimeMillis())
        }
        замолчавшие.remove(кто)

        when (кадр.kind) {
            KIND_HEARTBEAT -> {
                val разобранное = BraidworkCore.readHeartbeat(кадр.body)
                if (разобранное == null) {
                    // Тело не разобралось. Это не поломка связи — возможно, пришло
                    // сообщение нового вида. Молчать нельзя: тогда неопознанный
                    // кадр был бы неотличим от потери.
                    записать(
                        "от $кто: сердцебиение не разобрано, ${кадр.body.size} байт"
                    )
                    return
                }
                val заряд = if (разобранное.charging) {
                    "заряд ${разобранное.batteryPercent} %, на зарядке"
                } else {
                    "заряд ${разобранное.batteryPercent} %"
                }
                // Время подписано как «напарника»: его часы могут врать, и своё
                // время, показанное чужими часами, выглядело бы как ошибка
                // приложения.
                записать(
                    "от $кто: сердцебиение, $заряд, " +
                        "время напарника ${время(разобранное.unixTimeMs)}"
                )
                пульсы[кто] = PeerPulse(
                    заряд = разобранное.batteryPercent,
                    зарядка = разобранное.charging,
                    // Отмечаем момент получения, а не время из сообщения: часы
                    // соседа могут врать на минуты, и по ним «свежесть» считалась
                    // бы неверно.
                    вМс = System.currentTimeMillis(),
                )
            }

            KIND_CLIPBOARD_CONTENT -> {
                val разобранное = BraidworkCore.readClipboard(кадр.body)
                if (разобранное == null) {
                    записать("от $кто: содержимое не разобрано, ${кадр.body.size} байт")
                    return
                }
                val текст = разобранное.first
                val тип = разобранное.second.ifEmpty { "без типа" }

                val trust = TrustStore.get(context)
                val dev = прежние[кто]
                val fp = dev?.fingerprint.orEmpty()
                val trustState = trust.состояние(кто, fp)

                if (trustState == TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ) {
                    записать("ОТКЛОНЕНО: отпечаток устройства $кто изменился! Буфер не принят из соображений безопасности")
                    mainHandler.post {
                        Toast.makeText(context, "Внимание: отпечаток $кто изменился!", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                // Полный текст в журнал не пишется: журнал читают с телефона, и
                // там может оказаться пароль. Показываем начало — столько,
                // чтобы человек узнал, что пришло то самое, и не больше.
                записать(
                    "от $кто: содержимое, $тип, ${текст.кратко()} " +
                        "(${текст.length} символов)"
                )
                // Запоминаем целиком: экран «Буфер обмена» должен показать
                // именно то, что вставится. Обрезанный для журнала текст в
                // список не годится.
                if (полученное.size >= ГЛУБИНА_ПОЛУЧЕННОГО) {
                    полученное.removeAt(полученное.size - 1)
                }
                полученное.add(
                    0,
                    ClipboardItem(
                        id = "cb_" + текст.hashCode().toUInt().toString(16) + "_" +
                            System.currentTimeMillis(),
                        текст = текст,
                        тип = тип,
                        отКого = кто,
                        вМс = System.currentTimeMillis(),
                    )
                )
                вставитьВБуфер(текст, кто, trustState)
            }

            KIND_FILE_OFFER -> {
                val offer = BraidworkCore.readFileOffer(кадр.body)
                if (offer == null) {
                    записать("от $кто: предложение файла не разобрано, ${кадр.body.size} байт")
                    return
                }
                val tidHex = offer.transferId.joinToString("") { "%02x".format(it) }
                val safeName = sanitizeFileName(offer.fileName)
                val sizeStr = formatBytes(offer.totalSize)

                if (offer.totalSize <= 0 || offer.totalSize > 1024L * 1024 * 1024 * 1024) {
                    записать("ОТКЛОНЕНО: недопустимый размер файла «$safeName» (${offer.totalSize} байт)")
                    return
                }

                val trust = TrustStore.get(context)
                val dev = прежние[кто]
                val fp = dev?.fingerprint.orEmpty()
                val trustState = trust.состояние(кто, fp)
                if (trustState == TrustStore.Доверие.ОТПЕЧАТОК_ИЗМЕНИЛСЯ) {
                    записать("ОТКЛОНЕНО: отпечаток $кто изменился! Приём файла «$safeName» заблокирован")
                    mainHandler.post {
                        Toast.makeText(context, "Отказ: подозрительная смена ключа у $кто", Toast.LENGTH_LONG).show()
                    }
                    return
                }

                val peerDisplayName = dev?.name ?: кто
                if (trustState == TrustStore.Доверие.НЕ_СВЕРЕНО) {
                    записать("от $peerDisplayName: входящий файл «$safeName» ($sizeStr) [не сопряжено]")
                    mainHandler.post {
                        Toast.makeText(context, "Входящий файл: $safeName ($sizeStr)", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    записать("от $peerDisplayName: входящий файл «$safeName» ($sizeStr)")
                    mainHandler.post {
                        Toast.makeText(context, "Входящий файл: $safeName ($sizeStr)", Toast.LENGTH_SHORT).show()
                    }
                }

                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Braidwork")
                if (!dir.exists()) dir.mkdirs()
                val tempFile = File(dir, ".bw_${tidHex}.part")
                val stateFile = File(dir, ".bw_${tidHex}.state")
                val finalFile = findUniqueFile(dir, safeName)

                var rHandle = 0L
                try {
                    rHandle = BraidworkCore.createFileReceiver(
                        offerBody = кадр.body,
                        tempPath = tempFile.absolutePath,
                        destPath = finalFile.absolutePath,
                        statePath = stateFile.absolutePath,
                    )
                } catch (e: Exception) {
                    записать("ошибка создания FileReceiver для «$safeName»: ${e.message}")
                    return
                }

                val transfer = ActiveTransfer(
                    transferIdHex = tidHex,
                    peerId = кто,
                    peerName = peerDisplayName,
                    fileName = safeName,
                    totalSize = offer.totalSize,
                    totalHash = offer.totalHash,
                    isIncoming = true,
                    tempFile = tempFile,
                    finalFile = finalFile,
                    stateFile = stateFile,
                    conn = кадр.conn,
                    receiverHandle = rHandle,
                )
                activeTransfers[tidHex] = transfer
            }

            KIND_FILE_BLOCK -> {
                val block = BraidworkCore.readFileBlock(кадр.body)
                if (block == null) {
                    записать("от $кто: повреждённый или неразобранный блок файла (${кадр.body.size} байт)")
                    return
                }
                val tidHex = block.transferId.joinToString("") { "%02x".format(it) }
                val transfer = activeTransfers[tidHex]
                if (transfer == null || transfer.cancelled) {
                    return
                }

                val rHandle = transfer.receiverHandle
                if (rHandle == 0L) {
                    return
                }

                transfer.lastBlockTime = System.currentTimeMillis()

                try {
                    val outcome = BraidworkCore.fileReceiverWriteBlock(rHandle, кадр.body)
                    if (outcome.result != BraidworkCore.BlockWriteResult.DUPLICATE) {
                        transfer.transferredBytes += block.data.size
                    }

                    val now = System.currentTimeMillis()
                    val elapsed = now - transfer.lastCalcTime
                    if (elapsed >= 500) {
                        val bytesDelta = transfer.transferredBytes - transfer.lastCalcBytes
                        transfer.speedMbps = (bytesDelta * 8.0) / (elapsed * 1000.0)
                        transfer.lastCalcBytes = transfer.transferredBytes
                        transfer.lastCalcTime = now
                    }

                    if (outcome.result == BraidworkCore.BlockWriteResult.COMPLETE || transfer.transferredBytes >= transfer.totalSize) {
                        // Финализируем приём в ядре Rust: проверяется поточный BLAKE3 хэш всего файла и выполняется атомарный rename в целевую директорию
                        BraidworkCore.fileReceiverFinish(rHandle)
                        BraidworkCore.fileReceiverFree(rHandle)
                        transfer.receiverHandle = 0L

                        // Отправляем Positive Completion ACK (пустой список missingOffsets подтверждает 100% приём)
                        val ackFrame = BraidworkCore.fileRequestFrame(block.transferId, LongArray(0))
                        val h = хэндл.get()
                        val conn = if (transfer.conn != 0L) transfer.conn else кадр.conn
                        if (h != 0L && conn != 0L) {
                            try {
                                BraidworkCore.transportSend(h, conn, ackFrame)
                            } catch (_: Exception) {}
                        }

                        val targetFile = transfer.finalFile ?: File(
                            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "Braidwork/${transfer.fileName}"
                        )
                        android.media.MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), null, null)

                        transfer.done = true
                        activeTransfers.remove(tidHex)
                        bytesTransferredToday.addAndGet(transfer.totalSize)
                        filesTransferredCount.incrementAndGet()
                        saveStats()
                        completedTransfers.add(0, CompletedTransfer(
                            fileName = transfer.fileName,
                            peerName = transfer.peerName,
                            totalSize = transfer.totalSize,
                            isIncoming = true,
                            success = true,
                        ))
                        if (completedTransfers.size > 20) completedTransfers.removeAt(completedTransfers.size - 1)

                        записать("файл «${transfer.fileName}» успешно принят и проверен ядром Rust (BLAKE3)")
                        mainHandler.post {
                            Toast.makeText(context, "Файл получен: ${transfer.fileName}", Toast.LENGTH_LONG).show()
                        }
                    }
                } catch (e: Exception) {
                    записать("ошибка приёма файла «${transfer.fileName}»: ${e.message}")
                    if (transfer.receiverHandle != 0L) {
                        try {
                            BraidworkCore.fileReceiverFree(transfer.receiverHandle)
                        } catch (_: Exception) {}
                        transfer.receiverHandle = 0L
                    }
                    transfer.tempFile?.delete()
                    transfer.stateFile?.delete()
                    transfer.error = e.message ?: "ошибка приёма"
                    activeTransfers.remove(tidHex)
                    completedTransfers.add(0, CompletedTransfer(
                        fileName = transfer.fileName,
                        peerName = transfer.peerName,
                        totalSize = transfer.totalSize,
                        isIncoming = true,
                        success = false,
                        error = e.message ?: "ошибка приёма",
                    ))
                    if (completedTransfers.size > 20) completedTransfers.removeAt(completedTransfers.size - 1)
                }
            }

            KIND_FILE_REQUEST -> {
                val req = BraidworkCore.readFileRequest(кадр.body)
                if (req == null) {
                    записать("от $кто: запрос докачки (FileRequest) не разобран")
                    return
                }
                val tidHex = req.transferId.joinToString("") { "%02x".format(it) }

                val transfer = activeTransfers[tidHex]
                if (req.missingOffsets.isEmpty()) {
                    // Positive Completion ACK от получателя
                    записать("от $кто: получено подтверждение завершения доставки (ACK) для файла «${transfer?.fileName ?: tidHex}»")
                    if (transfer != null) {
                        transfer.done = true
                    }
                } else {
                    записать("от $кто: запрос докачки для transfer_id=$tidHex (пропущено блоков: ${req.missingOffsets.size})")
                    if (transfer != null && transfer.senderHandle != 0L) {
                        try {
                            val retransmitted = BraidworkCore.fileSenderHandleRequest(transfer.senderHandle, кадр.body)
                            записать("FileSender: добавлено $retransmitted блоков в очередь повторной отправки")
                        } catch (e: Exception) {
                            записать("ошибка обработки FileRequest в FileSender: ${e.message}")
                        }
                    }
                }
            }

            else -> записать(
                // Неизвестный тип: это не ошибка и не повод молчать. Напарник
                // может быть новее, и тогда незнакомый кадр — норма.
                "от $кто: сообщение типа ${кадр.kind}, ${кадр.body.size} байт — не разобрано"
            )
        }
    }

    /**
     * Кладёт присланное содержимое в буфер обмена этого устройства.
     *
     * Без этого текст приходил в журнал, но оставался там: человек нажал
     * «Отправить буфер», а на втором телефоне ничего не происходило. Ради этого
     * кнопка и нажимается.
     *
     * ## Что здесь перезаписывается
     *
     * Буфер целиком. Если на устройстве лежал другой текст, он пропадёт, и это
     * единственный способ — буфер в системе один. Поэтому действие пишется в
     * журнал словами «перезаписан», а не «получено»: человек должен понимать,
     * что старый текст ушёл.
     *
     * ## Почему сразу, а не по кнопке
     *
     * Отправитель нажал «Отправить буфер» — он уже решил, что текст должен
     * оказаться там. Второе подтверждение на принимающем устройстве превращало
     * бы действие в «положить и hope, что он увидит».
     */
    private fun вставитьВБуфер(текст: String, кто: String, trustState: TrustStore.Доверие) {
        if (trustState == TrustStore.Доверие.НЕ_СВЕРЕНО) {
            записать("от $кто: текст добавлен в историю Braidwork (системный буфер не изменён: устройство не сопряжено)")
            mainHandler.post {
                Toast.makeText(context, "Текст от $кто сохранён в истории (не сопряжено)", Toast.LENGTH_SHORT).show()
            }
            return
        }

        mainHandler.post {
            val менеджер = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (менеджер == null) {
                записать("от $кто: буфер обмена недоступен, текст только в журнале")
                return@post
            }
            try {
                менеджер.setPrimaryClip(ClipData.newPlainText("Braidwork", текст))
                записать("буфер обмена перезаписан текстом от $кто (сопряжено)")
                Toast.makeText(context, "Скопировано с $кто: ${текст.кратко()}", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                записать("буфер обмена не обновлён: ${e.message}")
            }
        }
    }

    /**
     * Время напарника в его собственном поясе.
     *
     * Формат короткий намеренно: журнал читают на ходу, и полная дата с
     * секундами заняла бы две строки на каждое сообщение.
     */
    private fun время(мс: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(мс))

    /** Активная передача файла (входящая или исходящая). */
    data class ActiveTransfer(
        val transferIdHex: String,
        val peerId: String,
        val peerName: String,
        val fileName: String,
        val totalSize: Long,
        var totalHash: ByteArray,
        val isIncoming: Boolean,
        @Volatile var transferredBytes: Long = 0L,
        @Volatile var lastCalcBytes: Long = 0L,
        @Volatile var lastCalcTime: Long = System.currentTimeMillis(),
        @Volatile var speedMbps: Double = 0.0,
        var tempFile: File? = null,
        var finalFile: File? = null,
        var stateFile: File? = null,
        var conn: Long = 0L,
        var raf: RandomAccessFile? = null,
        var senderHandle: Long = 0L,
        var receiverHandle: Long = 0L,
        @Volatile var lastBlockTime: Long = System.currentTimeMillis(),
        @Volatile var cancelled: Boolean = false,
        @Volatile var done: Boolean = false,
        @Volatile var error: String? = null,
        @Volatile var statusText: String? = null,
    )

    private val activeTransfers = ConcurrentHashMap<String, ActiveTransfer>()
    private val completedTransfers = java.util.concurrent.CopyOnWriteArrayList<CompletedTransfer>()
    private val bytesTransferredToday = AtomicLong(0L)
    private val filesTransferredCount = AtomicInteger(0)
    private val sentClipboardCount = AtomicInteger(0)
    private val fileTransferExecutor = Executors.newSingleThreadExecutor()

    private val statsPrefs by lazy {
        context.applicationContext.getSharedPreferences("braidwork_stats", Context.MODE_PRIVATE)
    }

    init {
        loadStats()
    }

    private fun loadStats() {
        try {
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            val savedDay = statsPrefs.getString("stats_day", "")
            if (savedDay == today) {
                bytesTransferredToday.set(statsPrefs.getLong("bytes_today", 0L))
                filesTransferredCount.set(statsPrefs.getInt("files_today", 0))
                sentClipboardCount.set(statsPrefs.getInt("clip_today", 0))
            } else {
                statsPrefs.edit().putString("stats_day", today).putLong("bytes_today", 0L).putInt("files_today", 0).putInt("clip_today", 0).apply()
            }
        } catch (_: Exception) {}
    }

    private fun saveStats() {
        try {
            val today = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())
            statsPrefs.edit()
                .putString("stats_day", today)
                .putLong("bytes_today", bytesTransferredToday.get())
                .putInt("files_today", filesTransferredCount.get())
                .putInt("clip_today", sentClipboardCount.get())
                .apply()
        } catch (_: Exception) {}
    }

    /** Пришедшее содержимое, новые сверху. */
    private val полученное =
        java.util.concurrent.CopyOnWriteArrayList<ClipboardItem>()

    /**
     * Что пришло по каналу, для экрана «Буфер обмена».
     *
     * Глубина ограничена двадцатью записями: список на телефоне не должен расти
     * вечно, а вставить нужно что-то недавнее. Всё, что пришло, попадает в
     * журнал подключения — там ничего не теряется.
     */
    fun полученноеСодержимое(): List<ClipboardItem> = полученное.toList()

    /** Запись по идентификатору: для действия «вставить». */
    fun найтиПолученное(id: String): ClipboardItem? = полученное.firstOrNull { it.id == id }

    /**
     * Кладёт запись из истории в буфер обмена по нажатию пользователя.
     *
     * Возвращает текст ошибки или `null`. Нажатие без ответа выглядело бы как
     * поломка, поэтому причина показывается.
     */
    fun вставитьПолученное(id: String): String? {
        val предмет = найтиПолученное(id) ?: return "запись не найдена"
        val менеджер = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "буфер обмена недоступен"
        return try {
            менеджер.setPrimaryClip(ClipData.newPlainText("Braidwork", предмет.текст))
            записать("вставлено из истории: ${предмет.текст.кратко()}")
            null
        } catch (e: RuntimeException) {
            val причина = "буфер обмена не обновлён: ${e.message}"
            записать(причина)
            причина
        }
    }

    /**
     * Заряд устройства, если система его назвала.
     *
     * Процент берётся у системы, а не считается вручную: свой счётчик разряда
     * разошёлся бы с системным, и человек увидел бы на экране соседа не тот
     * процент, что показывает его собственный телефон.
     *
     * `null` означает «система не сообщила». Подставлять на это место ноль нельзя:
     * «заряд 0 %» на экране соседа читается как «телефон разряжен», и человек
     * начал бы искать, где его зарядить. Лучше не отправить вовсе.
     */
    private fun процентЗаряда(): Заряд? {
        val фильтр = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        // Тип не указывается: с `Intent?` компилятор перестаёт понимать, что
        // после `?: return` значение не nullable, и требует `?.` на каждой строке.
        val сообщение = context.registerReceiver(null, фильтр) ?: return null
        val уровень = сообщение.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val шкала = сообщение.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (уровень < 0 || шкала <= 0) return null

        val процент = ((уровень.toFloat() / шкала) * 100).toInt().coerceIn(0, 100)
        val состояние = сообщение.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val зарядка = состояние == BatteryManager.BATTERY_STATUS_CHARGING ||
            состояние == BatteryManager.BATTERY_STATUS_FULL
        return Заряд(процент, зарядка)
    }

    private data class Заряд(val percent: Int, val charging: Boolean)

    /**
     * Устройство исчезает из списка через столько миллисекунд с последнего
     * объявления.
     *
     * Объявления повторяются каждые две секунды, а сеть может умолчать на пару
     * секунд из-за одного лишнего пакета. Три пропуска подряд — уже не сеть
     * молчит, а устройство ушло, и оставлять его в списке нельзя: пользователь
     * ткнёт и получит отказ без объяснений.
     */
    private val исчезает_через_мс = 9000L

    private val запущен = AtomicBoolean(false)
    private val хэндл = AtomicLong(0)
    private val снимки = java.util.concurrent.atomic.AtomicReference(Snapshot.EMPTY)

    /**
     * Соответствие «хэндл соединения — устройство».
     *
     * Нужно потому, что события соединения приходят с хэндлом, а не с
     * идентификатором: сопоставить их иначе не с чем, и список не знал бы,
     * какое именно устройство подключилось.
     */
    private val соединения = java.util.concurrent.ConcurrentHashMap<Long, String>()


    /**
     * Просьбы о подключении, ждущие потока канала.
     *
     * Очередь, а не поле с одним значением: два быстрых нажатия на разные
     * устройства не должны теряться, а второе нажатие на то же отбрасывается
     * при добавлении.
     */
    private val просьбы = java.util.concurrent.ConcurrentLinkedQueue<Device>()

    @Volatile
    private var поток: Thread? = null

    @Volatile
    private var состояние: State = State.ОСТАНОВЛЕН

    /**
     * Поднимает канал, если он ещё не поднят.
     *
     * Повторный вызов ничего не делает: второй канал занял бы второй порт и
     * начал объявлять себя дважды, а список устройств показал бы дубли.
     */
    fun start() {
        if (!запущен.compareAndSet(false, true)) return

        состояние = State.ЗАПУСКАЕТСЯ
        publish(Snapshot.EMPTY.copy(running = false, problem = ""))

        val worker = Thread({ цикл() }, "braidwork-discovery")
        // Фоновый приоритет: канал не должен отбирать время у интерфейса, но и
        // пропадать ему незачем — связь рвётся вместе с засыпанием процесса.
        worker.priority = Thread.MIN_PRIORITY + 1
        поток = worker
        worker.start()
    }

    /**
     * Останавливает канал и освобождает его в ядре.
     *
     * Порядок важен: сначала сигнал потоку, потом освобождение. Иначе поток
     * успеет опросить уже освобождённый хэндл, а ядро вернёт ошибку, которую
     * придётся разбирать в журнале при каждом закрытии приложения.
     */
    fun stop() {
        if (!запущен.compareAndSet(true, false)) return

        состояние = State.ОСТАНОВЛЕН
        поток?.interrupt()
        поток = null

        // Соединения закрываются вместе с каналом: отдельного вызова на каждое
        // нет, а держать карту после остановки незачем — хэндлы уже мертвы, и
        // первый же `Connected` после перезапуска вёл бы в пустоту.
        соединения.clear()

        val h = хэндл.getAndSet(0)
        if (h != 0L) {
            runCatching { BraidworkCore.transportStop(h) }
            runCatching { BraidworkCore.transportFree(h) }
        }

        publish(Snapshot.EMPTY)
    }

    /** Текущее состояние для интерфейса. */
    fun snapshot(): Snapshot = снимки.get().copy(
        shareStats = getShareStats(),
        transfers = getActiveTransfers(),
    )

    /**
     * Просит подключиться к устройству.
     *
     * Здесь **ничего не соединяется**: только кладётся просьба в очередь, которую
     * разбирает поток канала. Причина не в аккуратности — `connect_timeout`
     * внутри ядра блокирует до трёх секунд, а нажатие приходит из интерфейса, то
     * есть с главного потока. Прямой вызов замораживал бы экран на всё время
     * ожидания, а на телефоне с чужой или недоступной сетью — навсегда.
     *
     * Повторная просьба по тому же устройству игнорируется: соединение уже
     * есть, а второе открыло бы второй сокет на тот же порт и забрало бы
     * соединение у первого.
     */
    fun connect(устройство: Device) {
        val id = устройство.deviceId.идентификатор()

        // Повторные нажатия в журнал не пишутся: это обычное дело пользователя,
        // а не событие. Пять нажатий подряд давали пять одинаковых строк и
        // забивали всё окно, из которого читают причину обрыва.
        if (соединения.values.contains(id)) return
        if (просьбы.contains(устройство)) return

        записать("просьба: подключиться к $id на порт ${устройство.port}")
        просьбы.add(устройство)
    }

    /**
     * Выполняет накопленные просьбы о подключении.
     *
     * Вызывается из потока канала, а не из интерфейса — см. [connect].
     */
    private fun выполнить_просьбы(h: Long) {
        while (true) {
            val устройство = просьбы.poll() ?: return
            val id = устройство.deviceId.идентификатор()

            try {
                val conn = BraidworkCore.transportConnect(h, устройство.ipv4, устройство.port)
                соединения[conn] = id
                connУстройства[id] = conn
                записать("соединение $conn открыто, ждём рукопожатие")
            } catch (e: Exception) {
                // Причина показывается пользователю, а не только пишется в
                // журнал: иначе нажатие выглядело бы как ничего не сделавшее,
                // а это самый обидный вид поломки — пользователь не знает, куда
                // смотреть.
                записать("подключение к $id НЕ удалось: ${e.message}")
                Log.w(TAG, "подключение к $id не удалось", e)
                publish(снимки.get().copy(problem = "не удалось подключиться: ${e.message}"))
            }
        }
    }

    /** Состояние канала. */
    fun state(): State = состояние

    /**
     * Основной цикл: подъём канала, опрос, учёт найденного.
     *
     * Падение здесь не должно ронять приложение: связь — не основа, на которой
     * держится интерфейс. Ошибка показывается в экране сети и повторяется
     * попытка, потому что сеть обычно возвращается сама.
     */
    private fun цикл() {
        while (запущен.get()) {
            val h = хэндл.get()
            if (h == 0L) {
                if (!поднять()) {
                    // Канал не поднялся. Пауза перед повтором, иначе при
                    // занятом порте получится горячий цикл, съедающий батарею
                    // и ни к чему не приводящий.
                    Thread.sleep(повтор_через_мс)
                    continue
                }
                continue
            }

            try {
                // Просьбы разбираются до опроса: подключение должно начаться
                // в тот же заход, а рукопожатие продвинется следующим опросом —
                // на принятие первого сообщения напарника нужно время.
                выполнить_просьбы(h)
                обработать(h)
            } catch (e: Exception) {
                // Отдельная попытка: исключение из одного опроса не должно
                // убивать поток, иначе одно плохое событие отключило бы поиск
                // до перезапуска приложения.
                Log.w(TAG, "опрос не удался", e)
                publish(снимки.get().copy(problem = e.message ?: "ошибка опроса"))
            }

            val пауза = if (activeTransfers.isNotEmpty()) 10L else пауза_между_опросами_мс
            Thread.sleep(пауза)
        }
    }

    /** Поднимает канал. `false` — поднять не удалось. */
    private fun поднять(): Boolean {
        return try {
            val blob = keysBlobProvider()
            val имя = имя_устройства()
            // Постоянный порт, а не любой свободный: напарник узнаёт адрес из
            // объявления, и при перезапуске сервиса произвольный порт уезжал,
            // подключение падало с «недоступно», а в журнале не было ни слова
            // о том, что у напарника просто другой порт.
            val h = BraidworkCore.transportStart(
                blob, имя, платформа(), типУстройства(), ПОРТ_ДАННЫХ,
            )
            хэндл.set(h)

            val port = BraidworkCore.transportPort(h)
            состояние = if (port != 0) State.РАБОТАЕТ else State.ОШИБКА

            записать("канал поднят, порт $port")
            publish(Snapshot(emptyList(), port, port != 0, ""))
            true
        } catch (e: Exception) {
            состояние = State.ОШИБКА
            записать("канал НЕ поднялся: ${e.message}")
            publish(Snapshot(emptyList(), 0, false, e.message ?: "канал не поднялся"))
            false
        }
    }


    /**
     * Сопоставляет хэндл соединения с устройством из списка.
     *
     * Для исходящего соединения ответ известен сразу: [выполнить_просьбы]
     * записал его при открытии. Для входящего идентификатор приходит только в
     * объявлении, и сопоставление идёт по адресу — он есть и у [TransportEvent.Incoming],
     * и у [TransportEvent.Connected].
     *
     * Найденное значение кладётся в [соединения] сразу. Это не только экономит
     * поиск: [connect] отказывает в повторном подключении к устройству, для
     * которого уже есть соединение, и без записи входящее соединение таким
     * проверкой не считалось. Нажатие на устройство открывало бы второй сокет
     * к тому же напарнику поверх первого.
     *
     * `null` означает, что устройства в списке нет — например, напарник ещё не
     * успел себя объявить. Это разбирается отдельно и не молчит.
     */
    private fun сопоставить(
        conn: Long,
        адрес: ByteArray,
        известные: Map<String, Device>,
    ): String? {
        соединения[conn]?.let { return it }

        val id = известные.values
            .firstOrNull { it.ipv4.contentEquals(адрес) }
            ?.deviceId
            ?.идентификатор()
            ?: return null

        соединения[conn] = id
        connУстройства[id] = conn
        return id
    }

    /** Обратная к [соединения] карта: устройство — его соединение. */
    private val connУстройства = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Что прислал каждый сосед последним сердцебиением. */
    private val пульсы = java.util.concurrent.ConcurrentHashMap<String, PeerPulse>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val замолчавшие = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Есть ли хоть одно подтверждённое соединение.
     *
     * Отдельная функция, чтобы условие выше читалось словами. Смысл: пока канал
     * жив, молчание объявлений не должно убирать карточку — сведения о том, что
     * канал жив, надёжнее объявления, потому что объявление идёт по UDP, а
     * соединение по TCP.
     */
    private fun соединенияПусты(): Boolean = подтверждено.isEmpty()

    /**
     * Устройства, с которыми рукопожатие **завершено**.
     *
     * Отдельное множество, а не значение в устройстве. Признак «подключено» не
     *льзя держать в строке списка: строка пересобирается из объявлений каждые две
     * секунды, и пауза в сети убрала бы устройство из снимка вместе с признаком.
     *
     * Раньше так и было: устройство, молчавшее девять секунд и вернувшееся,
     * получало `connected = false`, хотя соединение продолжало жить. Владелец
     * видел на своём телефоне «Рядом, канал не установлен», а на чужом —
     * «На связи», и прав был каждый наполовину.
     *
     * Сюда попадает только завершённое рукопожатие, а не успешный `connect`:
     * TCP-соединение ещё ничего не значит.
     */
    private val подтверждено = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** Когда по соединению последний раз уходило сердцебиение. */
    private val отправленоСердцебие = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Шлёт сердцебиение по всем живым соединениям, кому оно ещё не уходило.
     *
     * Сердцебиение должно быть повторяющимся: по нему человек видит, что канал
     * жив прямо сейчас. Одноразовое сообщение доказывает связь только в момент
     * получения, а через минуту экран показывал бы живое соединение по строке,
     * которой уже нет.
     */
    private fun пульснутьЖивые(h: Long, теперь: Long) {
        for ((conn, id) in соединения) {
            val прошло = отправленоСердцебие[conn] ?: 0L
            if (теперь - прошло < ПЕРИОД_СЕРДЦЕБИЕНИЯ_МС) continue
            отправленоСердцебие[conn] = теперь
            послатьСердцебиение(conn, безЗаписиВЖурнал = прошло != 0L)
        }
    }

    /**
     * Отправляет содержимое буфера обмена устройству по его идентификатору.
     *
     * Интерфейс знает идентификатор, а не хэндл соединения, и это правильно:
     * хэндл выдаётся ядром и меняется при каждом переподключении, а значит
     * попытка найти его в интерфейсе рано или поздно дала бы отправку в никуда.
     *
     * `null` — отправлено. Иначе причина отказа: человек должен её увидеть,
     * молчаливая неудача выглядела бы как успех.
     */
    fun послатьБуферУстройству(deviceId: String, текст: String): String? {
        if (текст.isEmpty()) {
            // Пустой буфер — это не поломка, но и не отправка. Молчать нельзя:
            // нажатие выглядело бы как ничего не сделавшее.
            записать("буфер обмена пуст — отправлять нечего")
            return "буфер обмена пуст — отправлять нечего"
        }
        val conn = connУстройства[deviceId] ?: run {
            val причина = "нет соединения с устройством"
            записать("буфер не отправлен: $причина")
            return причина
        }
        return послатьБуфер(conn, текст)
    }

    /**
     * Отправляет файл выбранному устройству.
     */
    fun отправитьФайлУстройству(deviceId: String, uri: Uri): String? {
        val h = хэндл.get()
        if (h == 0L) return "канал не поднят"
        val conn = connУстройства[deviceId] ?: return "нет соединения с устройством"
        val peerName = снимки.get().devices.firstOrNull { it.deviceId.идентификатор() == deviceId }?.name ?: deviceId

        fileTransferExecutor.execute {
            try {
                выполнитьОтправкуФайла(h, conn, deviceId, peerName, uri)
            } catch (e: Exception) {
                записать("ошибка отправки файла: ${e.message}")
                mainHandler.post {
                    Toast.makeText(context, "Ошибка отправки: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return null
    }

    private fun выполнитьОтправкуФайла(h: Long, conn: Long, peerId: String, peerName: String, uri: Uri) {
        var fileName = "file"
        var fileSize = 0L

        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx) ?: "file"
                val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0) fileSize = cursor.getLong(sizeIdx)
            }
        }
        if (fileSize <= 0L) {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                fileSize = pfd.statSize
            }
        }
        if (fileSize <= 0L) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    var count = 0L
                    val skipBuf = ByteArray(64 * 1024)
                    var r: Int
                    while (stream.read(skipBuf).also { r = it } != -1) {
                        count += r
                    }
                    if (count > 0L) fileSize = count
                }
            } catch (_: Exception) {}
        }
        if (fileSize <= 0L) {
            записать("файл пуст или недоступен: $fileName")
            mainHandler.post {
                Toast.makeText(context, "Файл пуст или недоступен", Toast.LENGTH_SHORT).show()
            }
            return
        }

        fileName = sanitizeFileName(fileName)
        val sizeStr = formatBytes(fileSize)
        записать("подготовка к отправке «$fileName» ($sizeStr)...")

        val transferId = ByteArray(16).apply { SecureRandom().nextBytes(this) }
        val tidHex = transferId.joinToString("") { "%02x".format(it) }

        val transfer = ActiveTransfer(
            transferIdHex = tidHex,
            peerId = peerId,
            peerName = peerName,
            fileName = fileName,
            totalSize = fileSize,
            totalHash = ByteArray(0),
            isIncoming = false,
            statusText = "Хэширование...",
        )
        activeTransfers[tidHex] = transfer

        var success = false
        var transferError: String? = null

        try {
            // Считаем общий BLAKE3 хэш файла
            val totalHash = context.contentResolver.openInputStream(uri)?.use { fis ->
                BraidworkCore.blake3Stream(fis)
            } ?: run {
                throw IllegalStateException("не удалось прочитать файл для хэширования")
            }

            if (transfer.cancelled) {
                throw IllegalStateException("отменено")
            }

            transfer.totalHash = totalHash
            transfer.statusText = null

            // Создаём отправитель FileSender в ядре Rust
            val sHandle = BraidworkCore.createFileSender(
                transferId = transferId,
                fileName = fileName,
                totalSize = fileSize,
                totalHash = totalHash,
                chunkSize = BraidworkCore.DEFAULT_CHUNK_SIZE,
            )
            transfer.senderHandle = sHandle
            transfer.conn = conn

            // Отправляем FileOffer
            val offerFrame = BraidworkCore.fileOfferFrame(transferId, fileName, fileSize, totalHash)
            BraidworkCore.transportSend(h, conn, offerFrame)
            записать("предложение файла «$fileName» отправлено")

            // Читаем и передаём чанки под управлением Rust FileSender
            val chunkInfo = BraidworkCore.FileSenderChunk()
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "r")
            } catch (_: Exception) {
                null
            }

            if (pfd != null) {
                pfd.use { descriptor ->
                    FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                        fun sendPendingChunks() {
                            while (BraidworkCore.fileSenderNextChunk(sHandle, chunkInfo)) {
                                if (transfer.cancelled) {
                                    throw IllegalStateException("отменено")
                                }

                                // Противодавление (backpressure): ждём, если сокет не успевает сбрасывать данные в сеть
                                var waitCount = 0
                                while (BraidworkCore.transportOutboxLen(h, conn) > 256 * 1024 && waitCount < 300) {
                                    if (transfer.cancelled) break
                                    Thread.sleep(5)
                                    waitCount++
                                }

                                val buf = java.nio.ByteBuffer.allocate(chunkInfo.length)
                                channel.position(chunkInfo.offset)
                                var readTotal = 0
                                while (readTotal < chunkInfo.length) {
                                    val r = channel.read(buf)
                                    if (r <= 0) break
                                    readTotal += r
                                }

                                if (readTotal != chunkInfo.length) {
                                    throw IllegalStateException("неполное чтение блока: прочитано $readTotal из ${chunkInfo.length}")
                                }

                                val blockFrame = BraidworkCore.fileSenderBuildBlock(sHandle, chunkInfo.offset, buf.array())
                                BraidworkCore.transportSend(h, conn, blockFrame)

                                transfer.transferredBytes = (chunkInfo.offset + readTotal).coerceAtMost(fileSize)

                                val now = System.currentTimeMillis()
                                val elapsed = now - transfer.lastCalcTime
                                if (elapsed >= 500) {
                                    val bytesDelta = transfer.transferredBytes - transfer.lastCalcBytes
                                    transfer.speedMbps = (bytesDelta * 8.0) / (elapsed * 1000.0)
                                    transfer.lastCalcBytes = transfer.transferredBytes
                                    transfer.lastCalcTime = now
                                }

                                Thread.sleep(1)
                            }
                        }

                        // Первый проход: отправка всех блоков
                        sendPendingChunks()

                        // Ожидание подтверждения завершения (Positive ACK) или запросов на досылку (Retransmit)
                        var waitLoop = 0
                        while (!transfer.done && !transfer.cancelled && waitLoop < 100) {
                            Thread.sleep(50)
                            waitLoop++
                            sendPendingChunks()
                        }
                    }
                }
            } else {
                // Потоковый fallback для URI без прямого файлового дескриптора
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    var currentStreamOffset = 0L
                    val buf = ByteArray(BraidworkCore.DEFAULT_CHUNK_SIZE)
                    while (BraidworkCore.fileSenderNextChunk(sHandle, chunkInfo)) {
                        if (transfer.cancelled) {
                            throw IllegalStateException("отменено")
                        }

                        var waitCount = 0
                        while (BraidworkCore.transportOutboxLen(h, conn) > 256 * 1024 && waitCount < 300) {
                            if (transfer.cancelled) break
                            Thread.sleep(5)
                            waitCount++
                        }

                        var toRead = chunkInfo.length
                        var offsetInBuf = 0
                        while (toRead > 0) {
                            val r = stream.read(buf, offsetInBuf, toRead)
                            if (r <= 0) break
                            offsetInBuf += r
                            toRead -= r
                        }
                        if (offsetInBuf != chunkInfo.length) {
                            throw IllegalStateException("неполное чтение блока: прочитано $offsetInBuf из ${chunkInfo.length}")
                        }
                        val blockFrame = BraidworkCore.fileSenderBuildBlock(sHandle, chunkInfo.offset, if (offsetInBuf == buf.size) buf else buf.copyOf(offsetInBuf))
                        BraidworkCore.transportSend(h, conn, blockFrame)

                        currentStreamOffset += offsetInBuf
                        transfer.transferredBytes = currentStreamOffset.coerceAtMost(fileSize)

                        val now = System.currentTimeMillis()
                        val elapsed = now - transfer.lastCalcTime
                        if (elapsed >= 500) {
                            val bytesDelta = transfer.transferredBytes - transfer.lastCalcBytes
                            transfer.speedMbps = (bytesDelta * 8.0) / (elapsed * 1000.0)
                            transfer.lastCalcBytes = transfer.transferredBytes
                            transfer.lastCalcTime = now
                        }

                        Thread.sleep(1)
                    }

                    var waitLoop = 0
                    while (!transfer.done && !transfer.cancelled && waitLoop < 100) {
                        Thread.sleep(50)
                        waitLoop++
                    }
                }
            }

            if (transfer.cancelled) {
                throw IllegalStateException("отменено")
            }

            success = true
            transfer.done = true
            bytesTransferredToday.addAndGet(transfer.totalSize)
            filesTransferredCount.incrementAndGet()
            saveStats()
            записать("файл «$fileName» ($sizeStr) отправлен на $peerName")
            mainHandler.post {
                Toast.makeText(context, "Файл «$fileName» отправлен!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            transferError = e.message ?: "сбой передачи"
            transfer.error = transferError
            записать("ошибка отправки файла «$fileName»: $transferError")
            mainHandler.post {
                Toast.makeText(context, "Ошибка отправки: $transferError", Toast.LENGTH_SHORT).show()
            }
        } finally {
            if (transfer.senderHandle != 0L) {
                try {
                    BraidworkCore.fileSenderFree(transfer.senderHandle)
                } catch (_: Exception) {}
                transfer.senderHandle = 0L
            }
            activeTransfers.remove(tidHex)
            completedTransfers.add(0, CompletedTransfer(
                fileName = fileName,
                peerName = peerName,
                totalSize = fileSize,
                isIncoming = false,
                success = success,
                error = if (!success) (transferError ?: "сбой передачи") else null,
            ))
            if (completedTransfers.size > 20) completedTransfers.removeAt(completedTransfers.size - 1)
        }
    }

    /** Есть ли текущие активные передачи файлов. */
    fun hasActiveTransfers(): Boolean = activeTransfers.values.any { !it.done && !it.cancelled }

    /** Сводная статистика для экрана «Обмен». */
    fun getShareStats(): ShareStats {
        val clipCount = полученное.size + sentClipboardCount.get()
        val fileCount = filesTransferredCount.get()
        val activeList = activeTransfers.values.filter { !it.cancelled }
        val activeCount = activeList.count { !it.done }
        val totalBytes = bytesTransferredToday.get() + activeList.sumOf { it.transferredBytes }

        val bytesText = if (totalBytes == 0L) "0 МБ" else formatBytes(totalBytes)

        // 1. Оранжевое кольцо (Буфер): 5 записей = 100%
        val clipProg = if (clipCount > 0) (clipCount / 5f).coerceIn(0.15f, 1f) else 0f

        // 2. Зелёное кольцо (Файлы): прогресс по количеству файлов (до 5) или объёму (до 25 МБ)
        val fileProgByCount = (fileCount / 5f).coerceIn(0f, 1f)
        val fileProgByBytes = if (totalBytes > 0) (totalBytes.toFloat() / (25 * 1024 * 1024f)).coerceIn(0f, 1f) else 0f
        val fileProg = if (fileCount > 0 || totalBytes > 0) {
            maxOf(fileProgByCount, fileProgByBytes).coerceIn(0.18f, 1f)
        } else 0f

        // 3. Голубое кольцо (Очередь / Активность)
        val qProg = if (activeCount > 0) {
            val valid = activeList.filter { !it.done && it.totalSize > 0 }
            if (valid.isNotEmpty()) {
                val avg = valid.map { it.transferredBytes.toFloat() / it.totalSize }.average().toFloat()
                avg.coerceIn(0.1f, 1f)
            } else 0.25f
        } else if (fileCount > 0 || clipCount > 0) {
            ((clipProg + fileProg) / 2f).coerceIn(0.2f, 1f)
        } else 0f

        return ShareStats(
            todayBytesText = bytesText,
            clipboardCount = clipCount,
            filesCount = fileCount,
            notifsCount = 0,
            queueCount = activeCount,
            clipboardProgress = clipProg,
            filesProgress = fileProg,
            queueProgress = qProg,
        )
    }

    /** Список активных и недавних передач для экрана «Файлы». */
    fun getActiveTransfers(): List<Transfer> {
        val active = activeTransfers.values.filter { !it.cancelled }.map { at ->
            val fraction = if (at.totalSize > 0) (at.transferredBytes.toFloat() / at.totalSize).coerceIn(0f, 1f) else 0f
            val sentMb = formatBytes(at.transferredBytes)
            val totalMb = formatBytes(at.totalSize)
            val remainingBytes = (at.totalSize - at.transferredBytes).coerceAtLeast(0)
            val leftSec = if (at.speedMbps > 0.05) ((remainingBytes * 8) / (at.speedMbps * 1_000_000.0)).toLong() else 0L
            val leftStr = if (leftSec > 60) "${leftSec / 60} мин" else if (leftSec > 0) "$leftSec с" else "..."
            Transfer(
                name = at.fileName,
                target = if (at.isIncoming) "от ${at.peerName}" else "на ${at.peerName}",
                progress = fraction,
                speedMbps = Math.round(at.speedMbps * 10.0) / 10.0,
                leftText = if (at.done) "Готово" else if (at.error != null) "Ошибка: ${at.error}" else if (at.statusText != null) at.statusText!! else "Осталось $leftStr",
                sentText = "$sentMb из $totalMb",
            )
        }
        val finished = completedTransfers.take(10).map { ct ->
            val sizeStr = formatBytes(ct.totalSize)
            Transfer(
                name = ct.fileName,
                target = if (ct.isIncoming) "от ${ct.peerName}" else "на ${ct.peerName}",
                progress = if (ct.success) 1f else 0f,
                speedMbps = 0.0,
                leftText = if (ct.success) "Готово" else "Ошибка: ${ct.error ?: "сбой"}",
                sentText = "$sizeStr из $sizeStr",
            )
        }
        return active + finished
    }

    /** Отменяет передачу файла. */
    fun cancelTransfer(name: String) {
        val t = activeTransfers.values.firstOrNull { it.fileName == name }
        if (t != null) {
            t.cancelled = true
            try {
                t.raf?.close()
            } catch (_: Exception) {}
            t.raf = null
            if (t.receiverHandle != 0L) {
                try {
                    BraidworkCore.fileReceiverFree(t.receiverHandle)
                } catch (_: Exception) {}
                t.receiverHandle = 0L
            }
            if (t.senderHandle != 0L) {
                try {
                    BraidworkCore.fileSenderFree(t.senderHandle)
                } catch (_: Exception) {}
                t.senderHandle = 0L
            }
            t.tempFile?.delete()
            t.stateFile?.delete()
            activeTransfers.remove(t.transferIdHex)
            completedTransfers.add(0, CompletedTransfer(
                fileName = t.fileName,
                peerName = t.peerName,
                totalSize = t.totalSize,
                isIncoming = t.isIncoming,
                success = false,
                error = "отменено пользователем",
            ))
            if (completedTransfers.size > 20) completedTransfers.removeAt(completedTransfers.size - 1)
            записать("передача ${t.fileName} отменена")
        }
    }

    /** Занятое место в папке Загрузки/Braidwork. */
    fun getStorageUsageText(): String {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Braidwork")
        if (!dir.exists() || !dir.isDirectory) return "0 МБ на диске"
        val totalBytes = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
        return "${formatBytes(totalBytes)} на диске"
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 МБ"
        if (bytes < 1024L) return "$bytes Б"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return String.format(Locale.US, "%.1f КБ", kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return String.format(Locale.US, "%.1f МБ", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2f ГБ", gb)
    }

    private fun findUniqueFile(dir: File, baseName: String): File {
        val file = File(dir, baseName)
        if (!file.exists()) return file
        val dotIndex = baseName.lastIndexOf('.')
        val namePart = if (dotIndex > 0) baseName.substring(0, dotIndex) else baseName
        val extPart = if (dotIndex > 0) baseName.substring(dotIndex) else ""
        var counter = 1
        while (true) {
            val candidate = File(dir, "$namePart ($counter)$extPart")
            if (!candidate.exists()) return candidate
            counter++
        }
    }

    /** Один опрос: забираем события и обновляем список. */
    private fun обработать(h: Long) {
        val события = BraidworkCore.transportPoll(h, максимум_событий, запас_под_кадры)

        val прежние = LinkedHashMap<String, Device>()
        for (d in снимки.get().devices) прежние[d.deviceId.идентификатор()] = d

        val теперь = System.currentTimeMillis()
        var порт = снимки.get().port
        var живой = снимки.get().running
        var проблема = снимки.get().problem

        for (событие in события) {
            when (событие) {
                is BraidworkCore.TransportEvent.Discovered -> {
                    val id = событие.deviceId
                    // Соединение не сбрасывается на каждом объявлении: оно
                    // переживает паузу в сети и обрывается только по событию
                    // FL_EV_CLOSED. Иначе плитка мигала бы «подключено» и
                    // «не подключено» каждые две секунды.
                    val было = прежние[id.идентификатор()]
                    прежние[id.идентификатор()] = Device(
                        deviceId = id,
                        port = событие.port,
                        name = событие.name,
                        ipv4 = событие.ipv4,
                        // Отпечаток выведен из ключа, а не назван, поэтому он не
                        // меняется от объявления к объявлению. Пустая строка —
                        // ядро у напарника старше, и показывать нечего.
                        fingerprint = событие.fingerprint.ifEmpty {
                            было?.fingerprint.orEmpty()
                        },
                        // Платформа и версия приходят из того же объявления и
                        // меняться не могут, но на всякий случай берётся прежнее
                        // значение, если новое пустое: краткие объявления обрезаются
                        // целиком.
                        platform = событие.platform.ifEmpty { было?.platform.orEmpty() },
                        coreVersion = событие.coreVersion.ifEmpty { было?.coreVersion.orEmpty() },
                        // Тип устройства — то же объявление, та же осторожность:
                        // пустое новое значение не затирает прежнее, потому что
                        // краткое объявление могло обрезаться целиком.
                        deviceKind = событие.deviceKind.ifEmpty { было?.deviceKind.orEmpty() },
                        connected = подтверждено.contains(id.идентификатор()),
                        lastSeenMs = теперь,
                    )
                    замолчавшие.remove(id.идентификатор())
                    if (!живой) {
                        живой = true
                        проблема = ""
                    }
                }

                // События соединения несут хэндл соединения, а не идентификатор
                // устройства. Разбор идёт через карту, которую наполняет
                // [connect] для исходящих и обработчик `Incoming` для входящих.
                is BraidworkCore.TransportEvent.Connected -> {
                    val id = сопоставить(событие.conn, событие.ipv4, прежние)
                    if (id == null) {
                        // Канал поднят, а с кем — непонятно. Раньше этот случай
                        // просто пропускался, и в журнале не оставалось ничего:
                        // ни «рукопожатие прошло», ни объяснения, почему молчок.
                        записать(
                            "рукопожатие прошло с ${событие.ipv4.адрес()}, " +
                                "но устройство не опознано — напарник не объявлялся"
                        )
                        continue
                    }
                    записать("рукопожатие прошло с $id на ${событие.ipv4.адрес()}")
                    прежние[id]?.let { прежние[id] = it.copy(connected = true) }
                    подтверждено.add(id)
                    // Сердцебиение уходит сразу по рукопожатии: канал только что
                    // стал готов, и ждать нажатия кнопки — значит оставить его
                    // пустым. Это первое, что реально уходит по этому каналу, и
                    // у напарника появится настоящая запись в журнале вместо
                    // молчания, по которому непонятно, дошло ли что-нибудь.
                    послатьСердцебиение(событие.conn)
                }

                // Входящее соединение — вторая половина картины. Раньше оно
                // молчало, и на втором телефоне журнал выглядел пустым, хотя
                // именно там видно, кто инициировал. Хуже молчания было то, что
                // хэндл так и не сопоставлялся с устройством: соединение
                // доходило до рукопожатия и тихо пропадало.
                is BraidworkCore.TransportEvent.Incoming -> {
                    val id = сопоставить(событие.conn, событие.ipv4, прежние)
                    записать(
                        "входящее соединение от ${событие.ipv4.адрес()}" +
                            (id?.let { " — это $it" } ?: " — устройство в списке не значится")
                    )
                }

                is BraidworkCore.TransportEvent.Closed -> {
                    val id = соединения.remove(событие.conn)
                    отправленоСердцебие.remove(событие.conn)
                    if (id != null) {
                        val другоеConn = соединения.entries.firstOrNull { it.value == id }?.key
                        if (другоеConn != null) {
                            connУстройства[id] = другоеConn
                        } else {
                            connУстройства.remove(id)
                            пульсы.remove(id)
                            подтверждено.remove(id)
                            замолчавшие.remove(id)
                        }
                    }
                    if (id == null) {
                        // Соединение, которое так и не сопоставили. Причину всё
                        // равно показываем: она объясняет, что произошло, даже
                        // если устройство в списке не значилось.
                        записать(
                            "соединение с ${событие.ipv4.адрес()} закрыто: " +
                                "${событие.reasonText()} — устройство не опознано"
                        )
                        continue
                    }
                    // Причина обязательна: «соединение закрыто» ничего не
                    // объясняло, а именно это и нужно было выяснить. Различие
                    // между «напарник закрыл» и «обрыв сети» и есть ответ.
                    записать("соединение $id закрыто: ${событие.reasonText()}, ${событие.ipv4.адрес()}")
                    прежние[id]?.let { прежнее -> прежние[id] = прежнее.copy(connected = false) }
                }

                is BraidworkCore.TransportEvent.State ->
                    // Объявление упало, сети нет: список обязан опустеть, иначе
                    // пользователь увидит устройство, которого уже не существует.
                    if (событие.value == BraidworkCore.STATE_FAILED) {
                        проблема = "сеть недоступна"
                    }

                is BraidworkCore.TransportEvent.Notice ->
                    проблема = "канал: ${событие.error}"

                // Принятые кадры. Раньше здесь стояло `Unit`, то есть ядро
                // расшифровывало кадр, отдавало клиенту, а клиент его выбрасывал:
                // канал поднимался, оставался пустым, и по журналу нельзя было
                // сказать, дошло ли что-нибудь.
                is BraidworkCore.TransportEvent.Frame ->
                    разобратьКадр(событие, соединения, прежние)

                else -> Unit
            }
        }

        // Убираем то, что замолчало. Проверка по времени, а не по отсутствию в
        // порции: объявления приходят пачками и с пропусками, и очистка по
        // одной порции выбрасывала бы устройства из списка каждые две секунды.
        // `filter` отдаёт `List`, а не изменяемый список: сортировать его на
        // месте нельзя, и попытка молча ничего бы не изменила. Порядок задаётся
        // явно, и не «как попало», а от свежих к старым: устройство, которое
        // только что объявилось, должно быть первым.
        // Устройство живёт, пока есть хоть один живой признак: объявление ИЛИ
        // подтверждённое соединение.
        //
        // Раньше список держался только на объявлениях, и пауза в девять секунд
        // убирала устройство даже при живом соединении. Владелец видел, как
        // карточка пропадала на минуты и возвращалась, и на втором телефоне в это
        // время значилось «На связи». Соединение живо, а карточки нет — хуже
        // неправды.
        val живые = прежние.values
            .filter {
                val id = it.deviceId.идентификатор()
                теперь - it.lastSeenMs < исчезает_через_мс ||
                    (подтверждено.contains(id) && !соединенияПусты())
            }
            .sortedByDescending { it.lastSeenMs }

        // Пропуск объявлений — самая частая причина того, что стороны перестают
        // видеть друг друга, и по журналу раньше не оставалось ничего: устройство
        // просто исчезало из списка, как будто его и не было.
        //
        // Причина здесь не всегда в сети — телефон может уснуть на девять секунд,
        // а система может отобрать права на широковещание. Что именно — покажет
        // строка в журнале.
        for (пропавший in прежние.values) {
            val id = пропавший.deviceId.идентификатор()
            if (теперь - пропавший.lastSeenMs >= исчезает_через_мс) {
                if (замолчавшие.add(id)) {
                    val жил = подтверждено.contains(id)
                    записать(
                        "$id замолчал" +
                            if (жил) {
                                ", соединение ещё живо, карточка остаётся"
                            } else {
                                ", устройство убрано из списка"
                            }
                    )
                }
            } else {
                if (замолчавшие.remove(id)) {
                    записать("$id снова на связи")
                }
            }
        }

        if (!живой && проблема.isEmpty() && снимки.get().running) {
            // Канал жив, но ничего не найдено — это не поломка.
            проблема = ""
        }

        // Сердцебиение уходит здесь, а не в ветке события `Connected`: там оно
        // было бы одноразовым, и точка на карточке погасла бы через несколько
        // секунд, пока связь жива.
        пульснутьЖивые(h, теперь)

        publish(Snapshot(живые, порт, живой, проблема, пульсы.toMap()))
    }

    private fun publish(s: Snapshot) {
        снимки.set(s)
    }

    /**
     * Имя устройства для объявления.
     *
     * Берётся из системы: `Build.MODEL` — это и есть модель, которую
     * пользователь и так видит в настройках. Выдуманное имя было бы враньём, а
     * в общей квартире, где рядом лежит чужой телефон, различать их нечем.
     *
     * Производитель добавляется только если его ещё нет в модели: на Xiaomi
     * `MODEL` иногда уже «Xiaomi …», и тогда получилось бы «Xiaomi Xiaomi …».
     *
     * Обрезка до 48 символов — с запасом для длинных моделей вроде
     * «Redmi Note 12 4D». Ядро обрезает ещё раз, до своих 64 байт, но
     * обрезанный посреди слова идентификатор в списке не впечатляет.
     */
    private fun имя_устройства(): String {
        val модель = Build.MODEL?.trim().orEmpty()
        val фирма = Build.MANUFACTURER?.trim().orEmpty()

        val основа = when {
            модель.isEmpty() -> фирма
            фирма.isEmpty() -> модель
            // Регистр не сравнивается: «xiaomi» и «Xiaomi» — одна и та же
            // фирма, а в списке оба написания выглядели бы как два устройства.
            модель.startsWith(фирма, ignoreCase = true) -> модель
            else -> "$фирма $модель"
        }

        val с_запасом = основа.ifEmpty { "Устройство" }
        return с_запасом.take(48)
    }

    /**
     * Своя платформа для объявления: «Android 13».
     *
     * Идёт в объявление без подписи и показывается на карточке напарника.
     * Точность здесь не нужна: строку на чужом телефоне читают глазами, а
     * сверять по ней нечем.
     *
     * Пустой результат законен и не подменяется: ядро о системе ничего не
     * знает, и выдумывать «неизвестно» от его имени было бы враньём.
     */
    private fun платформа(): String {
        val релиз = Build.VERSION.RELEASE?.trim().orEmpty()
        if (релиз.isEmpty()) return ""
        return "Android $релиз".take(48)
    }

    /**
     * Свой тип устройства для объявления.
     *
     * ## Почему объявляем мы, а не сосед
     *
     * Получатель видит адрес и четыре байта порта в UDP-пакете. Ни размер экрана
     * соседа, ни наличие у него клавиатуры отсюда не восстанавливаются — есть
     * только враньё, правдоподобное ровно настолько, чтобы человек ему поверил.
     * Тип знает лишь тот, у кого есть своё окно, то есть мы.
     *
     * ## Откуда берётся
     *
     * `smallestScreenWidthDp` — наименьшая ширина экрана в dp, заданная
     * устройством. Граница 600 dp официальная: ниже неё телефон, выше или
     * равно — всё, что шире. На устройстве без ресурсов настройки значение
     * равно нулю, и это честный ответ «не знаю».
     *
     * ## Что при неизвестном
     *
     * Пустая строка. На карточке у соседа будет «неизвестно», и это правда.
     * Значение по умолчанию — ноутбук — обошлось бы правдоподобно, и человек
     * принял бы значок за показание.
     */
    private fun типУстройства(): String {
        val ширина = context.resources.configuration.smallestScreenWidthDp
        return when {
            ширина <= 0 -> ""
            ширина >= ПОРОГ_ПЛАНШЕТА_DP -> "tablet"
            else -> "phone"
        }
    }

    private companion object {
        const val TAG = "BraidworkDiscovery"

        /**
         * Типы сообщений.
         *
         * Значения повторяют `MessageKind` в схеме протокола. Расхождение не даёт
         * ошибки сборки, а тихо превратит каждое сообщение в «непонятно» — поэтому
         * число взято один раз и стоит рядом со схемой, а не в коде разбора.
         */
        const val KIND_HEARTBEAT = 16
        const val KIND_CLIPBOARD_CONTENT = 34
        const val KIND_FILE_OFFER = 35
        const val KIND_FILE_BLOCK = 36
        const val KIND_FILE_REQUEST = 37

        /**
         * Граница «телефон или планшет» в dp.
         *
         * Официальная граница Android: `smallestScreenWidthDp` от 600 означает
         * всё, что шире телефона. Число стоит здесь, рядом с местом, где решается,
         * чтобы не искать его потом по всему коду.
         */
        const val ПОРОГ_ПЛАНШЕТА_DP = 600

        /** Сколько шагов подключения хранить. Глубже бесполезно: событий столько же. */
        const val ГЛУБИНА_СЛЕДА = 8

        /**
         * Сколько присланных записей буфера держать.
         *
         * Больше не нужно: вставить требуется что-то недавнее, а всё, что пришло,
         * остаётся в журнале подключения — там ничего не пропадает.
         */
        const val ГЛУБИНА_ПОЛУЧЕННОГО = 20

        /**
         * Как часто уходит сердцебиение по живому соединению.
         *
         * Пять секунд: достаточно быстро, чтобы человек считал связь живой, и
         * достаточно редко, чтобы не жечь заряд на слабом телефоне. Сообщение
         * крошечное, но оно запечатывается в Noise и уходит отдельным пакетом, а
         * их число на слабой сети не бесплатно.
         */
        const val ПЕРИОД_СЕРДЦЕБИЕНИЯ_МС = 5_000L

        /**
         * После скольких миллисекунд без сердцебиения связь считается умершей.
         *
         * Втрое больше периода: одно пропущенное сообщение при обычном
         * задержании сети не должно гасить точку — иначе она мигала бы без
         * причины и человек решил бы, что приложение сбоит.
         */
        const val СВЕЖЕСТЬ_СЕРДЦЕБИЕНИЯ_МС = 3 * ПЕРИОД_СЕРДЦЕБИЕНИЯ_МС

        /** Порт приёма соединений. Постоянный — см. `поднять()`. */
        const val ПОРТ_ДАННЫХ = 42424

        /**
         * Событий за один опрос.
         *
         * Больше брать незачем: каждое событие — это запись в копию списка,
         * а сеть в доме даёт десяток устройств, не больше.
         */
        const val максимум_событий = 64

        /**
         * Запас под кадры в одной порции.
         * 512 КиБ — вмещает порцию блоков файла для высокой скорости передачи.
         */
        const val запас_под_кадры = 512 * 1024

        /** Пауза между опросами: объявления идут каждые две секунды. */
        const val пауза_между_опросами_мс = 500L

        /** Повтор подъёма канала после неудачи. */
        const val повтор_через_мс = 5000L

        /**
         * Очищает входящее имя файла от путей, недопустимых символов и попыток Path Traversal.
         */
        fun sanitizeFileName(raw: String): String {
            // 1. Оставляем только имя файла после последнего слэша (любого типа)
            var name = raw.replace('\\', '/').substringAfterLast('/')
            // 2. Убираем управляющие символы и байты 0x00-0x1F, 0x7F
            name = name.replace(Regex("[\\x00-\\x1F\\x7F]"), "")
            // 3. Заменяем спецсимволы файловых систем (< > : " / \ | ? *)
            name = name.replace(Regex("[<>:\"/\\\\|?*]"), "_")
            // 4. Убираем пробелы и замыкающие точки
            name = name.trim().trimEnd('.')
            // 5. Защита от относительных путей, пустых строк и системных имён
            if (name.isEmpty() || name == "." || name == "..") {
                name = "received_file"
            }
            // 6. Ограничение длины имени файла (до 200 символов с сохранением расширения)
            if (name.length > 200) {
                val dot = name.lastIndexOf('.')
                if (dot > 0 && dot < name.length - 1) {
                    val base = name.substring(0, dot).take(190)
                    val ext = name.substring(dot + 1).take(9)
                    name = "$base.$ext"
                } else {
                    name = name.take(200)
                }
            }
            return name
        }
    }
}

/**
 * Адрес в виде строки для журнала и показа.
 *
 * Четыре байта в сетевом порядке, как их отдаёт ядро. Знак байта приводится
 * явно: `toInt() and 0xFF` вместо `toInt()`, иначе адрес 192.168.3.140
 * напечатался бы как «-64.-88.-3.140».
 */
fun ByteArray.адрес(): String =
    if (size == 4) joinToString(".") { (it.toInt() and 0xFF).toString() } else "?"

/**
 * Идентификатор устройства в том виде, в каком он приходит из ядра.
 *
 * Идентификатор — это base32-**текст**: 26 символов верхнего регистра и цифр.
 * Раньше он приходил обрезанным до 16 байт, а здесь переводился в
 * шестнадцатеричный вид: получалось 64 символа, из которых читалась ничего
 * не значившая каша, а рядом с отпечатком ключа два числа об одном
 * устройстве выглядели независимыми.
 *
 * Теперь это просто текст без преобразования: сравнивать и показывать можно
 * как есть, а кодировать надо там, где кодируют, — в ядре.
 *
 * Нули в хвосте — заполнение фиксированного поля в структуре события, а не
 * часть идентификатора. Отрезаются: иначе на конце каждого идентификатора
 * в списке и в журнале висели бы нули.
 */
fun ByteArray.идентификатор(): String =
    toString(Charsets.UTF_8).trimEnd(' ', Char(0))

/**
 * Начало текста для журнала, чтобы он не занимал пол-экрана.
 *
 * Обрезание здесь не о декорации. В журнале может оказаться пароль или ключ от
 * чего-то, и показывать его целиком — значит оставить след на экране и в списке
 * недавних приложений. Человеку хватает начала, чтобы узнать, пришло ли то
 * самое; полный текст он увидит после явного действия «вставить».
 *
 * Многоточие в конце обязательно: без него обрезанная строка выглядит целой, и
 * человек подумал бы, что сосед прислал именно столько.
 */
fun String.кратко(предел: Int = 40): String =
    if (length <= предел) replace('\n', ' ') else {
        take(предел).replace('\n', ' ') + "… (${length} символов)"
    }

