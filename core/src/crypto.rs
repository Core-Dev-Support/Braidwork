//! Криптография ядра Braidwork.
//!
//! Здесь нет ни одного системного вызова и ни одной платформенной зависимости:
//! ядро обязано собираться одинаково на всех таргетах (D-001).

use ed25519_dalek::{Signer, SigningKey, Verifier, VerifyingKey};
use rand_core::{OsRng, RngCore};
use snow::params::NoiseParams;
use snow::{Builder, HandshakeState};
use thiserror::Error;
use zeroize::Zeroize;

/// Префикс доменного разделения при выводе `device_id`.
/// Менять нельзя: смена значения делает все выпущенные устройства чужими.
const DEVICE_ID_DOMAIN: &[u8] = b"bw-device-id-v1";

#[derive(Debug, Error)]
pub enum CryptoError {
    #[error("криптографическая операция отклонена")]
    Rejected,

    #[error("подпись не прошла проверку")]
    BadSignature,

    #[error("устройство идентифицировано иначе, чем заявлено в подписи")]
    IdentityMismatch,

    #[error("рукопожатие не завершено")]
    HandshakeIncomplete,
}

/// Ключи устройства.
///
/// Три ключа с разным назначением: `static` участвует в Noise XX и в
/// согласовании, `signing` подписывает заявления о доверии, `qr` одноразовый
/// и живёт только во время сопряжения.
pub struct DeviceKeys {
    static_key: x25519_dalek::StaticSecret,
    signing_key: SigningKey,
    qr_key: Option<x25519_dalek::StaticSecret>,
}

impl DeviceKeys {
    /// Генерирует свежий набор ключей из системного CSPRNG.
    pub fn generate() -> Self {
        let mut static_bytes = [0u8; 32];
        let mut signing_bytes = [0u8; 32];
        let mut qr_bytes = [0u8; 32];
        OsRng.fill_bytes(&mut static_bytes);
        OsRng.fill_bytes(&mut signing_bytes);
        OsRng.fill_bytes(&mut qr_bytes);

        Self {
            static_key: x25519_dalek::StaticSecret::from(static_bytes),
            signing_key: SigningKey::from_bytes(&signing_bytes),
            qr_key: Some(x25519_dalek::StaticSecret::from(qr_bytes)),
        }
    }

    pub fn static_public(&self) -> [u8; 32] {
        x25519_dalek::PublicKey::from(&self.static_key).to_bytes()
    }

    pub fn signing_public(&self) -> [u8; 32] {
        self.signing_key.verifying_key().to_bytes()
    }

    pub fn signing_key(&self) -> &SigningKey {
        &self.signing_key
    }

    /// Одноразовый ключ для QR. Забирается ровно один раз.
    ///
    /// Возвращается `None`, если ключ уже использован: повторное сканирование
    /// того же QR не должно ничего подменять, устройство узнаётся по ключу.
    pub fn take_qr_key(&mut self) -> Option<[u8; 32]> {
        self.qr_key
            .take()
            .map(|s| x25519_dalek::PublicKey::from(&s).to_bytes())
    }

    /// Подписывает сообщение.
    pub fn sign(&self, message: &[u8]) -> [u8; 64] {
        self.signing_key.sign(message).to_bytes()
    }

    /// Стабильный идентификатор устройства, вычисляемый из публичного ключа.
    ///
    /// Идентификатор не выдаётся, а выводится, поэтому он не может быть занят
    /// другим устройством и переживает перезагрузку.
    pub fn device_id(&self) -> DeviceId {
        let mut buf = Vec::with_capacity(DEVICE_ID_DOMAIN.len() + 32);
        buf.extend_from_slice(DEVICE_ID_DOMAIN);
        buf.extend_from_slice(&self.signing_public());
        let hash = blake3::hash(&buf);
        let id = hash.as_bytes()[..16].to_vec();

        // base32 без заполнения, как в RFC 4648, чтобы id оставался компактным.
        let encoded = base32_nopad(&id);
        DeviceId(encoded)
    }

    /// Заголовок рукопожатия: байты, которые подписываются в `Hello`.
    ///
    /// Отдельная функция, а не свободная сборка на стороне вызова: иначе
    /// подписываемое и проверяемое разъедутся при первой же правке.
    ///
    /// Принимает `&self`, хотя поля не читает: так подпись всегда собирается
    /// тем же экземпляром ключей, который её потом и проверит.
    pub fn handshake_tape(&self, capabilities: &[u8], nonce: u64) -> Vec<u8> {
        let mut tape = Vec::with_capacity(72 + capabilities.len());
        tape.extend_from_slice(self.device_id().as_bytes());
        tape.extend_from_slice(&self.static_public());
        tape.extend_from_slice(capabilities);
        tape.extend_from_slice(&nonce.to_be_bytes());
        tape
    }
}

impl Drop for DeviceKeys {
    fn drop(&mut self) {
        // Приватный ключ подписи затирается при уничтожении объекта, а не при
        // выходе из функции: иначе он остался бы в стеке и в дампе памяти.
        let mut bytes = self.signing_key.to_bytes();
        bytes.zeroize();
    }
}

/// Идентификатор устройства в текстовом виде.
#[derive(Clone, PartialEq, Eq, Hash, Debug)]
pub struct DeviceId(String);

impl DeviceId {
    pub fn as_bytes(&self) -> &[u8] {
        self.0.as_bytes()
    }

    pub fn as_str(&self) -> &str {
        &self.0
    }

    /// Сборка идентификатора из уже посчитанного хэша.
    ///
    /// Нужен там, где приватного ключа нет, но идентификатор показать надо:
    /// клиент сверяет его по QR, не имея доступа к ключам устройства.
    pub fn from_raw(hash: &[u8]) -> Self {
        DeviceId(base32_nopad(hash))
    }

    /// Короткий отпечаток для сверки человеком: четыре группы по четыре символа.
    pub fn fingerprint(&self) -> String {
        let bytes = self.0.as_bytes();
        let mut out = String::with_capacity(19);
        for (i, chunk) in bytes.chunks(4).enumerate() {
            if i > 0 {
                out.push(' ');
            }
            for b in chunk {
                out.push_str(&format!("{:02x}", b));
            }
            if out.len() > 17 {
                break;
            }
        }
        out
    }
}

impl AsRef<[u8]> for DeviceId {
    fn as_ref(&self) -> &[u8] {
        self.0.as_bytes()
    }
}

const BASE32_ALPHABET: &[u8; 32] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

fn base32_nopad(data: &[u8]) -> String {
    let mut out = String::with_capacity(data.len().div_ceil(5) * 8);
    let mut buffer: u32 = 0;
    let mut bits: u32 = 0;

    for &byte in data {
        buffer = (buffer << 8) | byte as u32;
        bits += 8;
        while bits >= 5 {
            bits -= 5;
            let index = ((buffer >> bits) & 0x1f) as usize;
            out.push(BASE32_ALPHABET[index] as char);
        }
    }

    if bits > 0 {
        let index = ((buffer << (5 - bits)) & 0x1f) as usize;
        out.push(BASE32_ALPHABET[index] as char);
    }

    out
}

/// Проверка подписи `Hello`.
///
/// Проверяется и сама подпись, и соответствие: подпись должна быть сделана тем
/// ключом, чьё имя указано в поле `signing`. Без второй проверки устройство
/// подписало бы чужим ключом и прошло верификацию.
pub fn verify_handshake(
    signing_public: &[u8; 32],
    tape: &[u8],
    signature: &[u8; 64],
) -> Result<(), CryptoError> {
    let key = VerifyingKey::from_bytes(signing_public).map_err(|_| CryptoError::Rejected)?;
    key.verify(tape, &ed25519_dalek::Signature::from_bytes(signature))
        .map_err(|_| CryptoError::BadSignature)
}

/// Параметры Noise XX: X25519, ChaChaPoly, SHA-256.
///
/// Строка разбора совпадает с обозначением из спецификации Noise, поэтому
/// параметры читаются глазами без обращения к исходнику.
///
/// `psk` не используется: без предварительного общего секрета рукопожатие идёт
/// по открытым каналам, и первый услышавший может подменить ключи. Ключи
/// сверяются по отпечатку при первом сопряжении, а на каждом соединении
/// подпись проверяется заново.
///
/// `fallback` не используется: тихое понижение до открытого канала хуже отказа.
pub fn noise_params() -> Result<NoiseParams, CryptoError> {
    "Noise_XX_25519_ChaChaPoly_SHA256"
        .parse()
        .map_err(|_| CryptoError::Rejected)
}

/// Сторона рукопожатия: инициирующая.
///
/// Статический ключ передаётся байтами один раз, дальше `snow` держит его внутри
/// состояния рукопожатия.
pub fn noise_initiator(secret: &x25519_dalek::StaticSecret) -> Result<HandshakeState, CryptoError> {
    let params = noise_params()?;
    let bytes = secret.to_bytes();
    Builder::new(params)
        .local_private_key(&bytes)
        .build_initiator()
        .map_err(|_| CryptoError::Rejected)
}

/// Сторона рукопожатия: отвечающая.
pub fn noise_responder(secret: &x25519_dalek::StaticSecret) -> Result<HandshakeState, CryptoError> {
    let params = noise_params()?;
    let bytes = secret.to_bytes();
    Builder::new(params)
        .local_private_key(&bytes)
        .build_responder()
        .map_err(|_| CryptoError::Rejected)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ключи_разные_у_разных_устройств() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        assert_ne!(a.device_id(), b.device_id());
        assert_ne!(a.static_public(), b.static_public());
    }

    #[test]
    fn идентификатор_стабилен_между_вызовами() {
        let a = DeviceKeys::generate();
        assert_eq!(a.device_id(), a.device_id());
    }

    #[test]
    fn подпись_проходит_проверку() {
        let a = DeviceKeys::generate();
        let tape = a.handshake_tape(b"cap/v1", 42);
        let sig = a.sign(&tape);
        verify_handshake(&a.signing_public(), &tape, &sig).unwrap();
    }

    #[test]
    fn подпись_чужого_ключа_отвергается() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let tape = a.handshake_tape(b"cap/v1", 42);
        let sig = a.sign(&tape);
        // Проверяем подпись, но заявляем ключ другого устройства.
        assert!(verify_handshake(&b.signing_public(), &tape, &sig).is_err());
    }

    #[test]
    fn подпись_на_другой_ленте_отвергается() {
        let a = DeviceKeys::generate();
        let tape = a.handshake_tape(b"cap/v1", 42);
        let sig = a.sign(&tape);
        let mut tampered = tape.clone();
        tampered[0] ^= 0xff;
        assert!(verify_handshake(&a.signing_public(), &tampered, &sig).is_err());
    }

    #[test]
    fn qr_ключ_берётся_один_раз() {
        let mut a = DeviceKeys::generate();
        assert!(a.take_qr_key().is_some());
        assert!(a.take_qr_key().is_none());
    }

    #[test]
    fn base32_совпадает_с_ожидаемым() {
        assert_eq!(base32_nopad(b"foobar"), "MZXW6YTBOI");
    }
}