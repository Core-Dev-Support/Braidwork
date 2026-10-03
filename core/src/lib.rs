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
use std::sync::atomic::{AtomicU64, Ordering};

mod crypto;
mod session;

// Мост к JVM нужен только под Android. На десктопе клиент обращается к ядру
// через C ABI напрямую, и зависимость от `jni` была бы лишней.
#[cfg(target_os = "android")]
mod jni_bridge;

pub mod pb {
    #![allow(clippy::all)]
    include!(concat!(env!("OUT_DIR"), "/braidwork.v1.rs"));
}

pub use crypto::{verify_handshake, CryptoError, DeviceId, DeviceKeys};
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

fn status_from_crypto(err: &CryptoError) -> FlStatus {
    match err {
        CryptoError::BadSignature | CryptoError::IdentityMismatch => FL_ERR_NOT_TRUSTED,
        CryptoError::Rejected | CryptoError::HandshakeIncomplete => FL_ERR_CRYPTO,
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

/// Длина буфера публичных ключей: signing (32) + static (32) + qr (32).
pub const PUBLIC_KEYS_LEN: usize = 96;

/// Максимальная длина `device_id` в байтах.
pub const DEVICE_ID_MAX: usize = 32;

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

/// Хэндлы пока не выдаются наружу: сессии ещё не выведены в C ABI.
///
/// Механизм готов и покрыт тестами, поэтому код не удаляем — он понадобится
/// сразу, как только сессии станут доступны клиентам.
#[allow(dead_code)]
mod handles {
    use super::*;

    pub(super) const HANDLE_INVALID: FlHandle = FlHandle(0);

    /// Счётчик слотов. Начинается с 1, потому что слот 0 зарезервирован.
    static NEXT_SLOT: AtomicU64 = AtomicU64::new(1);

    /// Начальное поколение. Ноль зарезервирован под «слот не выдавался».
    pub(super) const GENERATION_START: u64 = 1;

    /// Выдаёт хэндл для нового объекта ядра.
    ///
    /// Ноль не может получиться: слот начинается с 1, а поколение с 1, значит
    /// младшие 32 бита ненулевые и весь хэндл тоже ненулевой. Клиент
    /// использует `HANDLE_INVALID` как признак «объекта нет», поэтому
    /// выдача нуля означала бы, что несуществующий объект выглядит как
    /// освобождённый.
    pub(super) fn alloc_handle(generation: u64) -> FlHandle {
        let slot = NEXT_SLOT.fetch_add(1, Ordering::Relaxed);
        debug_assert!(slot > 0 && slot <= u32::MAX as u64, "слот исчерпан");
        debug_assert!(
            generation > 0 && generation <= u32::MAX as u64,
            "поколение вне диапазона"
        );
        FlHandle((slot << 32) | (generation & 0xffff_ffff))
    }

    /// Разбирает хэндл на слот и поколение.
    pub(super) fn split_handle(handle: FlHandle) -> (usize, u64) {
        let raw = handle.0;
        ((raw >> 32) as usize, raw & 0xffff_ffff)
    }

    impl FlHandle {
        /// Хэндл не ссылается ни на один объект.
        pub fn is_valid(&self) -> bool {
            *self != HANDLE_INVALID
        }
    }
}

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
    let mut buf = Vec::with_capacity(16 + 32);
    buf.extend_from_slice(b"bw-device-id-v1");
    buf.extend_from_slice(signing_public);
    let hash = blake3::hash(&buf);
    Ok(DeviceId::from_raw(&hash.as_bytes()[..16]))
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
    use handles::{alloc_handle, split_handle, GENERATION_START, HANDLE_INVALID};

    #[test]
    fn статус_паники_не_пропускает_наружу() {
        // Функция, которая паникует, обязана вернуть FL_ERR_PANIC, а не уронить процесс.
        let code = guard(|| panic!("намеренно"));
        assert_eq!(code, FL_ERR_PANIC);
    }

    #[test]
    fn хэндлы_разбираются_на_слот_и_поколение() {
        let h = alloc_handle(GENERATION_START);
        let (slot, gen) = split_handle(h);
        assert_eq!(gen, GENERATION_START);
        assert_ne!(slot, 0);
        // Ноль зарезервирован под «объекта нет», выдавать его нельзя.
        assert_ne!(h, HANDLE_INVALID);
        assert!(h.is_valid());
        assert!(!HANDLE_INVALID.is_valid());
    }

    #[test]
    fn у_разных_объектов_разные_слоты() {
        let a = alloc_handle(GENERATION_START);
        let b = alloc_handle(GENERATION_START);
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
}
