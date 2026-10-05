//! Сессия между двумя устройствами: рукопожатие и зашифрованный обмен.
//!
//! Рукопожатие описано как конечный автомат с одним шагом, а не как две
//! половины кода. Иначе «инициатор» и «ответчик» разъезжаются: дописывая одну
//! сторону, забывают про вторую, а Noise XX требует, чтобы обе прошли один путь.
//!
//! Транспорт при этом не знает, что именно он несёт: он зовёт `step` с
//! входящими байтами и отправляет то, что вернулось.

use prost::Message;

use crate::crypto::{
    capabilities_hash, noise_initiator, noise_responder, verify_handshake, CryptoError, DeviceKeys,
};
use crate::pb::{CapabilitySet, DeviceIdentity, Hello, SigningPublic, StaticPublic};

/// Максимальный размер одного зашифрованного сообщения.
///
/// Не техническое ограничение: кадр больше транспортного MTU рвётся на
/// фрагменты, и получатель получит обрыв вместо сообщения.
const MAX_MESSAGE: usize = 64 * 1024;

/// Запас под заголовок AEAD.
const MAX_FRAME: usize = MAX_MESSAGE + 16;

/// Сторона рукопожатия.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Role {
    Initiator,
    Responder,
}

/// Незавершённое рукопожатие.
///
/// Состояние лежит в `Option` не из осторожности, а по существу: внутри `snow`
/// то же самое состояние переиспользуется под транспортный ключ, поэтому
/// держать рукопожатие и сессию одновременно нельзя. `Option` позволяет
/// перейти на месте, не разбирая и не собирая объект заново, — именно это нужно
/// реестру хэндлов, где объект нельзя временно убрать из слота.
pub struct Handshake {
    state: Option<snow::HandshakeState>,
    role: Role,
}

/// Шаг рукопожатия: что делать дальше.
#[derive(Debug, PartialEq, Eq)]
pub enum HandshakeStep {
    /// Отправить эти байты напарнику.
    Send(Vec<u8>),
    /// Руки жат: пришёл хвост сообщения ответчика, отправлять нечего.
    ///
    /// Отдельный вариант нужен, потому что `None` не отличил бы «ждём ввода»
    /// от «отправлять нечего».
    WaitForInput,
    /// Рукопожатие завершено, сессию можно забрать.
    Complete,
}

impl Handshake {
    pub fn initiate(keys: &DeviceKeys) -> Result<Self, SessionError> {
        Ok(Self {
            state: Some(noise_initiator(keys.static_secret())?),
            role: Role::Initiator,
        })
    }

    pub fn accept(keys: &DeviceKeys) -> Result<Self, SessionError> {
        Ok(Self {
            state: Some(noise_responder(keys.static_secret())?),
            role: Role::Responder,
        })
    }

    /// Состояние `snow`, пока рукопожатие не перешло в транспортный режим.
    fn state(&self) -> Result<&snow::HandshakeState, SessionError> {
        self.state.as_ref().ok_or(SessionError::Transport)
    }

    fn state_mut(&mut self) -> Result<&mut snow::HandshakeState, SessionError> {
        self.state.as_mut().ok_or(SessionError::Transport)
    }

    /// Выполняет один шаг автомата.
    ///
    /// Пустой `input` означает «говори сам». Непустой — «продолжай после
    /// полученного».
    ///
    /// Чей сейчас ход, решает `snow`, а не флаг внутри нас: собственная копия
    /// этого правила рано или поздно разошлась бы с библиотекой, и рукопожатие
    /// зависало бы намертво.
    pub fn step(&mut self, input: &[u8]) -> Result<HandshakeStep, SessionError> {
        let my_turn = self.state()?.is_my_turn();

        if input.is_empty() {
            if !my_turn {
                // Обещали отправить, но ход не наш: это рассинхронизация, а
                // не «молча подождём».
                return Err(SessionError::UnexpectedMessage);
            }
            return Ok(HandshakeStep::Send(self.write(&[])?));
        }

        if my_turn {
            // Сообщение пришло, когда мы должны были говорить сами. Такого
            // в протоколе быть не может: это либо подмена в канале, либо
            // рассинхронизация.
            return Err(SessionError::UnexpectedMessage);
        }

        let mut plaintext = vec![0u8; MAX_FRAME];
        self.state_mut()?
            .read_message(input, &mut plaintext)
            .map_err(|_| SessionError::Transport)?;

        if self.state()?.is_handshake_finished() {
            return Ok(HandshakeStep::Complete);
        }

        // Ответчик обязан отвечать сразу после чтения, иначе инициатор
        // останется ждать вечно.
        if self.state()?.is_my_turn() {
            Ok(HandshakeStep::Send(self.write(&[])?))
        } else {
            Ok(HandshakeStep::WaitForInput)
        }
    }

    fn write(&mut self, payload: &[u8]) -> Result<Vec<u8>, SessionError> {
        let mut buffer = vec![0u8; MAX_FRAME];
        let len = self
            .state_mut()?
            .write_message(payload, &mut buffer)
            .map_err(|_| SessionError::Transport)?;
        buffer.truncate(len);
        Ok(buffer)
    }

    /// Переводит рукопожатие в сессию на месте.
    ///
    /// Повторный вызов возвращает `HandshakeIncomplete`: состояние ушло в
    /// транспортный режим, и второй раз брать его нечего. Клиент обязан
    /// переходить один раз — иначе он потерял бы сессию, не заметив.
    pub fn take_session(&mut self) -> Result<Session, SessionError> {
        let finished = self
            .state
            .as_ref()
            .map(|s| s.is_handshake_finished())
            .ok_or(SessionError::HandshakeIncomplete)?;

        if !finished {
            return Err(SessionError::HandshakeIncomplete);
        }

        let state = self.state.take().ok_or(SessionError::Transport)?;
        let transport = state
            .into_transport_mode()
            .map_err(|_| SessionError::Transport)?;
        Ok(Session {
            transport,
            role: self.role,
        })
    }

    /// Забирает транспортное состояние, если рукопожатие завершено.
    pub fn into_session(self) -> Result<Session, SessionError> {
        let mut this = self;
        this.take_session()
    }

    pub fn is_finished(&self) -> bool {
        self.state
            .as_ref()
            .map(|s| s.is_handshake_finished())
            .unwrap_or(false)
    }
}

/// Зашифрованный обмен после рукопожатия.
pub struct Session {
    transport: snow::TransportState,
    role: Role,
}

impl Session {
    /// Шифрует сообщение.
    pub fn seal(&mut self, payload: &[u8]) -> Result<Vec<u8>, SessionError> {
        if payload.len() > MAX_MESSAGE {
            return Err(SessionError::TooLarge);
        }
        let mut buffer = vec![0u8; MAX_FRAME];
        let len = self
            .transport
            .write_message(payload, &mut buffer)
            .map_err(|_| SessionError::Transport)?;
        buffer.truncate(len);
        Ok(buffer)
    }

    /// Расшифровывает сообщение.
    pub fn open(&mut self, frame: &[u8]) -> Result<Vec<u8>, SessionError> {
        if frame.len() > MAX_FRAME {
            return Err(SessionError::TooLarge);
        }
        let mut payload = vec![0u8; MAX_MESSAGE];
        let len = self
            .transport
            .read_message(frame, &mut payload)
            .map_err(|_| SessionError::Transport)?;
        payload.truncate(len);
        Ok(payload)
    }

    pub fn role(&self) -> Role {
        self.role
    }
}

/// Собирает `Hello` от устройства.
///
/// Подпись считается здесь же: собирать её на стороне вызова означало бы, что
/// подписываемое и проверяемое однажды разойдутся.
pub fn build_hello(
    keys: &DeviceKeys,
    display_name: &str,
    capabilities: &[String],
    nonce: u64,
    unix_time_ms: u64,
) -> Result<Vec<u8>, SessionError> {
    let caps_hash = capabilities_hash(capabilities);
    let tape = keys.handshake_tape(&caps_hash, nonce);
    let signature = keys.sign(&tape);

    let hello = Hello {
        identity: Some(DeviceIdentity {
            device_id: prost::bytes::Bytes::copy_from_slice(keys.device_id().as_bytes()),
            signing: Some(SigningPublic {
                key: prost::bytes::Bytes::copy_from_slice(&keys.signing_public()),
            }),
            static_key: Some(StaticPublic {
                key: prost::bytes::Bytes::copy_from_slice(&keys.static_public()),
            }),
            display_name: display_name.to_owned(),
            protocol_version: crate::PROTOCOL_VERSION,
        }),
        capabilities: Some(CapabilitySet {
            offered: capabilities.to_vec(),
        }),
        signature: prost::bytes::Bytes::copy_from_slice(&signature),
        nonce,
        unix_time_ms,
    };

    Ok(hello.encode_to_vec())
}

/// Разбирает `Hello` и проверяет подпись против ожидаемого ключа.
///
/// Проверяется и соответствие, и подпись: ключ в сообщении обязан совпасть с
/// тем, что устройство объявило при обнаружении. Иначе устройство подписало бы
/// чужим ключом и прошло верификацию.
pub fn parse_and_verify_hello(body: &[u8], expected: &DeviceKeys) -> Result<Hello, SessionError> {
    let hello = Hello::decode(body).map_err(|_| SessionError::Malformed)?;

    let identity = hello.identity.as_ref().ok_or(SessionError::Malformed)?;

    let claimed: [u8; 32] = identity
        .signing
        .as_ref()
        .ok_or(SessionError::Malformed)?
        .key
        .as_ref()
        .try_into()
        .map_err(|_| SessionError::Malformed)?;

    if claimed != expected.signing_public() {
        return Err(SessionError::Crypto(CryptoError::IdentityMismatch));
    }

    let offered = hello
        .capabilities
        .as_ref()
        .map(|c| c.offered.clone())
        .unwrap_or_default();

    let caps_hash = capabilities_hash(&offered);
    let tape = expected.handshake_tape(&caps_hash, hello.nonce);
    let signature: [u8; 64] = hello
        .signature
        .as_ref()
        .try_into()
        .map_err(|_| SessionError::Malformed)?;

    verify_handshake(&claimed, &tape, &signature)?;
    Ok(hello)
}

/// Набор возможностей, которое объявляет ядро.
///
/// Список расширяемый, поэтому клиент сверяет пересечение, а не сравнивает
/// целиком: устройство с более старым ядром не должно отвергаться из-за
/// отсутствия фичи, которой ещё нет ни у кого.
pub fn default_capabilities() -> Vec<String> {
    vec![
        "feature/clipboard/v1".to_owned(),
        "feature/file/v1".to_owned(),
        "feature/gallery/v1".to_owned(),
        "feature/notification/v1".to_owned(),
        "feature/media-state/v1".to_owned(),
    ]
}

/// Ошибки сессии.
#[derive(Debug, PartialEq, Eq)]
pub enum SessionError {
    Crypto(CryptoError),
    Transport,
    Malformed,
    TooLarge,
    HandshakeIncomplete,
    UnexpectedMessage,
}

impl std::fmt::Display for SessionError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            SessionError::Crypto(e) => write!(f, "криптография: {e}"),
            SessionError::Transport => write!(f, "транспорт отклонил сообщение"),
            SessionError::Malformed => write!(f, "сообщение повреждено или неполно"),
            SessionError::TooLarge => write!(f, "сообщение больше допустимого размера"),
            SessionError::HandshakeIncomplete => write!(f, "рукопожатие ещё не завершено"),
            SessionError::UnexpectedMessage => write!(f, "сообщение пришло не в свою очередь"),
        }
    }
}

impl std::error::Error for SessionError {}

impl From<CryptoError> for SessionError {
    fn from(e: CryptoError) -> Self {
        SessionError::Crypto(e)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::DeviceId;

    fn caps(list: &[&str]) -> Vec<String> {
        list.iter().map(|s| s.to_string()).collect()
    }

    /// Прогоняет рукопожатие целиком в памяти.
    ///
    /// Noise XX — это **три** сообщения, а не два:
    /// `-> e, s`, `<- e, ee`, `-> s, se`. Третье сообщение инициатора несёт его
    /// статический ключ, поэтому без него рукопожатие не закрывается.
    ///
    /// Ответчик после чтения сразу пишет своё: отдельного шага «прочитал» нет.
    fn handshake_pair(a: &DeviceKeys, b: &DeviceKeys) -> (Session, Session) {
        let mut init = Handshake::initiate(a).expect("инициатор");
        let mut resp = Handshake::accept(b).expect("ответчик");

        // 1. Инициатор: своё эфемерное и статическое ключи.
        let msg1 = expect_send(init.step(&[]).expect("шаг 1"));

        // 2. Ответчик: эфемерное и общий секрет.
        let msg2 = expect_send(resp.step(&msg1).expect("шаг 2"));

        // 3. Инициатор: свой статический ключ под общим секретом.
        let msg3 = expect_send(init.step(&msg2).expect("шаг 3"));

        // 4. Ответчик читает последнее сообщение и закрывает рукопожатие.
        assert_eq!(
            resp.step(&msg3).expect("шаг 4"),
            HandshakeStep::Complete,
            "ответчик закрывает рукопожатие"
        );

        assert!(
            init.is_finished(),
            "инициатор должен считать рукопожатие завершённым"
        );

        (
            init.into_session().expect("сессия инициатора"),
            resp.into_session().expect("сессия ответчика"),
        )
    }

    fn expect_send(step: HandshakeStep) -> Vec<u8> {
        match step {
            HandshakeStep::Send(bytes) => bytes,
            other => panic!("ожидалось Send, получено {other:?}"),
        }
    }

    #[test]
    fn рукопожатие_завершается_и_сессии_готовы() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let (s1, s2) = handshake_pair(&a, &b);
        assert_eq!(s1.role(), Role::Initiator);
        assert_eq!(s2.role(), Role::Responder);
    }

    #[test]
    fn сообщения_идут_в_обе_стороны() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let (mut init, mut resp) = handshake_pair(&a, &b);

        // Кириллица в полезной нагрузке: буфер обмена с русским текстом —
        // самый частый случай, и он обязан шифроваться как есть.
        let привет = "привет".as_bytes();
        let frame = init.seal(привет).expect("шифрование");
        assert_eq!(resp.open(&frame).expect("расшифрование"), привет);

        let ответ = "и тебе".as_bytes();
        let reply = resp.seal(ответ).expect("шифрование ответа");
        assert_eq!(init.open(&reply).expect("расшифрование ответа"), ответ);
    }

    #[test]
    fn ключ_подменённой_сессии_не_расшифровывает() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let c = DeviceKeys::generate();
        let (mut init, _) = handshake_pair(&a, &b);
        let (_, mut чужой) = handshake_pair(&a, &c);

        let frame = init.seal(b"secret").expect("шифрование");
        assert!(
            чужой.open(&frame).is_err(),
            "сессия с чужим ключом не должна читать"
        );
    }

    #[test]
    fn испорченный_кадр_отвергается() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let (mut init, mut resp) = handshake_pair(&a, &b);

        let mut frame = init.seal(b"hello").expect("шифрование");
        frame[0] ^= 0xff;
        assert!(resp.open(&frame).is_err());
    }

    #[test]
    fn перевёрнутый_кадр_отвергается() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let (mut init, mut resp) = handshake_pair(&a, &b);

        let mut frame = init.seal(b"hello").expect("шифрование");
        frame.reverse();
        assert!(resp.open(&frame).is_err());
    }

    #[test]
    fn незавершённое_рукопожатие_не_даёт_сессию() {
        let a = DeviceKeys::generate();
        let init = Handshake::initiate(&a).expect("инициатор");
        assert!(!init.is_finished());
        assert_eq!(
            init.into_session().err(),
            Some(SessionError::HandshakeIncomplete)
        );
    }

    #[test]
    fn сообщение_не_в_свою_очередь_отвергается() {
        let a = DeviceKeys::generate();
        let mut init = Handshake::initiate(&a).expect("инициатор");
        assert_eq!(
            init.step(b"out-of-turn").err(),
            Some(SessionError::UnexpectedMessage)
        );
    }

    #[test]
    fn слишком_большое_сообщение_не_принимается() {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        let (mut init, _) = handshake_pair(&a, &b);
        let huge = vec![0u8; MAX_MESSAGE + 1];
        assert_eq!(init.seal(&huge).err(), Some(SessionError::TooLarge));
    }

    #[test]
    fn настоящий_hello_проходит_проверку() {
        let a = DeviceKeys::generate();
        let body = build_hello(&a, "телефон", &default_capabilities(), 1, 0).expect("сборка");
        let hello = parse_and_verify_hello(&body, &a).expect("проверка");

        assert_eq!(hello.nonce, 1);
        assert_eq!(
            hello.capabilities.as_ref().map(|c| c.offered.clone()),
            Some(default_capabilities())
        );

        let identity = hello.identity.expect("идентичность");
        assert_eq!(identity.device_id.as_ref(), a.device_id().as_bytes());
        assert_eq!(
            identity.signing.as_ref().map(|s| s.key.as_ref()),
            Some(a.signing_public().as_slice())
        );
    }

    #[test]
    fn hello_подписанное_чужим_ключом_отвергается() {
        let a = DeviceKeys::generate();
        let подделка = DeviceKeys::generate();

        // Заявляем в сообщении устройство `a`, но подписываем ключом `подделка`.
        let hello = Hello {
            identity: Some(DeviceIdentity {
                device_id: prost::bytes::Bytes::copy_from_slice(a.device_id().as_bytes()),
                signing: Some(SigningPublic {
                    key: prost::bytes::Bytes::copy_from_slice(&a.signing_public()),
                }),
                static_key: Some(StaticPublic {
                    key: prost::bytes::Bytes::copy_from_slice(&a.static_public()),
                }),
                display_name: "подделка".into(),
                protocol_version: crate::PROTOCOL_VERSION,
            }),
            capabilities: Some(CapabilitySet {
                offered: default_capabilities(),
            }),
            signature: prost::bytes::Bytes::copy_from_slice(&подделка.sign(b"whatever")),
            nonce: 1,
            unix_time_ms: 0,
        };

        assert_eq!(
            parse_and_verify_hello(hello.encode_to_vec().as_slice(), &a).err(),
            Some(SessionError::Crypto(CryptoError::BadSignature))
        );
    }

    #[test]
    fn hello_от_другого_устройства_отвергается() {
        let ожидаемый = DeviceKeys::generate();
        let другой = DeviceKeys::generate();

        // Ключ в сообщении не совпадает с объявленным при обнаружении.
        let body = build_hello(&другой, "чужой", &default_capabilities(), 1, 0).expect("сборка");
        assert_eq!(
            parse_and_verify_hello(&body, &ожидаемый).err(),
            Some(SessionError::Crypto(CryptoError::IdentityMismatch))
        );
    }

    #[test]
    fn hello_с_подменёнными_возможностями_отвергается() {
        let a = DeviceKeys::generate();
        let original = build_hello(&a, "телефон", &caps(&["feature/v1"]), 7, 0).expect("сборка");

        // Меняем набор возможностей в теле: подпись собрана по старому хэшу.
        let mut hello = Hello::decode(original.as_slice()).expect("разбор");
        hello.capabilities = Some(CapabilitySet {
            offered: caps(&["feature/v9"]),
        });

        assert_eq!(
            parse_and_verify_hello(hello.encode_to_vec().as_slice(), &a).err(),
            Some(SessionError::Crypto(CryptoError::BadSignature))
        );
    }

    #[test]
    fn hello_с_подменённым_nonce_отвергается() {
        let a = DeviceKeys::generate();
        let original = build_hello(&a, "телефон", &default_capabilities(), 7, 0).expect("сборка");

        let mut hello = Hello::decode(original.as_slice()).expect("разбор");
        hello.nonce = 8;

        assert_eq!(
            parse_and_verify_hello(hello.encode_to_vec().as_slice(), &a).err(),
            Some(SessionError::Crypto(CryptoError::BadSignature))
        );
    }

    #[test]
    fn hello_с_подменённым_именем_проходит_подпись_но_меняет_имя() {
        let a = DeviceKeys::generate();
        let original = build_hello(&a, "телефон", &default_capabilities(), 7, 0).expect("сборка");

        // Имя не входит в подпись: оно показывается пользователю и может быть
        // переименовано. Фиксируем поведение явно, чтобы изменение состава
        // подписываемого поля не оказалось сюрпризом.
        let mut hello = Hello::decode(original.as_slice()).expect("разбор");
        if let Some(identity) = hello.identity.as_mut() {
            identity.display_name = "чужое имя".into();
        }
        let checked =
            parse_and_verify_hello(hello.encode_to_vec().as_slice(), &a).expect("проверка");
        assert_eq!(
            checked.identity.expect("идентичность").display_name,
            "чужое имя"
        );
    }

    #[test]
    fn пустое_тело_отвергается() {
        let a = DeviceKeys::generate();
        assert_eq!(
            parse_and_verify_hello(&[], &a).err(),
            Some(SessionError::Malformed)
        );
        assert_eq!(
            parse_and_verify_hello(b"\xff\xff\xff\xff", &a).err(),
            Some(SessionError::Malformed)
        );
    }

    #[test]
    fn hello_без_идентичности_отвергается() {
        let a = DeviceKeys::generate();
        let hello = Hello {
            identity: None,
            capabilities: None,
            signature: prost::bytes::Bytes::new(),
            nonce: 1,
            unix_time_ms: 0,
        };
        assert_eq!(
            parse_and_verify_hello(hello.encode_to_vec().as_slice(), &a).err(),
            Some(SessionError::Malformed)
        );
    }

    #[test]
    fn идентификатор_в_hello_совпадает_с_вычисленным() {
        let a = DeviceKeys::generate();
        let body = build_hello(&a, "планшет", &default_capabilities(), 3, 0).expect("сборка");
        let hello = parse_and_verify_hello(&body, &a).expect("проверка");

        let из_сообщения = hello.identity.expect("идентичность").device_id;
        let вычисленный = DeviceId::from_raw(&{
            let mut buf = Vec::new();
            buf.extend_from_slice(b"bw-device-id-v1");
            buf.extend_from_slice(&a.signing_public());
            let hash = blake3::hash(&buf);
            hash.as_bytes()[..16].to_vec()
        });

        assert_eq!(из_сообщения.as_ref(), вычисленный.as_bytes());
    }
}
