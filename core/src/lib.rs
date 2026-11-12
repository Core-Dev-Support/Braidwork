//! Граница C ABI.
//!
//! Наружу из ядра выходит только этот модуль. Правила, нарушать которые нельзя:
//!
//! * ни одна паника не пересекает C-кадры: разматывание стека через них — UB,
//!   поэтому `catch_unwind` стоит в каждой внешней функции (D-008);
//! * функции не бывают `async`: наружу отдаётся колбэк, а не футура;
//! * `char*` всегда идёт с явным размером: Android и HarmonyOS отдают UTF-8,
//!   Windows — UTF-16, и клиент обязан знать, что именно пришло;
//! * освобождение памяти только через `fl_free`.
//!
//! Обращение к хэндлам идёт через slab со счётчиком поколений, а не через
//! глобальную карту: `OnceLock<RwLock<HashMap>>` становится бутылочным горлышком
//! и отравляется после первой же паники (D-009).

use std::panic::{catch_unwind, AssertUnwindSafe};

mod abi_session;
pub mod android_log;
// Транспорт в C ABI появляется только вместе с каналом: клиент без сети не
// должен платить за мёртвый код.
#[cfg(feature = "transport-lan")]
mod abi_transport;
#[cfg(feature = "transport-lan")]
pub mod discovery;
mod crypto;
mod frame;
mod registry;
mod session;
pub mod transport;

// Мост к JVM нужен только под Android. На десктопе клиент обращается к ядру
// через C ABI напрямую, и зависимость от `jni` была бы лишней.
#[cfg(target_os = "android")]
mod jni_bridge;

pub mod pb {
    #![allow(clippy::all)]
    include!(concat!(env!("OUT_DIR"), "/braidwork.v1.rs"));
}

pub use abi_session::{
    fl_blake3_hash, fl_blake3_hasher_create, fl_blake3_hasher_finalize, fl_blake3_hasher_free,
    fl_blake3_hasher_update, fl_clipboard_build, fl_clipboard_read, fl_file_block_build,
    fl_file_block_read, fl_file_offer_build, fl_file_offer_read, fl_heartbeat_build,
    fl_heartbeat_read, fl_hello_build, fl_hello_verify, fl_session_create, fl_session_finish,
    fl_session_free, fl_session_open, fl_session_ready, fl_session_seal, fl_session_step,
    CLIPBOARD_MAX, FILE_BLOCK_FRAME_MAX, FILE_BLOCK_MAX, FILE_OFFER_FRAME_MAX, FL_ROLE_INITIATOR,
    FL_ROLE_RESPONDER, FL_STEP_COMPLETE, FL_STEP_SEND, FL_STEP_WAIT, HASH_LEN, HEARTBEAT_FRAME_MAX,
};
#[cfg(feature = "transport-lan")]
pub use abi_transport::{
    fl_frame_encode, fl_transport_conn_close, fl_transport_connect, fl_transport_event_size,
    fl_transport_free, fl_transport_lan_start, fl_transport_outbox_len, fl_transport_poll,
    fl_transport_port, fl_transport_send, fl_transport_stop, FlTransEvent, FL_EV_CLOSED,
    FL_EV_CONNECTED, FL_EV_DISCOVERED, FL_EV_FRAME, FL_EV_INCOMING, FL_EV_NOTICE, FL_EV_STATE,
};
pub use crypto::{verify_handshake, CryptoError, DeviceId, DeviceKeys, DEVICE_KEYS_BLOB_LEN};
pub use frame::{
    encode, encode_raw, is_known_kind, split, FrameError, FrameHeader, FrameReader, FRAME_MAGIC,
    FRAME_VERSION, HEADER_LEN, MAX_BODY_LEN,
};
pub use session::{
    build_hello, default_capabilities, parse_and_verify_hello, Handshake, HandshakeStep, Role,
    Session, SessionError,
};

/// Версия протокола, которую объявляет ядро в `Hello`.
pub const PROTOCOL_VERSION: u32 = 1;

// =============================================================================
// Статус-коды
// =============================================================================

/// Код результата операции.
pub type FlStatus = i32;

pub const FL_OK: FlStatus = 0;
pub const FL_ERR_INVALID_ARG: FlStatus = 1;
pub const FL_ERR_NO_MEMORY: FlStatus = 2;
pub const FL_ERR_CRYPTO: FlStatus = 3;
pub const FL_ERR_NOT_TRUSTED: FlStatus = 4;
pub const FL_ERR_ALREADY_EXISTS: FlStatus = 5;
pub const FL_ERR_TRANSPORT: FlStatus = 6;
pub const FL_ERR_INTERNAL: FlStatus = 7;
/// Ловушка сработала. Состояние сессии потеряно, клиент обязан пересоздать её,
/// а не продолжать работу с half-инициализированным объектом.
pub const FL_ERR_PANIC: FlStatus = 8;
/// Хранилище ключей нечитаемо: файл повреждён, обрезан или записан другой
/// версией ядра. Клиент обязан показать это пользователю, а не заменить ключи:
/// новая пара ключей означала бы потерю доверия ко всем устройствам разом.
pub const FL_ERR_STORAGE: FlStatus = 9;

fn status_from_crypto(err: &CryptoError) -> FlStatus {
    match err {
        CryptoError::BadSignature | CryptoError::IdentityMismatch => FL_ERR_NOT_TRUSTED,
        CryptoError::Rejected | CryptoError::HandshakeIncomplete => FL_ERR_CRYPTO,
        // Повреждённый файл ключей — это не криптографическая ошибка, а ошибка
        // хранилища: клиент должен сообщить пользователю, что файл не читается,
        // а не предлагать создать новые ключи молча.
        CryptoError::MalformedKeys => FL_ERR_STORAGE,
    }
}

/// Обёртка внешней функции.
///
/// Ловушка обязана быть самой внешней: всё, что глубже, возвращает `Result` и
/// паникой не заканчивается.
fn guard<F: FnOnce() -> FlStatus>(f: F) -> FlStatus {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(code) => code,
        Err(_) => FL_ERR_PANIC,
    }
}

/// Обёртка внешней функции, возвращающей значение, а не статус-код.
///
/// Паника внутри превращается в `при_панике`, а не в разматывание стека через
/// C-кадр: это UB (D-008). Отдельная обёртка, потому что `guard` жёстко зашит на
/// `FlStatus`, а несколько функций возвращают числа — порт, размер структуры.
pub(crate) fn guard_value<T, F: FnOnce() -> T>(f: F, при_панике: T) -> T {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(v) => v,
        Err(_) => при_панике,
    }
}

/// Длина публичных ключей: signing (32) + static (32) + qr (32).
pub const PUBLIC_KEYS_LEN: usize = 96;

/// Максимальная длина `device_id` в байтах.
pub const DEVICE_ID_MAX: usize = 32;

/// Буфер под отпечаток ключа: 16 символов и три пробела.
///
/// С запасом, как и [DEVICE_ID_MAX]. Отпечаток получится длиннее при смене
/// алфавита или длины хэша, и обрезание повторилось бы — тихо, посреди строки,
/// которую человек читает вслух соседу.
pub const FINGERPRINT_MAX: usize = 32;

// =============================================================================
// Хранилище ключей устройства
// =============================================================================
//
// Ядро не хранит файлы само: в нём нет ни одной платформенной зависимости
// (D-001), а хранилище у Android, Windows и Linux устроено по-разному. Ядро
// отдаёт байты и принимает их обратно, клиент решает, куда их положить.
//
// Такой раздел нужен именно потому, что без него каждое открытие приложения
// выдавало новое устройство: идентификатор выводится из ключа подписи, а ключ
// нигде не сохранялся. Доверие, сопряжение по QR и транспорт на таком
// устройстве невозможны.

/// Длина файла ключей. Клиент вызывает заранее, до выделения буфера.
///
/// # Safety
/// Функция безопасна: указателей не принимает и ничего не меняет.
#[no_mangle]
pub extern "C" fn fl_keys_blob_len() -> usize {
    DEVICE_KEYS_BLOB_LEN
}

/// Создаёт новый набор ключей и отдаёт его для сохранения.
///
/// Вызывается ровно один раз за жизнь устройства: при следующем запуске
/// клиент обязан загрузить сохранённые байты через `fl_keys_import`.
/// Второй вызов на том же устройстве создаст другое устройство.
///
/// Публичные ключи (96 байт) кладёт в `out_public`, файл — в `out_blob`.
///
/// # Safety
/// `out_public` должен указывать на 96 байт, `out_blob` — на
/// `fl_keys_blob_len()` байт, `out_len` — на `usize`. Указатели валидны,
/// не пересекаются и не перекрывают входной буфер.
#[no_mangle]
pub unsafe extern "C" fn fl_keys_create(out_blob: *mut u8, out_public: *mut u8) -> FlStatus {
    guard(|| {
        if out_blob.is_null() || out_public.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let mut keys = DeviceKeys::generate();
        write_public_keys(&keys, out_public);

        // QR-ключ выдаётся отдельно и в файл не входит: он одноразовый, и
        // сохранять его рядом с постоянными ключами значило бы раздать лишний
        // секрет на все случаи жизни.
        let _ = keys.take_qr_key();
        let blob = keys.export();

        let out = std::slice::from_raw_parts_mut(out_blob, DEVICE_KEYS_BLOB_LEN);
        out.copy_from_slice(&blob);

        FL_OK
    })
}

/// Загружает ключи из сохранённого файла и отдаёт публичные ключи.
///
/// Несовпадение формата, версии или контрольной суммы — `FL_ERR_STORAGE`.
/// Клиент обязан показать это пользователю и не заменять ключи: новая пара
/// ключей означала бы, что все ранее доверенные устройства стали чужими.
///
/// # Safety
/// `blob` указывает на `blob_len` байт, `out_public` — на 96 байт. Указатели
/// валидны, читаемы и не пересекаются.
#[no_mangle]
pub unsafe extern "C" fn fl_keys_load(
    blob: *const u8,
    blob_len: usize,
    out_public: *mut u8,
) -> FlStatus {
    guard(|| {
        if blob.is_null() || out_public.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if blob_len != DEVICE_KEYS_BLOB_LEN {
            return FL_ERR_STORAGE;
        }

        let bytes = std::slice::from_raw_parts(blob, blob_len);
        let keys = match DeviceKeys::import(bytes) {
            Ok(k) => k,
            Err(e) => return status_from_crypto(&e),
        };

        write_public_keys(&keys, out_public);
        FL_OK
    })
}

/// Идентификатор устройства из сохранённого файла ключей.
///
/// Нужен там, где ключи в памяти держать нельзя или незачем: сверка по QR,
/// показ в настройках. Приватные ключи не восстанавливаются и наружу не
/// отдаются — вычисляется только публичный signing-ключ.
///
/// # Safety
/// `blob` указывает на `blob_len` байт, `out_id` — на буфер не менее
/// `DEVICE_ID_MAX` байт, `out_len` — на `usize`. Указатели валидны.
#[no_mangle]
pub unsafe extern "C" fn fl_keys_device_id(
    blob: *const u8,
    blob_len: usize,
    out_id: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if blob.is_null() || out_id.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if blob_len != DEVICE_KEYS_BLOB_LEN {
            return FL_ERR_STORAGE;
        }

        let bytes = std::slice::from_raw_parts(blob, blob_len);
        let keys = match DeviceKeys::import(bytes) {
            Ok(k) => k,
            Err(e) => return status_from_crypto(&e),
        };

        let id = keys.device_id();
        let raw = id.as_bytes();
        let out = std::slice::from_raw_parts_mut(out_id, DEVICE_ID_MAX);
        let n = raw.len().min(DEVICE_ID_MAX);
        out[..n].copy_from_slice(&raw[..n]);
        *out_len = n;

        FL_OK
    })
}

/// Отпечаток ключа подписи этого устройства: четыре группы по четыре символа.
///
/// Отдельная функция, а не вычисление на стороне клиента. Клиенту нужен
/// **собственный** отпечаток, чтобы показать его человеку для сверки с
/// отпечатком соседа, и единственный способ узнать его тот же, что и
/// [crate::crypto::fingerprint_from_signing].
///
/// Считать его в клиенте — значит завести вторую реализацию. Такие разъезжаются
/// при первом же изменении формата, и тогда сверка перестаёт работать тихо:
/// отпечаток у соседа и отпечаток у себя окажутся разными, и человек решит, что
/// его обманывают.
///
/// # Safety
/// `blob` указывает на `blob_len` байт, `out_fp` — на буфер не менее
/// `FINGERPRINT_MAX` байт, `out_len` — на `usize`. Указатели валидны.
#[no_mangle]
pub unsafe extern "C" fn fl_keys_fingerprint(
    blob: *const u8,
    blob_len: usize,
    out_fp: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if blob.is_null() || out_fp.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if blob_len != DEVICE_KEYS_BLOB_LEN {
            return FL_ERR_STORAGE;
        }

        let bytes = std::slice::from_raw_parts(blob, blob_len);
        let keys = match DeviceKeys::import(bytes) {
            Ok(k) => k,
            Err(e) => return status_from_crypto(&e),
        };

        let отпечаток = crate::crypto::fingerprint_from_signing(&keys.signing_public());
        let raw = отпечаток.as_bytes();
        let out = std::slice::from_raw_parts_mut(out_fp, FINGERPRINT_MAX);
        let n = raw.len().min(FINGERPRINT_MAX);
        out[..n].copy_from_slice(&raw[..n]);
        *out_len = n;

        FL_OK
    })
}

/// Раскладывает публичные ключи в буфер клиента: signing, static, qr.
///
/// Отдельная функция, потому что порядок полей — часть контракта, а не
/// деталь реализации: клиент на Android и клиент на Windows читают эти 96 байт
/// одинаково.
fn write_public_keys(keys: &DeviceKeys, out_public: *mut u8) {
    let out = unsafe { std::slice::from_raw_parts_mut(out_public, PUBLIC_KEYS_LEN) };
    out[..32].copy_from_slice(&keys.signing_public());
    out[32..64].copy_from_slice(&keys.static_public());
    out[64..PUBLIC_KEYS_LEN].copy_from_slice(&[0u8; 32]);
}

// =============================================================================
// Хэндлы
// =============================================================================

/// Непрозрачный дескриптор объекта.
///
/// Наружу отдаётся целое, а не указатель: указатели не переживают перемещение
/// объекта и ломают 32-битный Android. Значение составное — индекс слота плюс
/// счётчик поколений, что закрывает ABA: после освобождения и переиспользования
/// слота старый хэндл не указывает на новый объект.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
#[repr(transparent)]
pub struct FlHandle(pub u64);

// =============================================================================
// Инициализация
// =============================================================================

/// Генерирует новый набор ключей устройства.
///
/// Устройство получает ключи при первом запуске и хранит их локально.
/// `out_public` — 96 байт: signing (32) затем static (32) затем qr (32).
/// Память подqr_ключ освобождается вызовом `fl_free`.
///
/// # Safety
/// `out_public` должен указывать на буфер не менее 96 байт, `out_len` — на
/// `usize`. Оба указателя валидны и не пересекаются.
#[no_mangle]
pub unsafe extern "C" fn fl_init(out_public: *mut u8, out_len: *mut usize) -> FlStatus {
    guard(|| {
        if out_public.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let mut keys = DeviceKeys::generate();
        let signing = keys.signing_public();
        let static_public = keys.static_public();
        // QR-ключ одноразовый: клиент получает его сейчас, чтобы показать код,
        // и больше не может его взять.
        let qr = keys.take_qr_key().unwrap_or([0u8; 32]);

        let out = std::slice::from_raw_parts_mut(out_public, PUBLIC_KEYS_LEN);
        out[..32].copy_from_slice(&signing);
        out[32..64].copy_from_slice(&static_public);
        out[64..PUBLIC_KEYS_LEN].copy_from_slice(&qr);

        // Записываем длину только после успешного заполнения.
        *out_len = PUBLIC_KEYS_LEN;

        FL_OK
    })
}

/// Вычисляет `device_id` из публичного signing-ключа.
///
/// # Safety
/// `signing_public` должен указывать на 32 байта, `out_id` — на буфер не
/// менее `DEVICE_ID_MAX` байт, `out_len` — на `usize`. Указатели валидны.
#[no_mangle]
pub unsafe extern "C" fn fl_device_id(
    signing_public: *const u8,
    out_id: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if signing_public.is_null() || out_id.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let signing: [u8; 32] = match std::slice::from_raw_parts(signing_public, 32).try_into() {
            Ok(v) => v,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let id = match device_id_from_signing(&signing) {
            Ok(v) => v,
            Err(_) => return FL_ERR_CRYPTO,
        };

        let bytes = id.as_bytes();
        let out = std::slice::from_raw_parts_mut(out_id, DEVICE_ID_MAX);
        let n = bytes.len().min(DEVICE_ID_MAX);
        out[..n].copy_from_slice(&bytes[..n]);
        *out_len = n;

        FL_OK
    })
}

/// `device_id` без хранения приватного ключа: нужен клиенту для сверки QR.
fn device_id_from_signing(signing_public: &[u8; 32]) -> Result<DeviceId, CryptoError> {
    Ok(crypto::device_id_from_signing(signing_public))
}

/// Проверяет подпись рукопожатия.
///
/// # Safety
/// `signing_public` указывает на 32 байта, `signature` — на 64 байта,
/// `tape` — на `tape_len` байт. Все указатели валидны и читаемы.
#[no_mangle]
pub unsafe extern "C" fn fl_verify_signature(
    signing_public: *const u8,
    tape: *const u8,
    tape_len: usize,
    signature: *const u8,
) -> FlStatus {
    guard(|| {
        if signing_public.is_null() || tape.is_null() || signature.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if tape_len == 0 {
            return FL_ERR_INVALID_ARG;
        }

        let signing: [u8; 32] = match std::slice::from_raw_parts(signing_public, 32).try_into() {
            Ok(v) => v,
            Err(_) => return FL_ERR_INVALID_ARG,
        };
        let sig: [u8; 64] = match std::slice::from_raw_parts(signature, 64).try_into() {
            Ok(v) => v,
            Err(_) => return FL_ERR_INVALID_ARG,
        };
        let tape_slice = std::slice::from_raw_parts(tape, tape_len);

        match verify_handshake(&signing, tape_slice, &sig) {
            Ok(()) => FL_OK,
            Err(e) => status_from_crypto(&e),
        }
    })
}

/// Освобождает буфер, выделенный ядром.
///
/// Единственная допустимая точка освобождения. Вызов с другим аллокатором или
/// дважды — поведение не определено, поэтому клиент обязан звать ровно один раз.
///
/// # Safety
/// `ptr` должен быть получен из ядра, а `len` — совпадать с длиной, с которой
/// ядро этот буфер отдало. Нулевые значения допустимы и ничего не делают.
#[no_mangle]
pub unsafe extern "C" fn fl_free(ptr: *mut u8, len: usize) {
    if ptr.is_null() || len == 0 {
        return;
    }
    drop(Vec::from_raw_parts(ptr, 0, len));
}

/// Версия ABI. Клиент проверяет её при загрузке библиотеки и отказывается
/// работать при несовпадении, вместо того чтобы читать неверную память.
#[no_mangle]
pub extern "C" fn fl_abi_version() -> u32 {
    1
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::registry::{make_handle, split_handle, GENERATION_START, HANDLE_INVALID};

    #[test]
    fn статус_паники_не_пропускает_наружу() {
        // Функция, которая паникует, обязана вернуть FL_ERR_PANIC, а не уронить процесс.
        let code = guard(|| panic!("намеренно"));
        assert_eq!(code, FL_ERR_PANIC);
    }

    #[test]
    fn хэндлы_разбираются_на_слот_и_поколение() {
        let h = make_handle(1, GENERATION_START);
        let (slot, gen) = split_handle(h);
        assert_eq!(gen, GENERATION_START);
        assert_ne!(slot, 0);
        assert_ne!(h, HANDLE_INVALID);
        assert!(h.is_valid());
        assert!(!HANDLE_INVALID.is_valid());
    }

    #[test]
    fn у_разных_объектов_разные_слоты() {
        let a = make_handle(1, GENERATION_START);
        let b = make_handle(2, GENERATION_START);
        assert_ne!(split_handle(a).0, split_handle(b).0);
    }

    #[test]
    fn идентификатор_детерминирован() {
        let key = [7u8; 32];
        let a = device_id_from_signing(&key).unwrap();
        let b = device_id_from_signing(&key).unwrap();
        assert_eq!(a, b);
        // Идентификатор — текст, а не сырые байты хэша.
        assert!(!a.as_str().is_empty());
        // 16 байт хэша в base32 дают 26 символов.
        assert_eq!(a.as_bytes().len(), 26);
    }

    #[test]
    fn разные_ключи_дают_разные_идентификаторы() {
        let a = device_id_from_signing(&[1u8; 32]).unwrap();
        let b = device_id_from_signing(&[2u8; 32]).unwrap();
        assert_ne!(a, b);
    }

    /// Ключи, прошедшие через границу C ABI, должны остаться теми же.
    ///
    /// Ошибка в раскладке публичных ключей или в разборе файла выглядела бы
    /// снаружи как «устройство сменило идентификатор», и найти её можно было бы
    /// только на телефоне.
    #[test]
    fn ключи_переживают_границу_abi() {
        let mut blob = vec![0u8; DEVICE_KEYS_BLOB_LEN];
        let mut public = vec![0u8; PUBLIC_KEYS_LEN];

        let code = unsafe { fl_keys_create(blob.as_mut_ptr(), public.as_mut_ptr()) };
        assert_eq!(code, FL_OK);

        // Загружаем тем же путём, каким воспользуется клиент.
        let mut public2 = vec![0u8; PUBLIC_KEYS_LEN];
        let code = unsafe { fl_keys_load(blob.as_ptr(), blob.len(), public2.as_mut_ptr()) };
        assert_eq!(code, FL_OK);
        assert_eq!(public, public2, "публичные ключи не должны меняться");

        // Идентификатор из файла совпадает с тем, что даёт сам ключ.
        let keys = DeviceKeys::import(&blob).expect("загрузка");
        let mut id = vec![0u8; DEVICE_ID_MAX];
        let mut len = 0usize;
        let code =
            unsafe { fl_keys_device_id(blob.as_ptr(), blob.len(), id.as_mut_ptr(), &mut len) };
        assert_eq!(code, FL_OK);
        assert_eq!(&id[..len], keys.device_id().as_bytes());
    }

    #[test]
    fn повреждённый_файл_ключей_даёт_ошибку_хранилища() {
        let mut blob = vec![0u8; DEVICE_KEYS_BLOB_LEN];
        let mut public = vec![0u8; PUBLIC_KEYS_LEN];
        assert_eq!(
            unsafe { fl_keys_create(blob.as_mut_ptr(), public.as_mut_ptr()) },
            FL_OK
        );

        blob[30] ^= 0xff;
        let mut public2 = vec![0u8; PUBLIC_KEYS_LEN];
        assert_eq!(
            unsafe { fl_keys_load(blob.as_ptr(), blob.len(), public2.as_mut_ptr()) },
            FL_ERR_STORAGE
        );

        // Обрезанный файл отвергается по длине, не читая содержимого.
        assert_eq!(
            unsafe { fl_keys_load(blob.as_ptr(), 10, public2.as_mut_ptr()) },
            FL_ERR_STORAGE
        );
    }

    #[test]
    fn нулевые_указатели_отвергаются() {
        let mut public = vec![0u8; PUBLIC_KEYS_LEN];
        assert_eq!(
            unsafe { fl_keys_create(std::ptr::null_mut(), public.as_mut_ptr()) },
            FL_ERR_INVALID_ARG
        );
        assert_eq!(
            unsafe { fl_keys_create(public.as_mut_ptr(), std::ptr::null_mut()) },
            FL_ERR_INVALID_ARG
        );
    }
    /// Отпечаток устройства совпадает с тем, что видит сосед.
    ///
    /// Ровно эта пара сравнивается человеком: отпечаток соседа на экране
    /// сопряжения и отпечаток устройства у себя. Если бы они считались разными
    /// способами, сверка всегда давала бы «не совпало» — и выглядело бы так,
    /// будто обманывают.
    #[test]
    fn отпечаток_устройства_совпадает_с_чужим_взглядом() {
        let keys = DeviceKeys::generate();
        let blob = keys.export();

        let mut буфер = [0u8; FINGERPRINT_MAX];
        let mut длина = 0usize;
        let статус = unsafe {
            fl_keys_fingerprint(
                blob.as_ptr(),
                blob.len(),
                буфер.as_mut_ptr(),
                &mut длина as *mut usize,
            )
        };
        assert_eq!(статус, FL_OK);

        let полученный = std::str::from_utf8(&буфер[..длина]).expect("отпечаток в UTF-8");
        assert_eq!(
            полученный,
            crate::crypto::fingerprint_from_signing(&keys.signing_public())
        );
        assert_eq!(полученный.len(), 19, "получилось «{полученный}»");
    }
}
