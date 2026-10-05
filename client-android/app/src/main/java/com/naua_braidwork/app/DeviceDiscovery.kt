package com.naua_braidwork.app

import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 * ## Чего здесь нет
 *
 * Сопряжения и обмена сообщениями. Канал их умеет — рукопожатие и кадры
 * проходят внутри ядра, — но клиент пока только смотрит, кто рядом. Второй
 * путь к рукопожатию из клиента разошёлся бы с первым, а рукопожатие в
 * `fl_session_*` намеренно оставлено для настольных клиентов.
 */
class DeviceDiscovery(private val keysBlobProvider: () -> ByteArray) {

    /** Что видно пользователю. */
    data class Device(
        val deviceId: ByteArray,
        val port: Int,
        /** Когда видели в последний раз, миллисекунды системного времени. */
        val lastSeenMs: Long,
    ) {
        override fun equals(other: Any?) =
            other is Device && deviceId.contentEquals(other.deviceId)

        override fun hashCode() = deviceId.contentHashCode()
    }

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
    ) {
        companion object {
            val EMPTY = Snapshot(emptyList(), 0, false, "")
        }
    }

    /** Что происходит с каналом. */
    enum class State { ОСТАНОВЛЕН, ЗАПУСКАЕТСЯ, РАБОТАЕТ, ОШИБКА }

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

        val h = хэндл.getAndSet(0)
        if (h != 0L) {
            runCatching { BraidworkCore.transportStop(h) }
            runCatching { BraidworkCore.transportFree(h) }
        }

        publish(Snapshot.EMPTY)
    }

    /** Текущее состояние для интерфейса. */
    fun snapshot(): Snapshot = снимки.get()

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
                обработать(h)
            } catch (e: Exception) {
                // Отдельная попытка: исключение из одного опроса не должно
                // убивать поток, иначе одно плохое событие отключило бы поиск
                // до перезапуска приложения.
                Log.w(TAG, "опрос не удался", e)
                publish(снимки.get().copy(problem = e.message ?: "ошибка опроса"))
            }

            Thread.sleep(пауза_между_опросами_мс)
        }
    }

    /** Поднимает канал. `false` — поднять не удалось. */
    private fun поднять(): Boolean {
        return try {
            val blob = keysBlobProvider()
            val имя = имя_устройства()
            val h = BraidworkCore.transportStart(blob, имя, 0)
            хэндл.set(h)

            val port = BraidworkCore.transportPort(h)
            состояние = if (port != 0) State.РАБОТАЕТ else State.ОШИБКА

            publish(Snapshot(emptyList(), port, port != 0, ""))
            true
        } catch (e: Exception) {
            состояние = State.ОШИБКА
            publish(Snapshot(emptyList(), 0, false, e.message ?: "канал не поднялся"))
            false
        }
    }

    /** Один опрос: забираем события и обновляем список. */
    private fun обработать(h: Long) {
        val события = BraidworkCore.transportPoll(h, максимум_событий, запас_под_кадры)

        val прежние = LinkedHashMap<String, Device>()
        for (d in снимки.get().devices) прежние[d.deviceId.toHex()] = d

        val теперь = System.currentTimeMillis()
        var порт = снимки.get().port
        var живой = снимки.get().running
        var проблема = снимки.get().problem

        for (событие in события) {
            when (событие) {
                is BraidworkCore.TransportEvent.Discovered -> {
                    val id = событие.deviceId
                    прежние[id.toHex()] = Device(id, событие.port, теперь)
                    if (!живой) {
                        живой = true
                        проблема = ""
                    }
                }

                is BraidworkCore.TransportEvent.State ->
                    // Объявление упало, сети нет: список обязан опустеть, иначе
                    // пользователь увидит устройство, которого уже не существует.
                    if (событие.value == BraidworkCore.STATE_FAILED) {
                        проблема = "сеть недоступна"
                    }

                is BraidworkCore.TransportEvent.Notice ->
                    проблема = "канал: ${событие.error}"

                is BraidworkCore.TransportEvent.Frame -> Unit

                else -> Unit
            }
        }

        // Убираем то, что замолчало. Проверка по времени, а не по отсутствию в
        // порции: объявления приходят пачками и с пропусками, и очистка по
        // одной порции выбрасывала бы устройства из списка каждые две секунды.
        val живые = прежние.values.filter { теперь - it.lastSeenMs < исчезает_через_мс }
        живые.sortBy { it.lastSeenMs }

        if (!живой && проблема.isEmpty() && снимки.get().running) {
            // Канал жив, но ничего не найдено — это не поломка.
            проблема = ""
        }

        publish(Snapshot(живые, порт, живой, проблема))
    }

    private fun publish(s: Snapshot) {
        снимки.set(s)
    }

    /**
     * Имя устройства для объявления.
     *
     * Пока берётся модель из системы: выдуманное имя в списке у другого
     * устройства выглядело бы как настоящее, а это ровно то, чему продукт не
     * учит. Своё имя пользователь задаст позже, и оно перекроет это значение.
     *
     * Модель обрезается: в объявление уходит UTF-8, а длинная строка съела бы
     * место, которого в пакете мало. 48 байт хватает любой модели, а
     * «Xiaomi 12T» пользователю узнаваемо.
     */
    private fun имя_устройства(): String {
        val модель = Build.MODEL?.trim().orEmpty()
        val основа = if (модель.isEmpty()) "Устройство" else модель
        return основа.take(48)
    }

    private companion object {
        const val TAG = "BraidworkDiscovery"

        /**
         * Событий за один опрос.
         *
         * Больше брать незачем: каждое событие — это запись в копию списка,
         * а сеть в доме даёт десяток устройств, не больше.
         */
        const val максимум_событий = 32

        /**
         * Запас под кадры в одной порции.
         *
         * Объявление занимает меньше сотни байт, и на первом шаге кадров в
         * порции нет вовсе. Запас нужен на будущее, когда через канал пойдут
         * данные, и нехватка означала бы потерю кадра без предупреждения.
         */
        const val запас_под_кадры = 64 * 1024

        /** Пауза между опросами: объявления идут каждые две секунды. */
        const val пауза_между_опросами_мс = 500L

        /** Повтор подъёма канала после неудачи. */
        const val повтор_через_мс = 5000L
    }
}

/** Идентификатор в виде строки для показа пользователю и сравнения. */
fun ByteArray.toHex(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append("0123456789ABCDEF"[v ushr 4])
        out.append("0123456789ABCDEF"[v and 0x0F])
    }
    return out.toString()
}
