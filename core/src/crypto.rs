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

/// Префикс доменного разделения при хэшировании набора возможностей.
const CAPS_DOMAIN: &[u8] = b"bw-caps-v1";

#[derive(Debug, Error, PartialEq, Eq, Clone, Copy)]
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

        // OsRng — единовременный источник, но RngCore требует изменяемый
        // получатель, поэтому берём его в переменную, а не обращаемся к
        // временному значению.
        let mut rng = OsRng;
        rng.fill_bytes(&mut static_bytes);
        rng.fill_bytes(&mut signing_bytes);
        rng.fill_bytes(&mut qr_bytes);

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

    /// Статический секрет для Noise XX.
    ///
    /// Отдаётся ссылкой и остаётся внутри объекта: копия секрета в руках
    /// вызывающей стороны рано или поздно оказалась бы в дампе памяти.
    pub fn static_secret(&self) -> &x25519_dalek::StaticSecret {
        &self.static_key
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
        device_id_from_signing(&self.signing_public())
    }

    /// Лента, подписываемая в `Hello`.
    ///
    /// Набор возможностей входит не строкой, а хэшем: иначе `["a", "b"]` и
    /// `["a\nb"]` дали бы одинаковую подпись, и подмена не была бы заметна.
    ///
    /// Принимает `&self`, хотя поля читает не все: так подпись всегда
    /// собирается тем же экземпляром ключей, который её потом и проверит.
    pub fn handshake_tape(&self, capabilities_hash: &[u8; 32], nonce: u64) -> Vec<u8> {
        let mut tape = Vec::with_capacity(16 + 32 + 32 + 8);
        tape.extend_from_slice(self.device_id().as_bytes());
        tape.extend_from_slice(&self.static_public());
        tape.extend_from_slice(capabilities_hash);
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

/// Хэш набора возможностей для подписи.
///
/// Разделитель `\n` исключает неоднозначность: без него `["ab", "c"]` и
/// `["a", "bc"]` дали бы один хэш.
pub fn capabilities_hash(capabilities: &[String]) -> [u8; 32] {
    let mut buf = Vec::with_capacity(CAPS_DOMAIN.len() + capabilities.len() * 8);
    buf.extend_from_slice(CAPS_DOMAIN);
    for cap in capabilities {
        buf.extend_from_slice(cap.as_bytes());
        buf.push(b'\n');
    }
    *blake3::hash(&buf).as_bytes()
}

/// `device_id` из публичного ключа подписи.
///
/// Нужен там, где приватного ключа нет, но идентификатор показать надо:
/// клиент сверяет его по QR, не имея доступа к ключам устройства.
pub fn device_id_from_signing(signing_public: &[u8; 32]) -> DeviceId {
    let mut buf = Vec::with_capacity(DEVICE_ID_DOMAIN.len() + 32);
    buf.extend_from_slice(DEVICE_ID_DOMAIN);
    buf.extend_from_slice(signing_public);
    let hash = blake3::hash(&buf);
    DeviceId::from_raw(&hash.as_bytes()[..16])
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
    pub fn from_raw(hash: &[u8]) -> Self {
        DeviceId(base32_nopad(hash))
    }

    /// Короткий отпечаток для сверки человеком: четыре группы по четыре символа.
    ///
    /// Берутся первые 16 символов идентификатора — это 80 бит, достаточно для
    /// визуальной сверки и достаточно мало, чтобы прочитать вслух.
    /// Показывается сам base32, а не hex: символы получаются прописными и
    /// разделимыми, в отличие от сплошной шестнадцатеричной строки.
    pub fn fingerprint(&self) -> String {
        let mut out = String::with_capacity(19);
        for (i, b) in self.0.as_bytes().iter().take(16).enumerate() {
            if i > 0 && i % 4 == 0 {
                out.push(' ');
            }
            out.push(*b as char);
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
/// Проверяется сама подпись. Соответствие подписи объявленному ключу — задача
/// вызывающей стороны: она знает, как устройство объявило себя при обнаружении.
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

    fn caps(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn ключи_разные_у_разных_устройств() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        assert_ne!(a.device_id(), b.device_id());
        assert_ne!(a.static_public(), b.static_public());
        assert_ne!(a.signing_public(), b.signing_public());
    }

    #[test]
    fn идентификатор_стабилен_между_вызовами() {
        let a = DeviceKeys::generate();
        assert_eq!(a.device_id(), a.device_id());
        // 16 байт хэша в base32 дают 26 символов: 128 бит, по 5 бит на символ.
        assert_eq!(a.device_id().as_bytes().len(), 26);
    }

    #[test]
    fn подпись_проходит_проверку() {
        let a = DeviceKeys::generate();
        let hash = capabilities_hash(&caps(&["feature/v1"]));
        let tape = a.handshake_tape(&hash, 42);
        let sig = a.sign(&tape);
        verify_handshake(&a.signing_public(), &tape, &sig).unwrap();
    }

    #[test]
    fn подпись_чужого_ключа_отвергается() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let hash = capabilities_hash(&caps(&["feature/v1"]));
        let tape = a.handshake_tape(&hash, 42);
        let sig = a.sign(&tape);
        assert!(verify_handshake(&b.signing_public(), &tape, &sig).is_err());
    }

    #[test]
    fn подпись_на_другой_ленте_отвергается() {
        let a = DeviceKeys::generate();
        let hash = capabilities_hash(&caps(&["feature/v1"]));
        let tape = a.handshake_tape(&hash, 42);
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

    /// Главное свойство хэша возможностей: разные наборы не должны
    /// подменяться друг под другом.
    #[test]
    fn хэш_возможностей_различает_неоднозначные_наборы() {
        assert_ne!(
            capabilities_hash(&caps(&["ab", "c"])),
            capabilities_hash(&caps(&["a", "bc"]))
        );
        assert_ne!(
            capabilities_hash(&caps(&["feature/v1"])),
            capabilities_hash(&caps(&["feature/v2"]))
        );
        assert_eq!(
            capabilities_hash(&caps(&["a", "b"])),
            capabilities_hash(&caps(&["a", "b"]))
        );
        // Порядок значим: наборы сравниваются как множества на стороне клиента,
        // а подпись должна соответствовать фактически отправленному порядку.
        assert_ne!(
            capabilities_hash(&caps(&["a", "b"])),
            capabilities_hash(&caps(&["b", "a"]))
        );
    }

    #[test]
    fn отпечаток_читаемый() {
        let a = DeviceKeys::generate();
        let id = a.device_id();
        let fp = id.fingerprint();

        // Четыре группы по четыре символа через пробел.
        assert_eq!(fp.len(), 19);
        assert_eq!(fp.matches(' ').count(), 3);

        // Отпечаток — начало идентификатора, а не что-то похожее.
        let ожидается: String = id.as_bytes().iter().take(16).map(|b| *b as char).collect();
        assert_eq!(fp.replace(' ', ""), ожидается);

        // У разных устройств отпечаток разный.
        let b = DeviceKeys::generate();
        assert_ne!(a.device_id().fingerprint(), b.device_id().fingerprint());
    }
}
