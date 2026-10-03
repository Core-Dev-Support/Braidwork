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

    /** Размер буфера под идентификатор устройства. */
    const val DEVICE_ID_MAX = 32

    const val STATUS_OK = 0
    const val STATUS_CRYPTO = 3
    const val STATUS_NOT_TRUSTED = 4
    const val STATUS_PANIC = 8

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
     * Генерирует ключи устройства.
     *
     * Вызывается один раз при первом запуске, дальше ключи хранятся локально.
     * Отправлять их в открытом виде нельзя.
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
    private external fun nativeDeviceId(signingPublic: ByteArray, outId: ByteArray, outLen: IntArray): Int
    private external fun nativeVerifySignature(signingPublic: ByteArray, tape: ByteArray, signature: ByteArray): Int
}

/** Ошибка ядра с её статус-кодом. */
class BraidworkException(val status: Int, message: String) : RuntimeException("$message (статус $status)")