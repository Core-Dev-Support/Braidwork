/*
 * Braidwork — граница C ABI.
 *
 * Файл статичен и проверяется в CI сравнением с перегенерированным (D-010).
 * Правки вносятся руками: это публичный контракт, и его должны ревьюить глазами,
 * а не генерировать из исходников Rust.
 *
 * Правила, которые клиент обязан соблюдать:
 *   - все строки UTF-8 и всегда с явной длиной;
 *   - память из ядра освобождается только через fl_free, ровно один раз;
 *   - хэндл непрозрачен, его внутреннее значение трогать нельзя;
 *   - при FL_ERR_PANIC сессия считается потерянной и пересоздаётся.
 */

#ifndef BRAIDWORK_CORE_H
#define BRAIDWORK_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Версия ABI. Клиент обязан отказаться работать при несовпадении. */
#define FL_ABI_VERSION 1u

/* Количество байт, которое занимает device_id. */
#define FL_DEVICE_ID_MAX 32u

/* Результат операции. */
typedef int32_t fl_status;

#define FL_OK                  0
#define FL_ERR_INVALID_ARG     1
#define FL_ERR_NO_MEMORY       2
#define FL_ERR_CRYPTO          3
#define FL_ERR_NOT_TRUSTED     4
#define FL_ERR_ALREADY_EXISTS  5
#define FL_ERR_TRANSPORT       6
#define FL_ERR_INTERNAL        7
/* Ловушка сработала: состояние потеряно, объект нужно пересоздать. */
#define FL_ERR_PANIC           8
/*
 * Хранилище ключей нечитаемо: файл повреждён, обрезан или записан другой версией
 * ядра. Клиент обязан показать это пользователю и НЕ заменять ключи: новая пара
 * ключей означала бы, что все ранее доверенные устройства стали чужими.
 */
#define FL_ERR_STORAGE         9

/* Размер буфера публичных ключей, который заполняют функции хранилища. */
#define FL_PUBLIC_KEYS_LEN 96u

/* Событие для колбэка клиента. Значения стабильны, новые только добавляются. */
typedef enum {
    FL_EVENT_NONE          = 0,
    FL_EVENT_DEVICE_FOUND  = 1,
    FL_EVENT_DEVICE_LOST   = 2,
    FL_EVENT_READY         = 3,
    FL_EVENT_CLIPBOARD     = 4,
    FL_EVENT_NOTIFICATION  = 5,
    FL_EVENT_SCREEN_FRAME  = 6
} fl_event_type;

/*
 * Колбэк клиента. Вызывается из потока ядра, не из UI-потока клиента:
 * передача в UI обязана быть собственной работой клиента.
 *
 * Возвращаемое значение игнорируется: клиент не может отменить событие.
 */
typedef void (*fl_callback)(void *context, fl_event_type event, const uint8_t *data, size_t len);

/*
 * Длина файла ключей. Клиент вызывает до выделения буфера: значение фиксировано
 * и не зависит от версии, поэтому достаточно одного вызова за жизнь процесса.
 */
size_t fl_keys_blob_len(void);

/*
 * Сессия между двумя устройствами.
 *
 * Сессия живёт дольше одного вызова, поэтому клиенту нужен хэндл. Хэндл
 * проверяется на каждом вызове: клиент держит его в своих переменных и вполне
 * может отдать освобождённый — это даёт FL_ERR_INVALID_ARG, а не чужой объект.
 *
 * Рукопожатие Noise XX — это три сообщения, а не два:
 *   инициатор -> e
 *   ответчик  <- e, ee
 *   инициатор -> s, se
 * Ответчик после чтения сразу пишет своё, отдельного шага «прочитал» нет.
 *
 * Важно о безопасности: рукопожатие само по себе НЕ определяет, кто перед нами.
 * Первое сообщение несёт только эфемерный ключ, поэтому ответчик принимает его
 * от любого, кто подключился. Личность связывается на третьем сообщении, а
 * сверяется подписью Hello поверх уже установленного канала. Отсюда правило:
 * fl_hello_verify зовётся на КАЖДОМ соединении, а не только при первом
 * сопряжении.
 */

/* Создаёт сессию по сохранённым ключам устройства. */
fl_status fl_session_create(const uint8_t *blob, size_t blob_len,
                            uint32_t role, uint64_t *out_handle);

/*
 * Один шаг рукопожатия.
 *
 * Пустой input означает «говори сам», непустой — «продолжай после полученного».
 * В out_step кладётся FL_STEP_SEND, FL_STEP_WAIT или FL_STEP_COMPLETE.
 *
 * out_data вместе с out_len: когда отправлять нечего, out_len равен нулю и
 * out_data можно передать NULL. Чтобы узнать нужный размер, зови с out_data =
 * NULL — вернётся длина и FL_ERR_INVALID_ARG, если длина ненулевая.
 */
fl_status fl_session_step(uint64_t handle,
                          const uint8_t *input, size_t input_len,
                          size_t *out_step,
                          uint8_t *out_data, size_t *out_len);

/* Готова ли сессия к обмену: 1 — да, 0 — ещё рукопожатие. */
fl_status fl_session_ready(uint64_t handle, uint32_t *out_ready);

/*
 * Переводит сессию из рукопожатия в обмен.
 *
 * Переход явный и ровно один раз. До него fl_session_seal и fl_session_open
 * возвращают FL_ERR_CRYPTO, а повторный вызов — тоже: состояние ушло внутрь
 * snow, второй раз брать нечего.
 */
fl_status fl_session_finish(uint64_t handle);

/* Шифрует и расшифровывает сообщение. */
fl_status fl_session_seal(uint64_t handle,
                          const uint8_t *payload, size_t payload_len,
                          uint8_t *out_frame, size_t *out_len);
fl_status fl_session_open(uint64_t handle,
                          const uint8_t *frame, size_t frame_len,
                          uint8_t *out_payload, size_t *out_len);

/*
 * Освобождает сессию.
 *
 * Повторный вызов с тем же хэндл даёт FL_ERR_INVALID_ARG, а не паникует: клиент
 * вправе освободить объект в обработчике ошибки, не зная, что он уже освобождён.
 */
fl_status fl_session_free(uint64_t handle);

/*
 * Собирает Hello устройства.
 *
 * Отдельная функция, а не часть создания сессии: Hello ходит по открытому
 * каналу обнаружения и должен быть готов до того, как напарник подключился.
 * Имя приходит UTF-8 длиной name_len.
 */
fl_status fl_hello_build(const uint8_t *blob, size_t blob_len,
                         const uint8_t *display_name, size_t name_len,
                         uint64_t nonce, uint64_t unix_time_ms,
                         uint8_t *out_data, size_t *out_len);

/*
 * Разбирает и проверяет Hello полученного устройства.
 *
 * Проверяется и подпись, и соответствие ключу из файла: устройство подписало бы
 * чужим ключом и прошло верификацию, если бы сверялась только подпись.
 *
 * Идентификатор и имя возвращаются готовыми, чтобы клиенту не пришлось заводить
 * второго разборщика протокола — а он разошёлся бы с первым.
 */
fl_status fl_hello_verify(const uint8_t *expected_blob, size_t expected_blob_len,
                          const uint8_t *body, size_t body_len,
                          uint8_t *out_id, size_t *out_id_len,
                          uint8_t *out_name, size_t *out_name_len);

/*
 * Создаёт новый набор ключей и отдаёт его для сохранения.
 *
 * Вызывается РОВНО ОДИН раз за жизнь устройства. При следующем запуске клиент
 * обязан загрузить сохранённые байты через fl_keys_load. Второй вызов на том же
 * устройстве создаст другое устройство с другим device_id, и всё накопленное
 * доверие перестанет существовать.
 *
 * Ядро не пишет файлы само: хранилище у Android, Windows и Linux устроено
 * по-разному. Клиент кладёт байты в приватное место приложения.
 *
 * out_blob получает fl_keys_blob_len() байт, out_public — FL_PUBLIC_KEYS_LEN.
 * Одноразовый ключ qr в файл не входит и выдаётся отдельно при сопряжении.
 */
fl_status fl_keys_create(uint8_t *out_blob, uint8_t *out_public);

/*
 * Загружает ключи из сохранённого файла и отдаёт публичные ключи.
 *
 * Возвращает FL_ERR_STORAGE, если файл повреждён, обрезан, записан другой
 * версией ядра или имеет чужое начало файла. Клиент обязан сообщить об этом
 * пользователю и не создавать новые ключи молча.
 *
 * out_public получает FL_PUBLIC_KEYS_LEN байт: signing (32), static (32),
 * qr (32, нули если ключ уже использован).
 */
fl_status fl_keys_load(const uint8_t *blob, size_t blob_len, uint8_t *out_public);

/*
 * Идентификатор устройства из сохранённого файла ключей.
 *
 * Приватные ключи не восстанавливаются и наружу не отдаются: вычисляется только
 * публичный signing-ключ. Нужен там, где ключи в памяти держать незачем, —
 * например для сверки по QR при сопряжении.
 */
fl_status fl_keys_device_id(const uint8_t *blob, size_t blob_len,
                            uint8_t *out_id, size_t *out_len);

/*
 * Генерирует ключи устройства.
 *
 * Устаревшая функция, оставлена ради совместимости с уже выпущенными клиентами.
 * Ключи из неё нигде не сохраняются, поэтому повторный вызов даёт новое
 * устройство. Новым клиентам использовать fl_keys_create и fl_keys_load.
 */
fl_status fl_init(uint8_t *out_public, size_t *out_len);

/*
 * Вычисляет device_id из публичного signing-ключа.
 *
 * out_id должен быть не меньше FL_DEVICE_ID_MAX байт. Длина пишется в out_len.
 * Нужен клиенту, чтобы показать идентификатор для сверки при сопряжении.
 */
fl_status fl_device_id(const uint8_t *signing_public, uint8_t *out_id, size_t *out_len);

/*
 * Проверяет подпись рукопожатия.
 *
 * Возвращает FL_OK либо FL_ERR_NOT_TRUSTED. Клиент обязан вызывать её на каждом
 * установлении соединения, а не только при первом сопряжении: объявление себя
 * чужим устройством в сети подделывается тривиально.
 */
fl_status fl_verify_signature(const uint8_t *signing_public,
                              const uint8_t *tape, size_t tape_len,
                              const uint8_t *signature);

/* Освобождает буфер, выделенный ядром. Вызывается ровно один раз. */
void fl_free(uint8_t *ptr, size_t len);

/* Версия ABI этой сборки библиотеки. */
uint32_t fl_abi_version(void);

#ifdef __cplusplus
}
#endif

#endif /* BRAIDWORK_CORE_H */