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

use jni::objects::{JByteArray, JClass, JIntArray};
use jni::sys::jint;
use jni::JNIEnv;

use crate::{
    fl_device_id, fl_init, fl_keys_blob_len, fl_keys_create, fl_keys_device_id, fl_keys_load,
    fl_verify_signature, FL_OK, PUBLIC_KEYS_LEN,
};

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
