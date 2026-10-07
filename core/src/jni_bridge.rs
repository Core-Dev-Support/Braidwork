//! Мост к JVM для Android-клиента.
//!
//! Мост живёт в ядре, а не в отдельной библиотеке. Причина: динамический
//! загрузчик открывает библиотеки через `System.loadLibrary` с флагом
//! `RTLD_LOCAL`, поэтому символы из первой библиотеки не видны второй.
//! Лишние `--unresolved-symbols=ignore-all` разрешали только линковку, а на
//! устройстве падало с `UnsatisfiedLinkError`.
//!
//! Одна библиотека снимает класс ошибок целиком: и CMake, и порядок загрузки,
//! и зависимость от того, что вторая `.so` оказалась рядом.
//!
//! Модуль собирается только под Android: на десктопе его не существует.

use jni::objects::{JByteArray, JClass, JIntArray, JLongArray, JString};
use jni::sys::{jboolean, jint, jlong};
use jni::JNIEnv;

use crate::abi_transport::DEVICE_ID_BYTES;
use crate::{
    fl_device_id, fl_init, fl_keys_blob_len, fl_keys_create, fl_keys_device_id,
    fl_keys_fingerprint, fl_keys_load, fl_transport_lan_start, fl_transport_poll,
    fl_transport_port, fl_verify_signature, FlHandle, FlTransEvent, FL_EV_STATE, FL_OK,
    PUBLIC_KEYS_LEN,
};

// Длина идентификатора приходит из bi_transport, а не пишется числом.
//
// clippy для хоста его **не проверяют**: ошибка всплыла бы только на CI.
// Расхождение с числом в структуре события дало бы не ошибку сборки, а тихо
// испорченные данные — худший вид поломки.

/// `jni` работает со знаковыми байтами: это тип Android, а не наш выбор.
///
/// Приведение делается в одном месте, чтобы в коде моста не было разнобоя
/// между тем, что считает Rust, и тем, что ждёт JVM.
fn to_signed(bytes: &[u8]) -> Vec<i8> {
    bytes.iter().map(|b| *b as i8).collect()
}

/// Генерирует ключи устройства.
///
/// В имени символа подчёркивание пакета `naua_braidwork` заменено на `_1`.
/// JNI требует именно так: в имени класса подчёркивание означает экранирование
/// следующего символа, поэтому обычное `naua_braidwork` ищется как
/// `naua_1braidwork` и не совпадает ни с одним экспортируемым символом.
///
/// Экранирование нашло применение на живом устройстве: `UnsatisfiedLinkError`
/// со списком всех трёх имён, которые JVM перебирал.
///
/// Имена методов обязаны совпадать с тем, что объявлено в `BraidworkCore.kt`.
/// При переименовании метода в Kotlin править нужно здесь же, иначе будет
/// `UnsatisfiedLinkError` с невнятным текстом.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeInit<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    out_public: JByteArray<'local>,
    out_len: JIntArray<'local>,
) -> jint {
    let mut buffer = vec![0u8; PUBLIC_KEYS_LEN];
    let mut len = 0usize;

    let status = unsafe { fl_init(buffer.as_mut_ptr(), &mut len) };
    if status != FL_OK {
        return status;
    }

    if env
        .set_byte_array_region(&out_public, 0, &to_signed(&buffer[..len]))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(&out_len, 0, &[len as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Создаёт новый набор ключей и отдаёт файл для сохранения.
///
/// Вызывается ровно один раз за жизнь устройства. При следующем запуске клиент
/// обязан отдать сохранённые байты в `nativeKeysLoad`: второй вызов создал бы
/// другое устройство с другим `device_id`, и всё накопленное доверие исчезло бы
/// без единого сообщения.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeKeysCreate<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    out_blob: JByteArray<'local>,
    out_public: JByteArray<'local>,
) -> jint {
    let mut blob = vec![0u8; fl_keys_blob_len()];
    let mut public = vec![0u8; PUBLIC_KEYS_LEN];

    let status = unsafe { fl_keys_create(blob.as_mut_ptr(), public.as_mut_ptr()) };
    if status != FL_OK {
        return status;
    }

    if env
        .set_byte_array_region(&out_blob, 0, &to_signed(&blob))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_byte_array_region(&out_public, 0, &to_signed(&public))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Загружает ключи из сохранённого файла.
///
/// Возвращает `FL_ERR_STORAGE`, если файл нечитаем. Клиент обязан показать это
/// пользователю и не создавать новые ключи: подмена ключей молча уничтожила бы
/// доверие ко всем ранее сопряжённым устройствам.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeKeysLoad<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    blob: JByteArray<'local>,
    out_public: JByteArray<'local>,
) -> jint {
    let stored = match env.convert_byte_array(&blob) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_STORAGE,
    };

    let mut public = vec![0u8; PUBLIC_KEYS_LEN];
    let status = unsafe { fl_keys_load(stored.as_ptr(), stored.len(), public.as_mut_ptr()) };
    if status != FL_OK {
        return status;
    }

    if env
        .set_byte_array_region(&out_public, 0, &to_signed(&public))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Собственный отпечаток ключа подписи: см. `fl_keys_fingerprint` в `lib.rs`.
///
/// Приватные ключи наружу не отдаются: наружу уходит только строка, которую
/// человек читает вслух соседу.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeKeysFingerprint<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    blob: JByteArray<'local>,
    out_fp: JByteArray<'local>,
    out_len: JIntArray<'local>,
) -> jint {
    let stored = match env.convert_byte_array(&blob) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_STORAGE,
    };

    let mut buffer = vec![0u8; crate::FINGERPRINT_MAX];
    let mut len = 0usize;

    let status = unsafe {
        fl_keys_fingerprint(stored.as_ptr(), stored.len(), buffer.as_mut_ptr(), &mut len)
    };
    if status != FL_OK {
        return status;
    }

    if env
        .set_byte_array_region(&out_fp, 0, &to_signed(&buffer[..len]))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(&out_len, 0, &[len as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Идентификатор устройства из сохранённого файла ключей.
///
/// Приватные ключи наружу не отдаются и в память клиента не попадают.
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeKeysDeviceId<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    blob: JByteArray<'local>,
    out_id: JByteArray<'local>,
    out_len: JIntArray<'local>,
) -> jint {
    let stored = match env.convert_byte_array(&blob) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_STORAGE,
    };

    let mut buffer = vec![0u8; crate::DEVICE_ID_MAX];
    let mut len = 0usize;

    let status =
        unsafe { fl_keys_device_id(stored.as_ptr(), stored.len(), buffer.as_mut_ptr(), &mut len) };
    if status != FL_OK {
        return status;
    }

    if env
        .set_byte_array_region(&out_id, 0, &to_signed(&buffer[..len]))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(&out_len, 0, &[len as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Вычисляет `device_id` по публичному ключу подписи.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeDeviceId<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    signing_public: JByteArray<'local>,
    out_id: JByteArray<'local>,
    out_len: JIntArray<'local>,
) -> jint {
    let signing = match env.convert_byte_array(&signing_public) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };
    let key: [u8; 32] = match signing.as_slice().try_into() {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    let mut buffer = vec![0u8; crate::DEVICE_ID_MAX];
    let mut len = 0usize;

    let status = unsafe { fl_device_id(key.as_ptr(), buffer.as_mut_ptr(), &mut len) };
    if status != FL_OK {
        return status;
    }

    if env
        .set_byte_array_region(&out_id, 0, &to_signed(&buffer[..len]))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(&out_len, 0, &[len as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Проверяет подпись рукопожатия.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
///
/// Вызывается на каждом соединении, поэтому кешировать результат нельзя:
/// кеш означал бы, что поддеванное устройство пройдёт один раз.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeVerifySignature<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    signing_public: JByteArray<'local>,
    tape: JByteArray<'local>,
    signature: JByteArray<'local>,
) -> jint {
    let signing = match env.convert_byte_array(&signing_public) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };
    let message = match env.convert_byte_array(&tape) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };
    let sig = match env.convert_byte_array(&signature) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    let key: [u8; 32] = match signing.as_slice().try_into() {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };
    let sign: [u8; 64] = match sig.as_slice().try_into() {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    unsafe { fl_verify_signature(key.as_ptr(), message.as_ptr(), message.len(), sign.as_ptr()) }
}

/// Поднимает канал локальной сети.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
///
/// # Привязка по порядку
///
/// Дальше идут четыре функции: `nativeTransportPoll`, `nativeTransportPort`,
/// `nativeTransportStop`, `nativeTransportFree`. Имена в Kotlin и здесь должны
/// совпадать побуквенно, иначе `UnsatisfiedLinkError`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportStart<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    blob: JByteArray<'local>,
    name: JByteArray<'local>,
    platform: JByteArray<'local>,
    port: jint,
    out_handle: JLongArray<'local>,
) -> jint {
    let keys = match env.convert_byte_array(&blob) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_STORAGE,
    };
    let имя = match env.convert_byte_array(&name) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    let платформа = env.convert_byte_array(&platform).unwrap_or_default();

    let mut handle = FlHandle(0);
    let status = unsafe {
        fl_transport_lan_start(
            keys.as_ptr(),
            keys.len(),
            имя.as_ptr(),
            имя.len(),
            платформа.as_ptr(),
            платформа.len(),
            port.clamp(0, u16::MAX as jint) as u16,
            &mut handle,
        )
    };
    if status != FL_OK {
        return status;
    }

    if env
        .set_long_array_region(&out_handle, 0, &[handle.0 as jlong])
        .is_err()
    {
        // Хэндл уже выдан и реестр его держит. Отдать его наружу нечем, но
        // освободить надо: иначе клиент не знает, что транспорт жив, и слот
        // останется занят до перезапуска процесса.
        let _ = crate::fl_transport_free(handle);
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Забирает события транспорта.
///
/// События отдаются **параллельными массивами**, а не структурой: клиент на
/// Android разместил бы `FlTransEvent` у себя и получил бы две копии её
/// раскладки — в Rust и в Kotlin. Расхождение выглядело бы как «события приходят
/// с мусором», и искать пришлось бы в интерфейсе, а не в ядре. Так раскладка
/// знает только Rust.
///
/// Соответствие полей и событий:
///
/// * `out_type`    — что произошло, одно из `FL_EV_*`;
/// * `out_conn`    — хэндл соединения, ноль там, где соединения нет;
/// * `out_kind`    — тип сообщения для `FL_EV_FRAME` **или** состояние
///                   транспорта для `FL_EV_STATE`. Одновременно они не нужны:
///                   у кадра есть тип, у состояния его нет;
/// * `out_aux`     — порт напарника для `FL_EV_DISCOVERED` **или** код ошибки
///                   для `FL_EV_NOTICE`. По той же причине не одновременно;
/// * `out_device`  — 16 байт идентификатора для `FL_EV_DISCOVERED`, иначе нули;
/// * `out_off`     — смещение тела в `out_payload`;
/// * `out_plen`    — длина тела, столько же элементов, сколько событий;
/// * `out_payload` — тела кадров подряд.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportPoll<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    out_type: JIntArray<'local>,
    out_conn: JLongArray<'local>,
    out_kind: JIntArray<'local>,
    out_aux: JIntArray<'local>,
    out_device: JByteArray<'local>,
    out_ip: JByteArray<'local>,
    out_off: JIntArray<'local>,
    out_plen: JIntArray<'local>,
    out_payload: JByteArray<'local>,
    out_count: JIntArray<'local>,
) -> jint {
    // Мест под события: клиент решает сам, сколько ему нужно. Больше, чем он
    // приготовил, ядро не пишет — писать мимо чужого массива нельзя.
    let максимум = match env.get_array_length(&out_type) {
        Ok(v) => v as usize,
        Err(_) => return crate::FL_ERR_INTERNAL,
    };
    // Длины тел обязаны быть столько же, сколько событий: без них второе тело
    // пришло бы с конца буфера, и клиент отдал бы чужой кадр.
    match env.get_array_length(&out_plen) {
        Ok(v) if v as usize == максимум => {}
        _ => return crate::FL_ERR_INTERNAL,
    }
    let мест_под_тела = match env.get_array_length(&out_payload) {
        Ok(v) => v as usize,
        Err(_) => return crate::FL_ERR_INTERNAL,
    };

    let mut типы = vec![0i32; максимум];
    let mut коннекты = vec![0i64; максимум];
    let mut виды = vec![0i32; максимум];
    let mut вспомогательные = vec![0i32; максимум];
    let mut идентификаторы = vec![0i8; максимум * DEVICE_ID_BYTES];
    let mut адреса = vec![0i8; максимум * 4];
    let mut смещения = vec![0i32; максимум];
    let mut длины = vec![0i32; максимум];
    let mut тела = vec![0u8; мест_под_тела];
    let mut событий = vec![FlTransEvent::default(); максимум];

    let mut count = 0usize;
    let mut used = 0usize;

    let status = unsafe {
        fl_transport_poll(
            FlHandle(handle as u64),
            if максимум == 0 {
                std::ptr::null_mut()
            } else {
                событий.as_mut_ptr()
            },
            максимум,
            if мест_под_тела == 0 {
                std::ptr::null_mut()
            } else {
                тела.as_mut_ptr()
            },
            мест_под_тела,
            &mut count,
            &mut used,
        )
    };
    if status != FL_OK {
        return status;
    }

    // Разбор раскладки — здесь, а не в клиенте.
    for (i, событие) in событий.iter().take(count).enumerate() {
        типы[i] = событие.event_type as i32;
        коннекты[i] = событие.conn as i64;
        // Состояние и тип сообщения не встречаются вместе, поэтому делят поле.
        виды[i] = if событие.event_type == FL_EV_STATE {
            событие.state as i32
        } else {
            событие.kind as i32
        };
        вспомогательные[i] = if событие.event_type == crate::FL_EV_NOTICE {
            событие.error
        } else {
            событие.port as i32
        };
        for j in 0..4usize {
            адреса[i * 4 + j] = событие.ipv4[j] as i8;
        }
        смещения[i] = событие.payload_off as i32;
        длины[i] = событие.payload_len as i32;

        // Идентификатор в структуре лежит u32 в младшем порядке, по четыре
        // байта на каждый. Раскладку знает только этот файл: в Kotlin её нет.
        //
        // Размеры взяты у DEVICE_ID_BYTES, а не написаны числами: при
        // расширении поля здесь и в Kotlin разошлись бы молча, а ошибка
        // была бы в данных, а не в сборке.
        let блок = i * DEVICE_ID_BYTES;
        for k in 0..(DEVICE_ID_BYTES / 4) {
            let октет = событие.device_id[k].to_le_bytes();
            for j in 0..4usize {
                идентификаторы[блок + k * 4 + j] = октет[j] as i8;
            }
        }
    }

    // Идентификатор значим только для обнаружения: у остальных событий он нулей.
    //
    // Адрес, в отличие от него, заполняется почти у всех событий соединения:
    // без него входящее соединение невозможно связать с устройством из списка,
    // а при закрытии нечего показать, кроме «соединение закрыто». Нули у
    // событий, у которых адреса нет вовсе, и клиент это различает.
    if env
        .set_byte_array_region(&out_device, 0, &идентификаторы[..count * DEVICE_ID_BYTES])
        .is_err()
        || env
            .set_byte_array_region(&out_ip, 0, &адреса[..count * 4])
            .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    if env
        .set_int_array_region(&out_type, 0, &типы[..count])
        .is_err()
        || env
            .set_long_array_region(&out_conn, 0, &коннекты[..count])
            .is_err()
        || env
            .set_int_array_region(&out_kind, 0, &виды[..count])
            .is_err()
        || env
            .set_int_array_region(&out_aux, 0, &вспомогательные[..count])
            .is_err()
        || env
            .set_int_array_region(&out_off, 0, &смещения[..count])
            .is_err()
        || env
            .set_int_array_region(&out_plen, 0, &длины[..count])
            .is_err()
        || env
            .set_int_array_region(&out_count, 0, &[count as i32])
            .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    if used > 0
        && env
            .set_byte_array_region(&out_payload, 0, &to_signed(&тела[..used]))
            .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Подключается к устройству в локальной сети.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportConnect<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    ipv4: JByteArray<'local>,
    port: jint,
    out_conn: JLongArray<'local>,
) -> jint {
    let адрес = match env.convert_byte_array(&ipv4) {
        Ok(v) if v.len() == 4 => v,
        Ok(_) => return crate::FL_ERR_INVALID_ARG,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    let mut conn = FlHandle(0);
    // Порт ограничивается здесь, а не в клиенте: клиент мог бы прислать
    // отрицательное число, и `as u16` тихо превратил бы его в 65 тысяч.
    if !(1..=u16::MAX as jint).contains(&port) {
        return crate::FL_ERR_INVALID_ARG;
    }

    let status = unsafe {
        crate::fl_transport_connect(
            FlHandle(handle as u64),
            адрес.as_ptr(),
            port as u16,
            &mut conn,
        )
    };
    if status != FL_OK {
        return status;
    }

    if env
        .set_long_array_region(&out_conn, 0, &[conn.0 as jlong])
        .is_err()
    {
        // Соединение уже создано и держится в транспорте. Отдать хэндл нечем, а
        // оставить соединение жить — значит забыть про него навсегда: сокет
        // останется открытым до конца процесса.
        crate::fl_transport_conn_close(FlHandle(handle as u64), conn.0);
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Отправляет готовый кадр в соединение.
///
/// Первая отправка через шифрованный канал. До сих пор `fl_transport_send`
/// существовал в C ABI, но до клиента не доходил: отправлять было нечем, и
/// канал поднимался, оставаясь пустым.
///
/// На входе целый кадр из `fl_heartbeat_build` или `fl_clipboard_build`, а не
/// тело. Тип сообщения объявляется один раз, в своём заголовке: иначе
/// получателю пришлось бы угадывать его по содержимому.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportSend<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    conn: jlong,
    frame: JByteArray<'local>,
) -> jint {
    let bytes = match env.convert_byte_array(&frame) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    unsafe {
        crate::fl_transport_send(
            FlHandle(handle as u64),
            conn as u64,
            bytes.as_ptr(),
            bytes.len(),
        )
    }
}

/// Собирает кадр `Heartbeat` из настоящих значений устройства.
///
/// Ошибка возвращается кодом, а не пустым массивом: молчаливая пустота была бы
/// неотличима от «сообщение не собралось», и отправка выглядела бы успешной.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeHeartbeatBuild<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    unix_time_ms: jlong,
    battery_percent: jint,
    charging: jboolean,
    out: JByteArray<'local>,
    out_len: JIntArray<'local>,
) -> jint {
    if out_len.is_null() {
        return crate::FL_ERR_INVALID_ARG;
    }
    // Заряд вне 0..100 отвергается в ядре, а не здесь: проверка в двух местах
    // разъехалась бы при первом же изменении границы.
    let mut мест = match env.get_array_length(&out) {
        Ok(v) => v as usize,
        Err(_) => return crate::FL_ERR_INTERNAL,
    };
    let mut буфер = vec![0u8; мест.max(1)];

    let status = unsafe {
        crate::fl_heartbeat_build(
            unix_time_ms as u64,
            // Отрицательный заряд превращается в огромное число и отвергается
            // ядром тем же правилом, что и 101. Подстраховывать здесь нечем:
            // проверка в двух местах разъехалась бы при первом же изменении
            // границы, и одна из них осталась бы старой.
            battery_percent as u32,
            // `jboolean` — это `u8`, и `bool::from(u8)` не существует.
            // Ненулевое значение означает истину, как и в самом JNI.
            charging != 0,
            буфер.as_mut_ptr(),
            &mut мест,
        )
    };
    if status != FL_OK {
        return status;
    }

    let срез = буфер[..мест.min(буфер.len())].to_vec();
    if env
        .set_byte_array_region(&out, 0, &to_signed(&срез))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(&out_len, 0, &[мест as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Разбирает тело `Heartbeat` в значения для показа.
///
/// Кладёт в `out` три числа: время, заряд, зарядка (1 или 0). Непризнанное тело
/// даёт ненулевой код, а не исключение: чужое сообщение не должно ронять цикл
/// опроса событий, иначе одно незнакомое сообщение остановило бы всю связь.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeHeartbeatRead<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    body: JByteArray<'local>,
    out: JLongArray<'local>,
) -> jint {
    let bytes = match env.convert_byte_array(&body) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    let mut время = 0u64;
    let mut заряд = 0u32;
    let mut зарядка = 0u32;
    let status = unsafe {
        crate::fl_heartbeat_read(
            bytes.as_ptr(),
            bytes.len(),
            &mut время,
            &mut заряд,
            &mut зарядка,
        )
    };
    if status != FL_OK {
        return status;
    }

    let числа = [время as jlong, заряд as jlong, зарядка as jlong];
    if env.set_long_array_region(&out, 0, &числа).is_err() {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Собирает кадр `ClipboardContent` из содержимого буфера.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeClipboardBuild<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    content: JByteArray<'local>,
    mime_type: JString<'local>,
    out: JByteArray<'local>,
    out_len: JIntArray<'local>,
) -> jint {
    if out_len.is_null() {
        return crate::FL_ERR_INVALID_ARG;
    }
    let текст = match env.convert_byte_array(&content) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };
    // Тип содержимого — ASCII по определению («text/plain»), поэтому
    // преобразование в строку безопасно. Содержимое сюда не попадает: оно идёт
    // байтами и проверяется на UTF-8 в ядре.
    let mime: String = match env.get_string(&mime_type) {
        Ok(s) => s.into(),
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    let mut мест = match env.get_array_length(&out) {
        Ok(v) => v as usize,
        Err(_) => return crate::FL_ERR_INTERNAL,
    };
    let mut буфер = vec![0u8; мест.max(1)];

    let status = unsafe {
        crate::fl_clipboard_build(
            текст.as_ptr(),
            текст.len(),
            mime.as_ptr(),
            mime.len(),
            буфер.as_mut_ptr(),
            &mut мест,
        )
    };
    if status != FL_OK {
        return status;
    }

    let срез = буфер[..мест.min(буфер.len())].to_vec();
    if env
        .set_byte_array_region(&out, 0, &to_signed(&срез))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(&out_len, 0, &[мест as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}

/// Разбирает тело `ClipboardContent` в содержимое и тип.
///
/// Буферы выделяет клиент, длины приходят в отдельные массивы. Ненулевой код
/// означает «тело не то», а не «связь плохая».
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeClipboardRead<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    body: JByteArray<'local>,
    out_content: JByteArray<'local>,
    out_content_len: JIntArray<'local>,
    out_mime: JByteArray<'local>,
    out_mime_len: JIntArray<'local>,
) -> jint {
    if out_content_len.is_null() || out_mime_len.is_null() {
        return crate::FL_ERR_INVALID_ARG;
    }
    let bytes = match env.convert_byte_array(&body) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    let содержимое_cap = match env.get_array_length(&out_content) {
        Ok(v) => v as usize,
        Err(_) => return crate::FL_ERR_INTERNAL,
    };
    let mime_cap = match env.get_array_length(&out_mime) {
        Ok(v) => v as usize,
        Err(_) => return crate::FL_ERR_INTERNAL,
    };
    let mut содержимое = vec![0u8; содержимое_cap.max(1)];
    let mut mime = vec![0u8; mime_cap.max(1)];
    let mut длина = 0usize;
    let mut mime_len = 0usize;

    let status = unsafe {
        crate::fl_clipboard_read(
            bytes.as_ptr(),
            bytes.len(),
            содержимое.as_mut_ptr(),
            содержимое_cap,
            &mut длина,
            mime.as_mut_ptr(),
            mime_cap,
            &mut mime_len,
        )
    };
    if status != FL_OK {
        return status;
    }

    let тело = содержимое[..длина.min(содержимое.len())].to_vec();
    let тип = mime[..mime_len.min(mime.len())].to_vec();
    if env
        .set_byte_array_region(&out_content, 0, &to_signed(&тело))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_byte_array_region(&out_mime, 0, &to_signed(&тип))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(out_content_len, 0, &[длина as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(out_mime_len, 0, &[mime_len as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}
/// Собирает кадр из готового protobuf-тела.
///
/// Клиент не собирает заголовок сам: раскладку заголовка знает только ядро, а
/// второй разборщик рано или поздно разошёлся бы с первым. Поломка выглядела бы
/// как «устройство не отвечает», и искать пришлось бы в интерфейсе.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeFrameEncode<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    kind: jint,
    flags: jint,
    body: JByteArray<'local>,
    out: JByteArray<'local>,
    out_len: JIntArray<'local>,
) -> jint {
    let тело = match env.convert_byte_array(&body) {
        Ok(v) => v,
        Err(_) => return crate::FL_ERR_INVALID_ARG,
    };

    // Клиент сообщает, сколько мест он приготовил, и ядро проверяет это само:
    // довериться длине из Kotlin значило бы поверить, что клиент ошибся лучше
    // него самого.
    let мест = match env.get_array_length(&out) {
        Ok(v) => v as usize,
        Err(_) => return crate::FL_ERR_INTERNAL,
    };
    let mut буфер = vec![0u8; мест];
    let mut len = мест;

    let status = unsafe {
        crate::fl_frame_encode(
            kind as u32,
            flags as u16,
            тело.as_ptr(),
            тело.len(),
            буфер.as_mut_ptr(),
            &mut len,
        )
    };
    if status != FL_OK {
        // Ядро сообщило нужную длину в out_len: клиент перевыделит и позовёт
        // снова. Молча обрезанный кадр хуже отказа — клиент отправил бы
        // половину файла и счёл бы передачу успешной.
        if env
            .set_int_array_region(&out_len, 0, &[len as jint])
            .is_err()
        {
            return crate::FL_ERR_INTERNAL;
        }
        return status;
    }

    if env
        .set_byte_array_region(&out, 0, &to_signed(&буфер[..len]))
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }
    if env
        .set_int_array_region(&out_len, 0, &[len as jint])
        .is_err()
    {
        return crate::FL_ERR_INTERNAL;
    }

    FL_OK
}
/// Порт, на котором транспорт принимает соединения.
///
/// Ноль означает «транспорта нет».
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportPort<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    fl_transport_port(FlHandle(handle as u64)) as jint
}

/// Останавливает и освобождает транспорт.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportStop<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    crate::fl_transport_stop(FlHandle(handle as u64))
}

/// Освобождает транспорт.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportFree<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    crate::fl_transport_free(FlHandle(handle as u64))
}

/// Транспорт жив, если он зарегистрирован в ядре.
///
/// Отдельный вызов, потому что порт и состояние читаются у уже освобождённого
/// хэндла как у чужого, и клиент отличил бы «освободил» от «ещё работает» только
/// по тому, что счётчик у него сошёлся. Счётчик — это его собственное
/// предположение, а ядро знает точно.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeTransportAlive<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
) -> jint {
    match crate::fl_transport_port(FlHandle(handle as u64)) {
        0 => 0,
        _ => 1,
    }
}

/// Размер структуры события — клиент сверяет его со своим, чтобы замететь
/// расхождение версий заголовка.
///
/// Экранирование подчёркивания объяснено у `nativeInit`.
#[no_mangle]
pub extern "system" fn Java_com_naua_1braidwork_app_BraidworkCore_nativeEventSize<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
) -> jint {
    crate::fl_transport_event_size() as jint
}
