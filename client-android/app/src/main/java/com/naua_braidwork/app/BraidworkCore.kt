package com.naua_braidwork.app

/**
 * Мост к ядру.
 *
 * Ядро собрано как cdylib и вызывается напрямую через JNI. Промежуточный слой
 * нужен по трём причинам.
 *
 * Первая — паники. При `FL_ERR_PANIC` состояние потеряно, и продолжать работу
 * молча нельзя. Здесь это превращается в исключение с понятным текстом.
 *
 * Вторая — UTF-8. Ядро всегда отдаёт UTF-8 с длиной, поэтому размер байтов
 * известен и нулевой терминатор не нужен.
 *
 * Третья — буферы. Ядро требует буфер известного размера, а здесь размеры
 * живут в одной строке, а не размазаны по вызовам.
 *
 * Что здесь есть: идентичность устройства, проверка подписи, сборка кадра и
 * канал локальной сети. Сессии из клиента пока не зовутся — ими пользуется сам
 * транспорт, и второй путь к тому же рукопожатию разошёлся бы с первым.
 *
 * Статусы ядра: 0 — успех, 1 — неверный аргумент, 2 — не хватило места,
 * 3 — криптография, 4 — не доверяем, 6 — канал не готов, 8 — паника ядра,
 * 9 — хранилище.
 */
object BraidworkCore {

    /** Размер буфера публичных ключей, который заполняет ядро. */
    const val PUBLIC_KEYS_LEN = 96

    /**
     * Размер файла ключей: 8 байт заголовка, 16 байт контрольной суммы и три
     * секрета по 32.
     *
     * Значение повторяет константу ядра и проверяется тестом на устройстве: если
     * ядро и клиент разойдутся, файл будет отвергнут как чужой, и это лучше,
     * чем тихо прочитать не то.
     */
    const val PUBLIC_KEYS_BLOB_LEN = 120

    /** Размер буфера под идентификатор устройства. */
    const val DEVICE_ID_MAX = 32

    const val STATUS_OK = 0
    const val STATUS_INVALID_ARG = 1
    const val STATUS_NO_MEMORY = 2
    const val STATUS_CRYPTO = 3
    const val STATUS_NOT_TRUSTED = 4

    /**
     * Канал не в состоянии принять: рукопожатие не завершено, соединения нет.
     *
     * Отдельное значение, а не «ошибка», потому что человек ничего не может с
     * ним сделать: нужно просто дождаться соединения. Показывать ему такую же
     * ошибку, как при обрыве сокета, значит заставить переподключаться зря.
     */
    const val STATUS_TRANSPORT = 6

    const val STATUS_PANIC = 8
    const val STATUS_STORAGE = 9

    /** Буфер под кадр `Heartbeat`. Обязан совпадать с `FL_HEARTBEAT_FRAME_MAX`. */
    const val HEARTBEAT_FRAME_MAX = 256

    /** Предел содержимого буфера обмена. Обязан совпадать с `FL_CLIPBOARD_MAX`. */
    const val CLIPBOARD_MAX = 65536

    /** Предел размера блока файла — 256 КиБ по PROTOCOL.md. */
    const val FILE_BLOCK_MAX = 256 * 1024

    /** Буфер под кадр блока файла. */
    const val FILE_BLOCK_FRAME_MAX = FILE_BLOCK_MAX + 1024

    /** Буфер под кадр предложения файла. */
    const val FILE_OFFER_FRAME_MAX = 4096

    /** Длина хэша BLAKE3. */
    const val HASH_LEN = 32

    /**
     * Буфер под тип содержимого, например `text/plain`.
     *
     * Типы содержимого задаются системой, а не приложением, и длиннее
     * `text/plain; charset=utf-8` не бывает. 64 байта — с запасом на этот
     * параметр; обрезанный тип был бы «неизвестным», и получатель не понял бы,
     * как с содержимым.
     */
    const val MIME_MAX = 64

    // =====================================================================
    // Типы событий транспорта
    //
    // Значения повторяют константы ядра и потому лежат рядом с ними в одном
    // файле: разойтись они могут только при правке ядра, и тогда сверка на CI
    // (заголовок против библиотеки) не заметит, потому что она смотрит на
    // имена функций, а не на числа внутри тела. Числа поэтому дублируются
    // сознательно, с проверкой в тестах клиента.
    // =====================================================================

    const val EV_STATE = 0
    const val EV_DISCOVERED = 1
    const val EV_INCOMING = 2
    const val EV_CONNECTED = 3
    const val EV_FRAME = 4
    const val EV_CLOSED = 5
    const val EV_NOTICE = 6

    /** Состояния транспорта для [TransportEvent.State]. */
    const val STATE_IDLE = 0
    const val STATE_ANNOUNCING = 1
    const val STATE_READY = 2
    const val STATE_STOPPING = 3
    const val STATE_FAILED = 4

    /** Причины разрыва соединения. Значения совпадают с CloseReason в ядре. */
    const val CLOSE_PEER = 1
    const val CLOSE_NETWORK = 2
    const val CLOSE_HANDSHAKE = 3
    const val CLOSE_BAD_FRAME = 4
    const val CLOSE_BY_REQUEST = 5

    /**
     * Длина идентификатора устройства в байтах.
     *
     * 32, а не 16: идентификатор занимает 26 символов base32, и при 16 он
     * **обрезался на полпути**. Клиент получал первую половину и показывал её
     * как идентификатор. Значение обязано совпадать с `DEVICE_ID_BYTES` в ядре —
     * расхождение здесь не даёт ошибки сборки, а тихо портит данные.
     */
    const val DEVICE_ID_BYTES = 32

    /** Размер буфера под отпечаток. Обязан совпадать с FL_FINGERPRINT_MAX. */
    const val FINGERPRINT_MAX = 32

    /**
     * Запас на заголовок кадра: 10 байт плюс запись varint на длину.
     *
     * Тело уже готово, поэтому длина известна, и её запись в protobuf занимает
     * мало места. Запас нужен, чтобы клиент собрал кадр целиком: обрезанный кадр
     * выглядел бы как отправленный, а напарник принял бы половину сообщения.
     */
    const val FRAME_MAX_OVERHEAD = 16

    @Volatile
    private var loaded = false

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return

            // Одна библиотека. Раньше мост к JVM жил в отдельной .so, и её
            // символы не находились: загрузчик открывает библиотеки с
            // RTLD_LOCAL, поэтому первая оставалась невидимой для второй.
            // Теперь и ядро, и мост лежат в одном libbraidwork_core.so.
            System.loadLibrary("braidwork_core")
            loaded = true
        }
    }

    /**
     * Проверяет, что обе библиотеки загрузились.
     *
     * Вызывается до первого кадра, чтобы падение показать текстом: иначе на
     * телефоне нет ни экрана, ни следа, и причина остаётся неизвестной.
     */
    fun checkLoaded() {
        ensureLoaded()
    }

    /** Ключи устройства: signing (32), static (32), одноразовый qr (32). */
    data class PublicKeys(
        val signing: ByteArray,
        val staticKey: ByteArray,
        val qrKey: ByteArray,
    )

    /**
     * Файл ключей и публичные ключи из него.
     *
     * Один объект вместо двух значений: разъехаться они могут только если
     * клиент забудет сохранить файл, а тогда устройство станет другим при
     * следующем запуске.
     */
    data class KeyBlob(val blob: ByteArray, val public: PublicKeys)

    /**
     * Создаёт ключи устройства и отдаёт файл для сохранения.
     *
     * Вызывается ровно один раз за жизнь устройства. Дальше ключи лежат в
     * приватном файле приложения, и каждый запуск читает их оттуда.
     *
     * Почему это важно: `device_id` выводится из ключа подписи, поэтому новые
     * ключи дают новое устройство. Раньше ключи генерировались при каждом
     * открытии приложения, и идентификатор менялся сам по себе — сопряжение по
     * QR и доверие после перезапуска не имели бы смысла.
     */
    fun createKeys(): KeyBlob {
        ensureLoaded()
        val blob = ByteArray(keysBlobLen())
        val public = ByteArray(PUBLIC_KEYS_LEN)
        val status = nativeKeysCreate(blob, public)
        if (status != STATUS_OK) throw BraidworkException(status, "не удалось создать ключи устройства")
        return KeyBlob(blob, parsePublic(public))
    }

    /**
     * Загружает ключи из сохранённого файла.
     *
     * `STATUS_STORAGE` означает, что файл повреждён или записан другой версией
     * ядра. Вызывающий обязан показать это пользователю и не создавать новые
     * ключи: подмена молча уничтожила бы доверие ко всем сопряжённым устройствам.
     */
    fun loadKeys(blob: ByteArray): PublicKeys {
        require(blob.size == keysBlobLen()) { "файл ключей другой длины: ${blob.size}" }
        ensureLoaded()

        val public = ByteArray(PUBLIC_KEYS_LEN)
        val status = nativeKeysLoad(blob, public)
        if (status != STATUS_OK) throw BraidworkException(status, "файл ключей нечитаем")
        return parsePublic(public)
    }

    /**
     * Идентификатор устройства из сохранённого файла ключей.
     *
     * Приватные ключи в клиент не попадают: вычисляется только публичный ключ
     * подписи. Нужно там, где ключи в памяти держать незачем, — например для
     * сверки по QR.
     */
    fun deviceIdFromBlob(blob: ByteArray): String {
        require(blob.size == keysBlobLen()) { "файл ключей другой длины: ${blob.size}" }
        ensureLoaded()

        val out = ByteArray(DEVICE_ID_MAX)
        val len = IntArray(1)
        val status = nativeKeysDeviceId(blob, out, len)
        if (status != STATUS_OK) throw BraidworkException(status, "файл ключей нечитаем")

        return String(out, 0, len[0], Charsets.UTF_8)
    }

    /** Размер файла ключей: значение фиксировано, вызывается один раз. */
    fun keysBlobLen(): Int = PUBLIC_KEYS_BLOB_LEN

    /**
     * Собственный отпечаток ключа: четыре группы по четыре символа.
     *
     * Считает ядро, а не клиент. Отпечаток у соседа на экране сопряжения и
     * отпечаток устройства у себя — одна и та же строка, и человек сравнивает их
     * глазами. Вторая реализация в клиенте разъехалась бы с ядром при первом же
     * изменении формата, и сверка всегда давала бы «не совпало» — то есть
     * выглядела бы так, будто обманывают.
     */
    fun fingerprintFromBlob(blob: ByteArray): String {
        require(blob.size == keysBlobLen()) { "файл ключей другой длины: ${blob.size}" }
        ensureLoaded()

        val out = ByteArray(FINGERPRINT_MAX)
        val len = IntArray(1)
        val status = nativeKeysFingerprint(blob, out, len)
        if (status != STATUS_OK) throw BraidworkException(status, "файл ключей нечитаем")

        return String(out, 0, len[0], Charsets.UTF_8)
    }

    private fun parsePublic(buffer: ByteArray) = PublicKeys(
        signing = buffer.copyOfRange(0, 32),
        staticKey = buffer.copyOfRange(32, 64),
        qrKey = buffer.copyOfRange(64, 96),
    )

    /**
     * Генерирует ключи устройства.
     *
     * Старый путь, оставлен для совместимости: ключи из него нигде не
     * сохраняются, поэтому повторный вызов даёт новое устройство. Новым кодам
     * пользоваться [createKeys] и [loadKeys].
     */
    fun generateKeys(): PublicKeys {
        ensureLoaded()
        val buffer = ByteArray(PUBLIC_KEYS_LEN)
        val len = IntArray(1)
        val status = nativeInit(buffer, len)
        if (status != STATUS_OK) throw BraidworkException(status, "не удалось создать ключи устройства")

        return PublicKeys(
            signing = buffer.copyOfRange(0, 32),
            staticKey = buffer.copyOfRange(32, 64),
            qrKey = buffer.copyOfRange(64, 96),
        )
    }

    /**
     * Идентификатор устройства по публичному ключу подписи.
     *
     * Нужен, чтобы показать его пользователю для сверки при сопряжении.
     */
    fun deviceId(signingPublic: ByteArray): String {
        require(signingPublic.size == 32) { "ключ подписи должен быть 32 байта" }
        ensureLoaded()

        val out = ByteArray(DEVICE_ID_MAX)
        val len = IntArray(1)
        val status = nativeDeviceId(signingPublic, out, len)
        if (status != STATUS_OK) throw BraidworkException(status, "не удалось вычислить device_id")

        return String(out, 0, len[0], Charsets.UTF_8)
    }

    /**
     * Проверяет подпись рукопожатия.
     *
     * Вызывается на каждом соединении, а не только при первом сопряжении:
     * объявление себя чужим устройством в сети подделывается тривиально.
     */
    fun verifySignature(signingPublic: ByteArray, tape: ByteArray, signature: ByteArray) {
        require(signingPublic.size == 32) { "ключ подписи должен быть 32 байта" }
        require(signature.size == 64) { "подпись должна быть 64 байта" }
        ensureLoaded()

        val status = nativeVerifySignature(signingPublic, tape, signature)
        if (status != STATUS_OK) {
            val reason = if (status == STATUS_NOT_TRUSTED) "подпись не прошла проверку" else "ошибка проверки подписи"
            throw BraidworkException(status, reason)
        }
    }

    // =====================================================================
    // Транспорт
    //
    // События отдаются ядром параллельными массивами, а не структурой. Так
    // раскладку события знает только Rust: если бы клиент размещал структуру
    // ядра у себя, в проекте стало бы две её копии, и расхождение выглядело бы
    // как «события приходят с мусором» — искать пришлось бы в интерфейсе, а не
    // в ядре.
    // =====================================================================

    /** Что произошло в транспорте. */
    sealed class TransportEvent {
        /**
         * Найдено устройство в сети. Ещё не сопряжено.
         *
         * [name] — то, что устройство назвало само. Это **заявление**, а не
         * проверенный факт: подпись сверяется только на соединении, а объявление
         * приходит по UDP от кого угодно из подсети. Показывать его нужно,
         * иначе в списке стояло бы «Устройство» и в общей квартире, где чужой
         * телефон лежит рядом с твоим, отличить их было бы нечем. После
         * соединения настоящее имя занимает место заявленного.
         */
        data class Discovered(
            val deviceId: ByteArray,
            val port: Int,
            val name: String,
            /**
             * Адрес в подсети, четыре байта в сетевом порядке.
             *
             * Нужен для подключения: порт у всех устройств один, и без адреса
             * подключаться было бы некуда. Берётся из самого пакета объявления,
             * а не из настроек сети — устройство может быть многоинтерфейсным.
             */
            val ipv4: ByteArray,
            /**
             * Отпечаток ключа подписи: четыре группы по четыре символа.
             *
             * Ядро сверяет его с объявленным идентификатором и отбрасывает
             * объявление, если ключ не даёт тот самый идентификатор. Поэтому
             * пришедший отпечаток принадлежит именно тому устройству, что видно
             * в списке.
             *
             * Пусто у устройства с более старым ядром. Это не «нет ключа» и не
             * «ключ не пришёл»: такого ядра просто ещё не было, когда отпечаток
             * начали класть в объявление.
             */
            val fingerprint: String,
            /**
             * Своё имя системы напарника, например «Android 13».
             *
             * Приходит без подписи, сверить нельзя. Пусто у устройства с более
             * старым ядром, которое таких полей ещё не присылает.
             */
            val platform: String = "",
            /** Версия ядра напарника. Тоже без подписи и тоже может быть пустой. */
            val coreVersion: String = "",
            /**
             * Тип устройства напарника, каким он назвал себя сам.
             *
             * Объявлять обязан отправитель: получатель знает только адрес и
             * четыре байта порта из пакета, и по ним тип соседа не
             * восстановить — есть только правдоподобное враньё.
             *
             * **Пусто — обычное состояние**, а не поломка: ядро напарника старше
             * этого поля, либо клиент не смог определить тип. Оба случая
             * показываются как «неизвестно».
             *
             * Значение по умолчанию ставить нельзя. Ноутбук на карточке телефона
             * выглядит правдоподобно настолько, что человек примет его за
             * показание, — это ровно тот класс правды, которого здесь быть не
             * должно.
             */
            val deviceKind: String = "",
        ) : TransportEvent() {
            // ByteArray в data class сравнивается по ссылке, а не по содержимому:
            // два одинаковых идентификатора выглядели бы разными. Список
            // устройств менялся бы при каждом объявлении, а выглядел бы так же.
            override fun equals(other: Any?) =
                other is Discovered && deviceId.contentEquals(other.deviceId) &&
                    port == other.port && name == other.name &&
                    ipv4.contentEquals(other.ipv4) && fingerprint == other.fingerprint &&
                    platform == other.platform && coreVersion == other.coreVersion &&
                    deviceKind == other.deviceKind

            override fun hashCode() =
                31 * (31 * ((31 * (31 * (31 * (31 * deviceId.contentHashCode() + port) * 31 +
                    name.hashCode()) + ipv4.contentHashCode()) * 31 + fingerprint.hashCode()) * 31 +
                    platform.hashCode()) * 31 + coreVersion.hashCode()) * 31 +
                    deviceKind.hashCode()
        }

        /** Пришло входящее соединение. Рукопожатие идёт, сверки ещё не было. */
        /**
         * Пришло входящее соединение. Рукопожатие идёт, сверки ещё не было.
         *
         * [ipv4] — четыре байта отправителя. Без них входящее соединение
         * невозможно связать с устройством из списка, и на экране оно
         * выглядит как чужое.
         */
        data class Incoming(val conn: Long, val ipv4: ByteArray) : TransportEvent() {
            override fun equals(other: Any?) = other is Incoming && conn == other.conn

            override fun hashCode() = conn.hashCode()
        }

        /** Соединение открыто нами. */
        /**
         * Соединение открыто, рукопожатие прошло.
         *
         * [ipv4] — четыре байта напарника: по ним видно, с кем соединение, и
         * при нескольких устройствах в сети события не путаются местами.
         */
        data class Connected(val conn: Long, val ipv4: ByteArray) : TransportEvent() {
            override fun equals(other: Any?) = other is Connected && conn == other.conn

            override fun hashCode() = conn.hashCode()
        }

        /** Пришёл целый кадр в расшифрованном виде. */
        data class Frame(val conn: Long, val kind: Int, val body: ByteArray) : TransportEvent() {
            override fun equals(other: Any?) =
                other is Frame && conn == other.conn && kind == other.kind &&
                    body.contentEquals(other.body)

            override fun hashCode() = (31 * conn.hashCode() + kind) * 31 + body.contentHashCode()
        }

        /**
         * Соединение закрыто.
         *
         * [reason] обязателен: «напарник закрыл сокет у себя» и «сеть отвалилась»
         * снаружи выглядят одинаково, а правятся по-разному. Без различения
         * пришлось бы угадывать, что именно сломалось.
         */
        data class Closed(val conn: Long, val reason: Int, val ipv4: ByteArray) : TransportEvent() {
            /** Причина по-русски, для показа в журнале подключения. */
            fun reasonText(): String = when (reason) {
                CLOSE_PEER -> "напарник закрыл соединение у себя"
                CLOSE_NETWORK -> "обрыв сети"
                CLOSE_HANDSHAKE -> "рукопожатие сорвалось"
                CLOSE_BAD_FRAME -> "пришёл битый кадр"
                CLOSE_BY_REQUEST -> "закрыто по команде"
                else -> "неизвестная причина ($reason)"
            }
        }

        /** Нефатальная ошибка: транспорт продолжает работать. */
        data class Notice(val error: Int) : TransportEvent()

        /** Изменилось состояние транспорта. */
        data class State(val value: Int) : TransportEvent()
    }

    /**
     * Поднимает канал локальной сети и возвращает его хэндл.
     *
     * Ключи берутся из файла устройства: транспорт обязан работать от настоящей
     * личности, иначе напарник не сверит подпись, а идентификатор устройства
     * будет меняться при каждом запуске.
     *
     * [port] = 0 означает «выбери свободный». Настоящий порт — [transportPort].
     *
     * [platform] уходит в объявление и показывается на карточке напарника.
     * Пустая строка законна: тогда строка на карточке останется пустой, и это
     * правда, а не пробел. Подписью платформа не защищена, и сверить по ней
     * нечего.
     *
     * [deviceKind] — тип устройства: `phone`, `tablet` или `laptop`. Объявляет
     * отправитель, потому что получатель знает только адрес и четыре байта
     * порта из пакета и по ним тип соседа не восстановить.
     *
     * Пустая строка законна и означает «не знаю». На карточке у соседа в этом
     * случае будет «неизвестно» — и это правда. Значение по умолчанию здесь
     * ставить нельзя: ноутбук на телефоне выглядит правдоподобно, и человек
     * поверит ему.
     *
     * При `STATUS_STORAGE` файл ключей нечитаем. Вызывающий обязан показать это
     * пользователю и не создавать новые ключи молча.
     */
    fun transportStart(
        keysBlob: ByteArray,
        displayName: String,
        platform: String = "",
        deviceKind: String = "",
        port: Int = 0,
    ): Long {
        require(keysBlob.size == PUBLIC_KEYS_BLOB_LEN) {
            "файл ключей другой длины: ${keysBlob.size}"
        }
        ensureLoaded()

        val name = displayName.toByteArray(Charsets.UTF_8)
        val платформа = platform.toByteArray(Charsets.UTF_8)
        val тип = deviceKind.toByteArray(Charsets.UTF_8)
        val handle = LongArray(1)
        val status = nativeTransportStart(keysBlob, name, платформа, тип, port, handle)
        if (status != STATUS_OK) {
            throw BraidworkException(status, "не удалось поднять канал локальной сети")
        }
        return handle[0]
    }

    /**
     * Забирает события транспорта.
     *
     * Пустой список — не ошибка, а «жди дальше»: клиент зовёт это в цикле, и
     * отсутствие событий должно быть обычным ответом, а не записью в журнал
     * каждый кадр.
     *
     * Размеры буферов определяют [maxEvents] и [payloadCap]. Если тел больше,
     * чем влезло, ядро отдаёт события без тех тел, что не поместились, и
     * клиент узнаёт об этом по пустому телу, а не по половине файла.
     */
    fun transportPoll(handle: Long, maxEvents: Int, payloadCap: Int): List<TransportEvent> {
        require(maxEvents > 0) { "нужно место хотя бы под одно событие" }
        ensureLoaded()

        val типы = IntArray(maxEvents)
        val коннекты = LongArray(maxEvents)
        val виды = IntArray(maxEvents)
        val вспомогательные = IntArray(maxEvents)
        val идентификаторы = ByteArray(maxEvents * DEVICE_ID_BYTES)
        val адреса = ByteArray(maxEvents * 4)
        val смещения = IntArray(maxEvents)
        val длины = IntArray(maxEvents)
        val тела = ByteArray(payloadCap.coerceAtLeast(1))
        val счётчик = IntArray(1)

        val status = nativeTransportPoll(
            handle, типы, коннекты, виды, вспомогательные,
            идентификаторы, адреса, смещения, длины, тела, счётчик,
        )
        if (status == STATUS_INVALID_ARG) {
            // Транспорта нет: либо освобождён, либо никогда не поднимался.
            // Не исключение — клиент сам освобождал его и ждёт следующего.
            return emptyList()
        }
        if (status != STATUS_OK) {
            throw BraidworkException(status, "опрос транспорта не удался")
        }

        val count = счётчик[0].coerceIn(0, maxEvents)
        val события = ArrayList<TransportEvent>(count)

        for (i in 0 until count) {
            val conn = коннекты[i]
            val off = смещения[i]
            val сколько = длины[i]

            // Тело вырезается по смещению И длине. Только по смещению нельзя:
            // при двух кадрах в одной порции второе тело начиналось бы там, где
            // кончилось первое, и клиент отдал бы чужой кадр.
            val payload = if (off >= 0 && сколько >= 0 && off + сколько <= тела.size) {
                тела.copyOfRange(off, off + сколько)
            } else {
                // Ядро отдало событие, тело которого не влезло. Пустое тело
                // честнее половины: клиент знает, что кадр потерян, и не
                // отправит напарнику обрывок.
                ByteArray(0)
            }

            события += when (типы[i]) {
                EV_DISCOVERED -> {
                    // Ядро кладёт в тело объявления имя и отпечаток, разделённые нулевым
                    // байтом. Разбор один: тело декодируется дважды, а это
                    // лишняя работа на каждом объявлении, а их приходит по
                    // одному от каждого устройства каждые две секунды.
                    //
                    // Разделитель — нулевой байт, а не пробел: в отпечатке
                    // пробелы есть, «ABCD EFGH IJKL MNOP», и деление по пробелу
                    // разрезало бы его на части.
                    //
                    // Нулевой байт безопасен и в ядре, и здесь: имя очищено от
                    // управляющих символов ещё в транспорте, и разделитель в
                    // него попасть не может.
                    val объявление = payload.toString(Charsets.UTF_8).split('\u0000')
                        TransportEvent.Discovered(
                        deviceId = идентификаторы.copyOfRange(i * DEVICE_ID_BYTES, (i + 1) * DEVICE_ID_BYTES),
                        port = вспомогательные[i],
                        // Ядро гарантирует непустое имя, но разбор UTF-8 всё равно
                        // может не сойтись на чужом устройстве. Тогда показываем
                        // честную заглушку, а не пустую строку: та в плитке читается
                        // как поломка.
                        name = объявление.getOrNull(0)?.ifEmpty { "Устройство" } ?: "Устройство",
                        // Пустой отпечаток означает устройство с более старым ядром.
                        // Выдуманное значение было бы хуже прочеркивания: человек
                        // сверил бы с тем, что никто не сверяет.
                        fingerprint = объявление.getOrNull(1).orEmpty(),
                        // Платформа присылается без подписи и может быть
                        // пустой: клиент о системе не знает. Тогда на карточке
                        // останется прочеркивание, и это правда, а не пробел.
                        platform = объявление.getOrNull(2).orEmpty(),
                        coreVersion = объявление.getOrNull(3).orEmpty(),
                        // Пятая часть — тип устройства. Её может не быть: у
                        // напарника ядро старше этого поля, и тогда на карточке
                        // будет «неизвестно».
                        deviceKind = объявление.getOrNull(4).orEmpty(),
                        ipv4 = адреса.copyOfRange(i * 4, i * 4 + 4),
                    )
                }

                EV_INCOMING -> TransportEvent.Incoming(conn, адреса.copyOfRange(i * 4, i * 4 + 4))
                EV_CONNECTED -> TransportEvent.Connected(conn, адреса.copyOfRange(i * 4, i * 4 + 4))
                EV_CLOSED -> TransportEvent.Closed(
                    conn,
                    вспомогательные[i],
                    адреса.copyOfRange(i * 4, i * 4 + 4),
                )
                EV_NOTICE -> TransportEvent.Notice(вспомогательные[i])
                EV_STATE -> TransportEvent.State(виды[i])
                EV_FRAME -> TransportEvent.Frame(conn, виды[i], payload)

                // Неизвестный вид события — устройство с более новым ядром.
                // Кадр отбрасывается, а не роняет разбор: иначе одно новое
                // событие выключило бы связь со всей сетью.
                else -> continue
            }
        }

        return события
    }

    /**
     * Порт, на котором транспорт принимает соединения.
     *
     * Ноль означает «транспорта нет»: клиенту это нужно отличать от порта, и
     * показывать «0» в настройках было бы враньём.
     */
    fun transportPort(handle: Long): Int {
        ensureLoaded()
        return nativeTransportPort(handle)
    }

    /**
     * Подключается к устройству в локальной сети.
     *
     * [ipv4] — четыре байта адреса в сетевом порядке, как пришло в
     * [TransportEvent.Discovered]. Строка разбирается на стороне клиента, и
     * это вторая копия разбора адреса рядом с первой в ядре: они разошлись бы
     * при первом же нестандартном написании.
     *
     * Возвращает хэндл соединения, по которому пойдёт рукопожатие и кадры.
     */
    fun transportConnect(handle: Long, ipv4: ByteArray, port: Int): Long {
        require(ipv4.size == 4) { "адрес должен быть 4 байта" }
        require(port in 1..65535) { "порт вне диапазона: $port" }
        ensureLoaded()

        val conn = LongArray(1)
        val status = nativeTransportConnect(handle, ipv4, port, conn)
        if (status != STATUS_OK) {
            throw BraidworkException(status, "не удалось подключиться к устройству")
        }
        return conn[0]
    }

    /** Жив ли ещё транспорт по этому хэндлу. */
    fun transportAlive(handle: Long): Boolean {
        ensureLoaded()
        return nativeTransportAlive(handle) == 1
    }

    /**
     * Отправляет готовый кадр в соединение.
     *
     * Первая отправка через шифрованный канал. Раньше отправлять было нечем:
     * `fl_transport_send` жил в C ABI, но до клиента не доходил, и канал
     * поднимался, оставаясь пустым.
     *
     * На входе **целый кадр** из [heartbeatFrame] или [clipboardFrame], а не тело.
     * Тип сообщения объявляется один раз, в своём заголовке: иначе получателю
     * пришлось бы угадывать его по содержимому.
     */
    fun transportSend(handle: Long, conn: Long, frame: ByteArray) {
        require(frame.isNotEmpty()) { "пустой кадр отправлять нечем" }
        ensureLoaded()

        val status = nativeTransportSend(handle, conn, frame)
        if (status != STATUS_OK) {
            // Рукопожатие не завершено — самая частая причина, и человеку
            // сказать «не удалось отправить» бесполезно: он ничего не сможет
            // с этим сделать. Поэтому в сообщении названа причина.
            throw BraidworkException(
                status,
                if (status == STATUS_TRANSPORT) {
                    "канал ещё не готов: рукопожатие не завершено"
                } else {
                    "не удалось отправить в соединение"
                },
            )
        }
    }

    /**
     * Собирает кадр `Heartbeat` из настоящих значений устройства.
     *
     * Считает ядро, а не клиент: время, заряд и зарядку человек сверяет с экраном
     * своего телефона, и вторая реализация разъехалась бы с ядром при первом же
     * изменении схемы — и показала бы чужое значение как своё.
     */
    fun heartbeatFrame(unixTimeMs: Long, batteryPercent: Int, charging: Boolean): ByteArray {
        ensureLoaded()

        val out = ByteArray(HEARTBEAT_FRAME_MAX)
        val len = IntArray(1)
        val status = nativeHeartbeatBuild(unixTimeMs, batteryPercent, charging, out, len)
        if (status != STATUS_OK) {
            // Заряд выше сотни — ошибка того, кто зовёт, а не устройства.
            throw BraidworkException(status, "заряд вне диапазона 0..100: $batteryPercent")
        }
        return out.copyOf(len[0])
    }

    /** Что прислал напарник в `Heartbeat`. */
    data class Heartbeat(
        /** Время напарника в миллисекундах Unix. */
        val unixTimeMs: Long,
        /** Заряд в процентах, 0 означает «разряжен», а не «неизвестно». */
        val batteryPercent: Int,
        /** На зарядке ли напарник. */
        val charging: Boolean,
    )

    /**
     * Разбирает тело `Heartbeat`.
     *
     * `null` — тело не то: это не ошибка связи, а незнакомое или повреждённое
     * сообщение. Исключение здесь было бы хуже: один чужой кадр уронил бы цикл
     * опроса событий, а с ним и всю связь.
     */
    fun readHeartbeat(body: ByteArray): Heartbeat? {
        ensureLoaded()

        val out = LongArray(3)
        val status = nativeHeartbeatRead(body, out)
        if (status != STATUS_OK) return null
        return Heartbeat(out[0], out[1].toInt(), out[2] != 0L)
    }

    /** Собирает кадр `ClipboardContent`. */
    fun clipboardFrame(content: String, mimeType: String): ByteArray {
        ensureLoaded()

        val out = ByteArray(CLIPBOARD_MAX)
        val len = IntArray(1)
        val status = nativeClipboardBuild(content.toByteArray(Charsets.UTF_8), mimeType, out, len)
        if (status != STATUS_OK) {
            throw BraidworkException(status, "содержимое не проходит: не текст или слишком велико")
        }
        return out.copyOf(len[0])
    }

    /**
     * Разбирает тело `ClipboardContent`.
     *
     * `null` — тело не то. Разбор живёт в ядре: второй разборщик протокола на
     * стороне приложения разъехался бы с ядром, и человек вставил бы в буфер
     * обмена чужой текст, ничего не проверив.
     */
    fun readClipboard(body: ByteArray): Pair<String, String>? {
        ensureLoaded()

        val content = ByteArray(CLIPBOARD_MAX)
        val contentLen = IntArray(1)
        val mime = ByteArray(MIME_MAX)
        val mimeLen = IntArray(1)
        val status =
            nativeClipboardRead(body, content, contentLen, mime, mimeLen)
        if (status != STATUS_OK) return null
        return String(content, 0, contentLen[0], Charsets.UTF_8) to
            String(mime, 0, mimeLen[0], Charsets.UTF_8)
    }

    data class FileOfferData(
        val transferId: ByteArray,
        val fileName: String,
        val totalSize: Long,
        val totalHash: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileOfferData) return false
            return transferId.contentEquals(other.transferId) &&
                fileName == other.fileName &&
                totalSize == other.totalSize &&
                totalHash.contentEquals(other.totalHash)
        }

        override fun hashCode(): Int {
            var result = transferId.contentHashCode()
            result = 31 * result + fileName.hashCode()
            result = 31 * result + totalSize.hashCode()
            result = 31 * result + totalHash.contentHashCode()
            return result
        }
    }

    data class FileBlockData(
        val transferId: ByteArray,
        val offset: Long,
        val data: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FileBlockData) return false
            return transferId.contentEquals(other.transferId) &&
                offset == other.offset &&
                data.contentEquals(other.data)
        }

        override fun hashCode(): Int {
            var result = transferId.contentHashCode()
            result = 31 * result + offset.hashCode()
            result = 31 * result + data.contentHashCode()
            return result
        }
    }

    /** Собирает кадр `FileOffer`. */
    fun fileOfferFrame(
        transferId: ByteArray,
        fileName: String,
        totalSize: Long,
        totalHash: ByteArray,
    ): ByteArray {
        ensureLoaded()
        val out = ByteArray(FILE_OFFER_FRAME_MAX)
        val len = IntArray(1)
        val status = nativeFileOfferBuild(transferId, fileName, totalSize, totalHash, out, len)
        if (status != STATUS_OK) {
            throw BraidworkException(status, "не удалось собрать кадр предложения файла")
        }
        return out.copyOf(len[0])
    }

    /** Разбирает тело `FileOffer`. */
    fun readFileOffer(body: ByteArray): FileOfferData? {
        ensureLoaded()
        val tid = ByteArray(64)
        val tidLen = IntArray(1)
        val name = ByteArray(1024)
        val nameLen = IntArray(1)
        val size = LongArray(1)
        val hash = ByteArray(HASH_LEN)
        val hashLen = IntArray(1)

        val status = nativeFileOfferRead(body, tid, tidLen, name, nameLen, size, hash, hashLen)
        if (status != STATUS_OK) return null
        return FileOfferData(
            transferId = tid.copyOf(tidLen[0]),
            fileName = String(name, 0, nameLen[0], Charsets.UTF_8),
            totalSize = size[0],
            totalHash = hash.copyOf(hashLen[0]),
        )
    }

    /** Собирает кадр `FileBlock`. */
    fun fileBlockFrame(
        transferId: ByteArray,
        offset: Long,
        data: ByteArray,
    ): ByteArray {
        ensureLoaded()
        val out = ByteArray(FILE_BLOCK_FRAME_MAX)
        val len = IntArray(1)
        val status = nativeFileBlockBuild(transferId, offset, data, out, len)
        if (status != STATUS_OK) {
            throw BraidworkException(status, "не удалось собрать кадр блока файла")
        }
        return out.copyOf(len[0])
    }

    /** Разбирает тело `FileBlock`, сверяя контрольную сумму блока. */
    fun readFileBlock(body: ByteArray): FileBlockData? {
        ensureLoaded()
        val tid = ByteArray(64)
        val tidLen = IntArray(1)
        val offset = LongArray(1)
        val data = ByteArray(FILE_BLOCK_MAX)
        val dataLen = IntArray(1)

        val status = nativeFileBlockRead(body, tid, tidLen, offset, data, dataLen)
        if (status != STATUS_OK) return null
        return FileBlockData(
            transferId = tid.copyOf(tidLen[0]),
            offset = offset[0],
            data = data.copyOf(dataLen[0]),
        )
    }

    /** Вычисляет BLAKE3 хэш от байтового массива. */
    fun blake3(data: ByteArray): ByteArray {
        ensureLoaded()
        val out = ByteArray(HASH_LEN)
        val status = nativeBlake3Hash(data, out)
        if (status != STATUS_OK) {
            throw BraidworkException(status, "ошибка вычисления BLAKE3")
        }
        return out
    }

    /**
     * Поточный расчёт BLAKE3 от [InputStream] порциями по 64 КиБ.
     * Не загружает весь файл в память.
     */
    fun blake3Stream(stream: java.io.InputStream): ByteArray {
        ensureLoaded()
        val handleArr = LongArray(1)
        val createStatus = nativeBlake3HasherCreate(handleArr)
        if (createStatus != STATUS_OK) {
            throw BraidworkException(createStatus, "ошибка создания hasher")
        }
        val handle = handleArr[0]
        try {
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (stream.read(buf).also { read = it } != -1) {
                if (read > 0) {
                    val slice = if (read == buf.size) buf else buf.copyOf(read)
                    val updateStatus = nativeBlake3HasherUpdate(handle, slice)
                    if (updateStatus != STATUS_OK) {
                        throw BraidworkException(updateStatus, "ошибка обновления hasher")
                    }
                }
            }
            val out = ByteArray(HASH_LEN)
            val finStatus = nativeBlake3HasherFinalize(handle, out)
            if (finStatus != STATUS_OK) {
                throw BraidworkException(finStatus, "ошибка финализации hasher")
            }
            return out
        } finally {
            nativeBlake3HasherFree(handle)
        }
    }

    /** Останавливает транспорт: сокеты закрыты, состояние сброшено. */
    fun transportStop(handle: Long) {
        ensureLoaded()
        nativeTransportStop(handle)
    }

    /**
     * Освобождает транспорт.
     *
     * Повторный вызов с тем же хэндлом даёт ошибку, а не паникует: клиент вправе
     * освободить объект в обработчике ошибки, не зная, что он уже освобождён.
     */
    fun transportFree(handle: Long) {
        ensureLoaded()
        nativeTransportFree(handle)
    }

    /**
     * Собирает кадр из готового protobuf-тела.
     *
     * Клиенту нужен кадр, а не тело: тип сообщения объявляется в заголовке.
     * Собирать его здесь нельзя — второй разборщик рано или поздно разошёлся бы
     * с ядром.
     */
    fun frameEncode(kind: Int, flags: Int, body: ByteArray): ByteArray {
        ensureLoaded()
        val out = ByteArray(body.size + FRAME_MAX_OVERHEAD)
        val len = IntArray(1) { out.size }

        val status = nativeFrameEncode(kind, flags, body, out, len)
        if (status == STATUS_NO_MEMORY) {
            // Ядро сообщило нужную длину: буфер мал.
            val точнее = ByteArray(len[0])
            val второй = IntArray(1) { точнее.size }
            val повтор = nativeFrameEncode(kind, flags, body, точнее, второй)
            if (повтор != STATUS_OK) throw BraidworkException(повтор, "кадр не собрался")
            return точнее
        }
        if (status != STATUS_OK) throw BraidworkException(status, "кадр не собрался")

        return out.copyOfRange(0, len[0])
    }

    private external fun nativeInit(outPublic: ByteArray, outLen: IntArray): Int
    private external fun nativeKeysCreate(outBlob: ByteArray, outPublic: ByteArray): Int
    private external fun nativeKeysLoad(blob: ByteArray, outPublic: ByteArray): Int
    private external fun nativeKeysDeviceId(blob: ByteArray, outId: ByteArray, outLen: IntArray): Int

    private external fun nativeKeysFingerprint(blob: ByteArray, outFp: ByteArray, outLen: IntArray): Int
    private external fun nativeDeviceId(signingPublic: ByteArray, outId: ByteArray, outLen: IntArray): Int
    private external fun nativeVerifySignature(signingPublic: ByteArray, tape: ByteArray, signature: ByteArray): Int

    /**
     * Поднимает канал локальной сети.
     *
     * ## Порядок параметров жёсткий
     *
     * Имя функции JNI кодирует только имя, но не типы. Ошибка на один
     * параметр здесь не даёт `UnsatisfiedLinkError`: приложение запустится, а
     * нативный код прочитает не тот аргумент — указатель вместо длины или
     * наоборот. Симптом будет «канал работает с чужим именем», и искать это
     * придётся по косвенным признакам.
     *
     * Поэтому [transportStart] — единственное место, где параметры перечисляются
     * поимённо, и менять их можно только вместе с
     * `Java_…_nativeTransportStart` в `jni_bridge.rs`.
     */
    private external fun nativeTransportStart(
        keysBlob: ByteArray,
        displayName: ByteArray,
        platform: ByteArray,
        deviceKind: ByteArray,
        port: Int,
        outHandle: LongArray,
    ): Int

    private external fun nativeTransportPoll(
        handle: Long,
        outType: IntArray,
        outConn: LongArray,
        outKind: IntArray,
        outAux: IntArray,
        outDevice: ByteArray,
        outIp: ByteArray,
        outOff: IntArray,
        outPlen: IntArray,
        outPayload: ByteArray,
        outCount: IntArray,
    ): Int

    private external fun nativeTransportPort(handle: Long): Int
    private external fun nativeTransportConnect(
        handle: Long,
        ipv4: ByteArray,
        port: Int,
        outConn: LongArray,
    ): Int

    private external fun nativeTransportSend(
        handle: Long,
        conn: Long,
        frame: ByteArray,
    ): Int

    private external fun nativeHeartbeatBuild(
        unixTimeMs: Long,
        batteryPercent: Int,
        charging: Boolean,
        out: ByteArray,
        outLen: IntArray,
    ): Int

    private external fun nativeHeartbeatRead(body: ByteArray, out: LongArray): Int

    private external fun nativeClipboardBuild(
        content: ByteArray,
        mimeType: String,
        out: ByteArray,
        outLen: IntArray,
    ): Int

    private external fun nativeClipboardRead(
        body: ByteArray,
        outContent: ByteArray,
        outContentLen: IntArray,
        outMime: ByteArray,
        outMimeLen: IntArray,
    ): Int
    private external fun nativeFileOfferBuild(
        transferId: ByteArray,
        fileName: String,
        totalSize: Long,
        totalHash: ByteArray,
        out: ByteArray,
        outLen: IntArray,
    ): Int

    private external fun nativeFileOfferRead(
        body: ByteArray,
        outTransferId: ByteArray,
        outTransferIdLen: IntArray,
        outFileName: ByteArray,
        outFileNameLen: IntArray,
        outTotalSize: LongArray,
        outTotalHash: ByteArray,
        outTotalHashLen: IntArray,
    ): Int

    private external fun nativeFileBlockBuild(
        transferId: ByteArray,
        offset: Long,
        data: ByteArray,
        out: ByteArray,
        outLen: IntArray,
    ): Int

    private external fun nativeFileBlockRead(
        body: ByteArray,
        outTransferId: ByteArray,
        outTransferIdLen: IntArray,
        outOffset: LongArray,
        outData: ByteArray,
        outDataLen: IntArray,
    ): Int

    private external fun nativeBlake3Hash(data: ByteArray, outHash: ByteArray): Int
    private external fun nativeBlake3HasherCreate(outHandle: LongArray): Int
    private external fun nativeBlake3HasherUpdate(handle: Long, data: ByteArray): Int
    private external fun nativeBlake3HasherFinalize(handle: Long, outHash: ByteArray): Int
    private external fun nativeBlake3HasherFree(handle: Long): Int

    private external fun nativeTransportStop(handle: Long): Int
    private external fun nativeTransportFree(handle: Long): Int
    private external fun nativeTransportAlive(handle: Long): Int
    private external fun nativeFrameEncode(
        kind: Int,
        flags: Int,
        body: ByteArray,
        out: ByteArray,
        outLen: IntArray,
    ): Int
}

/** Ошибка ядра с её статус-кодом. */
class BraidworkException(val status: Int, message: String) : RuntimeException("$message (статус $status)")
