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
 * Набор функций пока узкий: идентичность устройства и проверка подписи.
 * Сессии и транспорт ещё не реализованы, поэтому об хэндлах здесь знать
 * нечего — они появятся вместе с ними.
 *
 * Статусы ядра: 0 — успех, 3 — криптография, 4 — не доверяем, 8 — паника ядра.
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
    const val STATUS_CRYPTO = 3
    const val STATUS_NOT_TRUSTED = 4
    const val STATUS_PANIC = 8
    const val STATUS_STORAGE = 9

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

    private external fun nativeInit(outPublic: ByteArray, outLen: IntArray): Int
    private external fun nativeKeysCreate(outBlob: ByteArray, outPublic: ByteArray): Int
    private external fun nativeKeysLoad(blob: ByteArray, outPublic: ByteArray): Int
    private external fun nativeKeysDeviceId(blob: ByteArray, outId: ByteArray, outLen: IntArray): Int
    private external fun nativeDeviceId(signingPublic: ByteArray, outId: ByteArray, outLen: IntArray): Int
    private external fun nativeVerifySignature(signingPublic: ByteArray, tape: ByteArray, signature: ByteArray): Int
}

/** Ошибка ядра с её статус-кодом. */
class BraidworkException(val status: Int, message: String) : RuntimeException("$message (статус $status)")