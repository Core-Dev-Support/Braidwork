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

/* Размер буфера публичных ключей, который заполняет fl_init. */
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
 * Генерирует ключи устройства.
 *
 * out_public получает FL_PUBLIC_KEYS_LEN байт: signing (32), затем static (32),
 * затем одноразовый qr (32). Клиент обязан сохранить ключи локально и никогда
 * не отправлять их в открытом виде.
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