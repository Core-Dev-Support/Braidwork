package com.naua_braidwork.app

import java.io.File

/**
 * Хранилище ключей устройства на диске.
 *
 * Ядро не пишет файлы само: в нём нет ни одной платформенной зависимости
 * (D-001), а хранилище у Android, Windows и Linux устроено по-разному. Ядро
 * отдаёт байты, клиент кладёт их в приватный файл приложения.
 *
 * Файл лежит во внутреннем хранилище приложения (`filesDir`), а не во внешнем:
 * во внешнем он доступен другим приложениям, а файл ключей — это и есть
 * устройство.
 *
 * Пишется через временный файл с последующим переименованием: обычная запись
 * при убийстве процесса посреди файла оставила бы половину ключей, а читатель
 * решил бы, что устройство просто повреждено.
 */
class DeviceIdentityStore(private val directory: File) {

    /** Что известно об устройстве после загрузки или создания ключей. */
    data class Identity(
        val deviceId: String,
        val signing: ByteArray,
        val staticKey: ByteArray,
        /**
         * Собственный отпечаток ключа подписи.
         *
         * Нужен, чтобы человек мог сверить его с отпечатком этого же
         * устройства на экране сопряжения у соседа. Без него сверка физически
         * невозможна: у соседа отпечаток есть, а у себя его негде посмотреть.
         */
        val fingerprint: String = "",
    )

    /**
     * Результат чтения или создания ключей.
     *
     * `broken` означает, что файл есть, но нечитаем. Это отдельный случай, а не
     * ошибка: создавать новые ключи молча нельзя, иначе все ранее
     * сопряжённые устройства превратились бы в чужие без единого слова
     * пользователю.
     */
    sealed interface Result {
        data class Ready(val identity: Identity) : Result

        /** Файла нет: это первый запуск, ключи только что созданы. */
        data class Created(val identity: Identity) : Result

        /** Файл повреждён или записан другой версией ядра. */
        data class Broken(val reason: String) : Result
    }

    /**
     * Загружает устройство или создаёт его при первом запуске.
     *
     * Порядок именно такой: сначала пробуем прочитать, и только если файла нет —
     * создаём. Обратный порядок затирал бы устройство на каждом запуске.
     */
    fun loadOrCreate(): Result {
        val file = keysFile()

        if (file.exists()) {
            val bytes = runCatching { file.readBytes() }.getOrNull()
            if (bytes == null) return Result.Broken("файл ключей не читается")

            return try {
                val public = BraidworkCore.loadKeys(bytes)
                val id = BraidworkCore.deviceIdFromBlob(bytes)
                Result.Ready(
                    Identity(
                        deviceId = id,
                        signing = public.signing,
                        staticKey = public.staticKey,
                        fingerprint = BraidworkCore.fingerprintFromBlob(bytes),
                    ),
                )
            } catch (e: BraidworkException) {
                Result.Broken(e.message ?: "файл ключей нечитаем")
            }
        }

        val created = BraidworkCore.createKeys()
        // Сохраняем до возврата результата: если запись не удалась, устройство
        // не должно уйти в интерфейс с ключами, которых нет на диске.
        val saved = saveAtomically(created.blob)
        if (!saved) {
            return Result.Broken("ключи созданы, но сохранить их не удалось")
        }

        return Result.Created(
            Identity(
                deviceId = BraidworkCore.deviceIdFromBlob(created.blob),
                signing = created.public.signing,
                staticKey = created.public.staticKey,
                fingerprint = BraidworkCore.fingerprintFromBlob(created.blob),
            ),
        )
    }

    /**
     * Байты файла ключей для транспорта.
     *
     * Отдельный метод, а не переиспользование [loadOrCreate], потому что
     * транспорту нужны сырые байты, а не разобранная личность, и потому что
     * он поднимается в сервисе раньше экрана.
     *
     * Файл читается с диска, а не кэшируется: сервис и экран — разные времена
     * жизни, и копия в памяти разошлась бы с тем, что на диске, сразу после
     * того как кто-то из них это заметит.
     *
     * Повреждённый файл здесь **не пересоздаётся**: вызывается исключение, и
     * поднимать канал без ключей нельзя. Молча сменить ключи значило бы сделать
     * все ранее сопряжённые устройства чужими, не сказав пользователю ни
     * слова.
     */
    fun loadOrCreateBlob(): ByteArray {
        val file = keysFile()

        if (file.exists()) {
            val bytes = readAndCheck(file) ?: throw IllegalStateException("файл ключей не читается")
            return bytes
        }

        val created = BraidworkCore.createKeys()
        if (!saveAtomically(created.blob)) {
            throw IllegalStateException("ключи созданы, но сохранить их не удалось")
        }
        return created.blob
    }

    /**
     * Читает файл и проверяет его ядром.
     *
     * `null` означает «файл есть, но это не наши ключи». Проверку делает ядро:
     * у него есть контрольная сумма и версия формата, а клиент знает только
     * длину.
     */
    private fun readAndCheck(file: File): ByteArray? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return try {
            BraidworkCore.loadKeys(bytes)
            bytes
        } catch (e: BraidworkException) {
            null
        }
    }

    /**
     * Записывает файл целиком: сначала во временный, потом переименованием.
     *
     * Переименование в пределах одного каталога атомарно, поэтому читатель
     * либо увидит старый файл, либо новый, но никогда половину.
     */
    private fun saveAtomically(blob: ByteArray): Boolean = runCatching {
        if (!directory.exists()) directory.mkdirs()
        val temp = File(directory, TEMP_NAME)
        temp.writeBytes(blob)
        if (!temp.renameTo(keysFile())) {
            // Переименование может не сработать на занятом файле. Пробуем
            // записать напрямую: хуже, но лучше, чем остаться без ключей.
            keysFile().writeBytes(blob)
            temp.delete()
        }
        true
    }.getOrDefault(false)

    private fun keysFile() = File(directory, KEYS_NAME)

    companion object {
        private const val KEYS_NAME = "device.keys"
        private const val TEMP_NAME = "device.keys.tmp"
    }
}
