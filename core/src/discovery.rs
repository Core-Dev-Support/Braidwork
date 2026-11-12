//! Обнаружение устройств в локальной сети по UDP.
//!
//! Модуль отвечает за:
//! * сборку криптографически подписанных объявлений `Hello`;
//! * широковещательную отправку UDP-пакетов через заданные интервалы;
//! * прослушивание UDP-сокета, фильтрацию чужих пакетов, проверку Ed25519-подписи
//!   и соответствия отпечатка/ключа устройства;
//! * защиту от атак с подделкой имени, идентификатора и перехватом трафика.

use std::io::ErrorKind;
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::time::{Duration, Instant};

use crate::crypto::{self, DeviceKeys};
use crate::frame::{self};
use crate::pb::{Hello, MessageKind};
use crate::session::{build_hello, default_capabilities, verify_announcement, Профиль};
use crate::transport::TransportError;
use prost::Message;

/// Размер буфера для чтения входящего UDP-объявления.
pub const DISCOVERY_BUF_SIZE: usize = 2048;

/// Максимальная длина отображаемого имени устройства.
pub const MAX_NAME_LEN: usize = 64;

/// Устройство, объявленное в локальной сети по UDP.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    pub device_id: Vec<u8>,
    pub addr: SocketAddr,
    pub name: String,
    pub fingerprint: String,
    pub platform: String,
    pub core_version: String,
    pub device_kind: String,
}

/// Очищает имя от управляющих символов и обрезает по допустимой длине.
pub fn sanitize_name(raw: &str) -> String {
    let clean: String = raw
        .chars()
        .filter(|c| !c.is_control())
        .collect::<String>()
        .trim()
        .to_owned();

    if clean.is_empty() {
        return "Устройство".to_owned();
    }

    let trimmed: String = clean.chars().take(MAX_NAME_LEN).collect();
    if trimmed.is_empty() {
        "Устройство".to_owned()
    } else {
        trimmed
    }
}

/// Собирает подписанное UDP-объявление `Hello` в формате сетевого кадра.
pub fn build_announcement(
    keys: &DeviceKeys,
    display_name: &str,
    platform: &str,
    device_kind: &str,
    port: u16,
    nonce: u64,
    unix_time_ms: u64,
) -> Result<Vec<u8>, TransportError> {
    let profile = Профиль::new(display_name, platform, device_kind);
    let body = build_hello(
        keys,
        &profile,
        &default_capabilities(),
        nonce,
        unix_time_ms,
        port,
    )
    .map_err(|e| TransportError::Other(e.to_string()))?;

    frame::encode_raw(MessageKind::KindHello as i32, 0, &body).map_err(TransportError::Frame)
}

/// Разбирает и криптографически проверяет входящий пакет объявления.
///
/// Возвращает `Some(DiscoveredDevice)` только если:
/// 1. Кадр является сообщением `Hello`.
/// 2. Идентификатор устройства математически совпадает с открытым ключом подписи.
/// 3. Ed25519-подпись пакета верна.
/// 4. Порт данных не равен 0.
/// 5. Пакет отправлен другим устройством (не своим собственным).
/// 6. Адрес источника — валидный IPv4.
pub fn parse_announcement(
    packet: &[u8],
    source_addr: SocketAddr,
    own_device_id: &[u8],
) -> Option<DiscoveredDevice> {
    let (header, body, _) = frame::split(packet).ok()?;
    if header.kind != MessageKind::KindHello as i32 {
        return None;
    }

    let hello = Hello::decode(body).ok()?;
    let identity = hello.identity.as_ref()?;
    if identity.device_id.is_empty() || identity.signing.is_none() {
        return None;
    }

    let key_bytes: [u8; 32] = identity
        .signing
        .as_ref()
        .and_then(|s| <[u8; 32]>::try_from(&s.key[..]).ok())?;

    let fingerprint = crypto::fingerprint_from_signing(&key_bytes);

    if !crypto::device_id_matches_signing(&identity.device_id, &key_bytes) {
        crate::android_log::предостережение(&format!(
            "объявление с {}: идентификатор не соответствует ключу {fingerprint}, отброшено",
            source_addr
        ));
        return None;
    }

    if let Err(e) = verify_announcement(&hello) {
        crate::android_log::предостережение(&format!(
            "объявление с {} не подписано ключом {fingerprint}: {e}, отброшено",
            source_addr
        ));
        return None;
    }

    let port = u16::try_from(hello.lan_port).unwrap_or(0);
    if port == 0 {
        return None;
    }

    let id = identity.device_id.to_vec();
    if id.as_slice() == own_device_id {
        return None;
    }

    let IpAddr::V4(ip) = source_addr.ip() else {
        return None;
    };

    Some(DiscoveredDevice {
        device_id: id,
        addr: SocketAddr::from((ip, port)),
        name: sanitize_name(&identity.display_name),
        fingerprint,
        platform: sanitize_name(&hello.platform),
        core_version: sanitize_name(&hello.core_version),
        device_kind: sanitize_name(&hello.device_kind),
    })
}

/// Модуль управления UDP-обнаружением (рассылка и приём пакетов).
pub struct UdpDiscovery {
    socket: Option<UdpSocket>,
    last_announce: Option<Instant>,
}

impl UdpDiscovery {
    /// Открывает UDP сокет на порту `port` с флагом широковещания и неблокирующим режимом.
    pub fn bind(port: u16) -> Self {
        let socket = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, port))
            .and_then(|sock| {
                sock.set_broadcast(true)?;
                sock.set_nonblocking(true)?;
                Ok(sock)
            })
            .ok();

        Self {
            socket,
            last_announce: None,
        }
    }

    /// Проверяет, активен ли UDP сокет.
    pub fn is_bound(&self) -> bool {
        self.socket.is_some()
    }

    /// Закрывает сокет и сбрасывает состояние обнаружения.
    pub fn close(&mut self) {
        self.socket = None;
        self.last_announce = None;
    }

    /// Сбрасывает таймер отправки объявления, чтобы следующее ушло немедленно.
    pub fn reset_announce_timer(&mut self) {
        self.last_announce = None;
    }

    /// Отправляет широковещательное объявление, если истёк интервал `interval`.
    pub fn announce_if_due(
        &mut self,
        announcement_bytes: &[u8],
        broadcast_addr: [u8; 4],
        discovery_port: u16,
        interval: Duration,
    ) -> Result<(), TransportError> {
        let Some(socket) = &self.socket else {
            return Ok(());
        };

        let due = self
            .last_announce
            .map(|t| t.elapsed() >= interval)
            .unwrap_or(true);

        if !due {
            return Ok(());
        }

        self.last_announce = Some(Instant::now());

        let target = SocketAddrV4::new(
            Ipv4Addr::new(
                broadcast_addr[0],
                broadcast_addr[1],
                broadcast_addr[2],
                broadcast_addr[3],
            ),
            discovery_port,
        );

        if let Err(e) = socket.send_to(announcement_bytes, target) {
            if e.kind() != ErrorKind::WouldBlock {
                return Err(TransportError::Io(e));
            }
        }

        Ok(())
    }

    /// Опрашивает неблокирующий сокет на наличие входящих объявлений.
    pub fn poll_discovered(
        &mut self,
        own_device_id: &[u8],
    ) -> (Vec<DiscoveredDevice>, Option<TransportError>) {
        let mut devices = Vec::new();
        let mut error = None;

        let Some(socket) = &self.socket else {
            return (devices, error);
        };

        let mut buf = [0u8; DISCOVERY_BUF_SIZE];
        loop {
            let (n, from) = match socket.recv_from(&mut buf) {
                Ok(v) => v,
                Err(ref e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(ref e) if e.kind() == ErrorKind::Interrupted => continue,
                Err(e) => {
                    error = Some(TransportError::Io(e));
                    break;
                }
            };

            if let Some(dev) = parse_announcement(&buf[..n], from, own_device_id) {
                devices.push(dev);
            }
        }

        (devices, error)
    }
}
