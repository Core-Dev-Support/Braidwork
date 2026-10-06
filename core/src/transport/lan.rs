//! Канал локальной сети.
//!
//! Два сокета, никаких зависимостей:
//!
//! * TCP на [crate::transport::LAN_PORT] — приём соединений и данные;
//! * UDP на [crate::transport::DISCOVERY_PORT] — кто где.
//!
//! ## Почему широковещание, а не mDNS
//!
//! mDNS (224.0.0.251) — это **многоадресная** передача, и Android фильтрует её
//! на уровне драйвера: без захваченного `WifiManager.MulticastLock` пакеты до
//! приложения не доходят вовсе. Клиент обязан где-то взять lock и не отдать его
//! вовремя — и всё, обнаружение молча умирает, без единой ошибки. Пользователь
//! видит пустой список устройств и не понимает почему.
//!
//! Широковещание такого свойства не имеет: `255.255.255.255` Android не
//! блокирует. Обратная сторона — объявление слышно всем в подсети, но тело
//! подписано, а лишний слушатель получит отказ на сверке.
//!
//! Минус честно: широковещание не выходит за пределы одной подсети. Между
//! подсетями работает ICE, и для него широкий адрес бесполезен.
//!
//! ## Ключи — не создаются здесь
//!
//! Транспорт получает ключи снаружи и **обязан** использовать именно их. Каждое
//! соединение, построенное на свежих ключах, выглядело бы устройством, которого
//! никто никогда не сопрягал: подпись сошлась бы сама с собой, а настоящий
//! напарник всё равно получил бы отказ.
//!
//! ## Поток
//!
//! `poll` не блокируется: сокеты в неблокирующем режиме, клиент зовёт `poll`
//! из своего цикла. Это оставляет ядро независимым от выбора потоков на
//! стороне клиента (см. `transport/mod.rs`).

use std::collections::HashMap;
use std::io::{ErrorKind, Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, SocketAddrV4, TcpListener, TcpStream, UdpSocket};
use std::time::{Duration, Instant};

use crate::crypto::DeviceKeys;
use crate::frame::{self, FrameReader};
use crate::pb::{Hello, MessageKind};
use crate::session::{build_hello, default_capabilities, Handshake, HandshakeStep, Role, Session};
use crate::transport::{
    Channel, CloseReason, Connection, Transport, TransportError, TransportEvent, TransportState,
    ANNOUNCE_INTERVAL_MS, BROADCAST_ADDR, DISCOVERY_PORT,
};
use prost::Message;

/// Сколько данных пробуем прочитать за один заход.
const READ_CHUNK: usize = 8 * 1024;

/// Размер буфера приёма объявлений.
const DISCOVERY_BUF: usize = 2048;

/// На какой стадии находится соединение.
///
/// Готовый канал упакован: `Session` заметно крупнее рукопожатия, и без
/// упаковки перечисление занимало бы размер большего варианта при каждом
/// соединении — а соединений может быть десяток с открытыми каналами.
enum Phase {
    /// Идёт обмен ключами Noise.
    Handshake(Box<Handshake>),
    /// Канал готов, идёт обмен данными.
    Data(Box<Session>),
}

/// Одно соединение внутри транспорта.
struct Peer {
    stream: TcpStream,
    /// Откуда соединение. Нужно для события `Connected`: без адреса клиент не
    /// показал бы, куда он подключился.
    remote: SocketAddr,
    /// Сторона рукопожатия. Тоже для события `Connected`: у инициатора и
    /// ответчика разный смысл, и путать их в интерфейсе нельзя.
    role: Role,
    /// Хэндл соединения. Совпадает с ключом в таблице, но нужен сам: событие
    /// собирается отсюда, а искать себя в таблице во время обхода означало бы
    /// лезть в `self` от ссылки на элемент той же таблицы.
    handle: u64,
    phase: Phase,
    /// Накопитель входящих кадров.
    reader: FrameReader,
    /// Байты, которые не удалось отправить целиком.
    ///
    /// Сокет неблокирующий, поэтому частичная отправка — обычное дело, а не
    /// ошибка. При отправке файла накопитель растёт быстрее, чем уходит сеть;
    /// без него транспорт молча терял бы куски передачи.
    outbox: Vec<u8>,
    /// На той стороне закрыли соединение или сокет сломался. Писать больше
    /// нельзя, накопленное ждёт отправки.
    ///
    /// Наравне с ним обязателен [`Peer::причина`]: мёртвое соединение без
    /// причины нечем объяснить клиенту, и без неё же нечем обосновать
    /// удаление из таблицы. Поэтому мёртвые соединения заводятся только через
    /// [`Peer::убить`], где причина и признак ставятся вместе.
    dead: bool,
    /// Почему соединение закрыто. `None` в точности тогда, когда соединение
    /// живо.
    ///
    /// Держится здесь, а не в отдельном списке обхода, потому что причины
    /// возникают в разных местах — чтение, запись, разбор кадра, шаг
    /// рукопожатия, — и список обязан был бы их все перечислять вручную.
    /// Список перечислял не все: из десяти мест, где соединение умирало,
    /// событие `Closed` отправляли три. Остальные семь оставляли сокет открытым
    /// и запись в таблице, а клиент — с устройством, на которое он так и не
    /// смог подключиться, и без единого события об этом.
    причина: Option<CloseReason>,
}

impl Peer {
    /// Помечает соединение закрытым и записывает, почему.
    ///
    /// **Первая** причина побеждает. Последующие — следствия первой: после
    /// отказа рукопожатия сокет закроется напарником, и сетевая ошибка на
    /// чтении скажет о другом, хотя настоящая причина была известна раньше.
    fn убить(&mut self, причина: CloseReason) {
        self.dead = true;
        self.причина.get_or_insert(причина);
    }

    /// Отправляет накопившуюся очередь в сокет.
    ///
    /// Цикл, а не одна запись: неблокирующий сокет принимает столько, сколько
    /// влезло, и оставляет остальное на следующий заход. Одна запись за проход
    /// откладывала бы отправку на целый такт опроса — 500 мс на каждый шаг
    /// рукопожатия, а их три.
    ///
    /// ## Про `Ok(0)`
    ///
    /// Ноль байт — это **не** «буфер полон». Полный буфер неблокирующего сокета
    /// возвращает `Err(WouldBlock)`, то есть `EAGAIN`, и это штатная пауза.
    /// `Ok(0)` при непустой очереди у `write(2)` на сокете не означает ничего
    /// хорошего: писать было что, значит поток разорван.
    ///
    /// Раньше здесь стоял `break` без обрыва, в расчёте на «переполнение». Из
    /// этого следовало худшее из возможного: очередь не уменьшается, соединение
    /// не помечается мёртвым, из таблицы не уходит и события `Closed` не
    /// порождает. Канал висел бы молча и навсегда — хуже прежнего
    /// `NetworkLost`, который хотя бы сообщал о себе и убирал соединение.
    fn сбросить_очередь(&mut self) {
        // `Interrupted` на неблокирующем сокете практически не бывает, но и
        // бесконечный цикл на нём недопустим: страховка от зависания опроса.
        let mut прерываний = 0;

        while !self.outbox.is_empty() && !self.dead {
            match self.stream.write(&self.outbox) {
                Ok(0) => {
                    crate::android_log::ошибка(&format!(
                        "запись в {} вернула 0 при непустой очереди ({} байт): поток разорван",
                        self.remote,
                        self.outbox.len()
                    ));
                    self.убить(CloseReason::NetworkLost);
                }
                Ok(n) => {
                    self.outbox.drain(..n);
                }
                Err(ref e) if e.kind() == ErrorKind::WouldBlock => break,
                Err(ref e) if e.kind() == ErrorKind::Interrupted => {
                    прерываний += 1;
                    if прерываний > ПРЕДЕЛ_ПРЕРЫВАНИЙ {
                        crate::android_log::ошибка(&format!(
                            "запись в {} прерывается без конца: обрываем",
                            self.remote
                        ));
                        self.убить(CloseReason::NetworkLost);
                        break;
                    }
                }
                Err(e) => {
                    crate::android_log::ошибка(&format!(
                        "запись в {} не удалась: {e}",
                        self.remote
                    ));
                    self.убить(CloseReason::NetworkLost);
                    break;
                }
            }
        }
    }
}

/// Сколько раз запись может прерваться до обрыва соединения.
///
/// Значение произвольное, но не бесконечное: опрос не должен вставать.
const ПРЕДЕЛ_ПРЕРЫВАНИЙ: u32 = 8;

/// Транспорт локальной сети.
pub struct LanTransport {
    state: TransportState,
    listener: Option<TcpListener>,
    discovery: Option<UdpSocket>,
    /// Ключи устройства. Приватные не покидают ядро.
    keys: DeviceKeys,
    peers: HashMap<u64, Peer>,
    events: Vec<TransportEvent>,
    /// Имя устройства для объявления.
    display_name: String,
    /// Порт, на котором реально слушает сокет.
    port: u16,
    last_announce: Option<Instant>,
    next_handle: u64,
}

impl LanTransport {
    /// Поднимает транспорт под ключи устройства.
    ///
    /// `port` = 0 означает «выбери любой свободный»: этим пользуются тесты, не
    /// занимая порт, за который борется первый попавшийся процесс.
    ///
    /// ## Почему порт постоянен
    ///
    /// Напарник узнаёт адрес из объявления, а оно живёт две секунды. При
    /// произвольном порте после перезапуска сервиса адрес меняется, и всё, что
    /// накоплено раньше, указывает в никуда: подключение падает с
    /// «недоступно», и разобрать это невозможно — в журнале нет ни слова о
    /// том, что порт у напарника просто другой.
    ///
    /// Поэтому сначала пробуется постоянный порт, и только если он занят —
    /// любой свободный. Занятость порта на телефоне, кроме двух наших
    /// приложений, маловероятна.
    pub fn bind(keys: DeviceKeys, display_name: String, port: u16) -> Result<Self, TransportError> {
        let listener = match TcpListener::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, port)) {
            Ok(l) => l,
            Err(e) if port != 0 => {
                // Постоянный порт занят: это не повод отказывать в канале.
                // Работаем на любом свободном и объявляем настоящий — напарник
                // подключится по объявлению, а не по предположению.
                crate::android_log::предостережение(&format!(
                    "порт {port} занят ({e}), берём свободный"
                ));
                TcpListener::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0))?
            }
            Err(e) => return Err(e.into()),
        };

        let port = listener.local_addr()?.port();
        listener.set_nonblocking(true).map_err(TransportError::Io)?;

        // Обнаружение может и не подняться: порт способен быть занятым
        // посторонней программой. Это не повод отказывать в канале — работать
        // можно и по адресу, введённому вручную. Объявление о неудаче уходит
        // клиенту событием, а не ошибкой возврата.
        let discovery = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, DISCOVERY_PORT))
            .and_then(|sock| {
                sock.set_nonblocking(true)?;
                sock.set_broadcast(true)?;
                Ok(sock)
            })
            .ok();

        Ok(LanTransport {
            state: if discovery.is_some() {
                TransportState::Announcing
            } else {
                TransportState::Ready
            },
            listener: Some(listener),
            discovery,
            keys,
            peers: HashMap::new(),
            events: Vec::new(),
            display_name,
            port,
            last_announce: None,
            next_handle: 1,
        })
    }

    /// Порт, на котором транспорт принимает соединения.
    pub fn port(&self) -> u16 {
        self.port
    }

    /// Идентификатор устройства.
    ///
    /// Наружу отдаётся только он: клиенту он нужен для показа и для сверки при
    /// сопряжении, приватных ключей он не касается.
    pub fn device_id(&self) -> Vec<u8> {
        self.keys.device_id().as_bytes().to_vec()
    }

    /// Публичный ключ подписи устройства.
    ///
    /// Ровно тот, что попадает в `Hello`, и потому годный для сверки объявления
    /// из объявления же.
    pub fn signing_public(&self) -> [u8; 32] {
        self.keys.signing_public()
    }

    /// Собирает объявление о себе.
    ///
    /// Подписано настоящими ключами устройства — иначе напарник, получивший
    /// такое по UDP, не смог бы сверить его с тем, что придёт по соединению.
    pub fn build_announcement(
        &self,
        nonce: u64,
        unix_time_ms: u64,
    ) -> Result<Vec<u8>, TransportError> {
        let body = build_hello(
            &self.keys,
            &self.display_name,
            &default_capabilities(),
            nonce,
            unix_time_ms,
            // Настоящий порт, а не константа: клиент подключается именно туда,
            // и угадывать было бы вслепую.
            self.port,
        )
        .map_err(|e| TransportError::Other(e.to_string()))?;

        frame::encode_raw(MessageKind::KindHello as i32, 0, &body).map_err(TransportError::Frame)
    }

    /// Подключается к устройству и начинает рукопожатие.
    ///
    /// Первое сообщение уходит сразу, а не при следующем `poll`: иначе клиент
    /// увидел бы событие `Connected` и через миллисекунду — первый байт в
    /// сокете напарника, без объяснения, почему пауза.
    pub fn connect(&mut self, addr: SocketAddr) -> Result<u64, TransportError> {
        if self.listener.is_none() {
            return Err(TransportError::NotRunning);
        }

        let stream = TcpStream::connect_timeout(&addr, Duration::from_secs(3)).map_err(|e| {
            // Причина пишется в журнал ядра, а не теряется: клиент получает
            // только общий код, и «недоступно» и «соединение сброшено» выглядели
            // бы одинаково, хотя чинятся по-разному.
            crate::android_log::предостережение(
                &format!("подключение к {addr} не удалось: {e}"),
            );
            TransportError::Unreachable(addr)
        })?;
        stream.set_nonblocking(true).map_err(TransportError::Io)?;
        stream.set_nodelay(true).map_err(TransportError::Io)?;

        crate::android_log::инфо(&format!("соединение с {addr} открыто, ждём рукопожатие"));

        let handshake =
            Handshake::initiate(&self.keys).map_err(|e| TransportError::Other(e.to_string()))?;
        let handle = self.take_handle();
        let mut peer = Peer {
            stream,
            remote: addr,
            role: Role::Initiator,
            handle,
            phase: Phase::Handshake(Box::new(handshake)),
            reader: FrameReader::new(),
            outbox: Vec::new(),
            dead: false,
            причина: None,
        };

        // Первый шаг инициатора: «e».
        let step = peer
            .phase
            .as_handshake_mut()
            .expect("только что создано рукопожатие")
            .step(&[])
            .map_err(|e| TransportError::Other(e.to_string()))?;
        queue_handshake_step(&mut peer.outbox, step)?;
        peer.сбросить_очередь();

        self.peers.insert(handle, peer);

        // Событие `Connected` здесь **не** отправляется. TCP-соединение
        // открыто, но рукопожатие ещё не прошло, а называть устройство «на
        // связи» рано: через мгновение рукопожатие может провалиться, и
        // подпись не сойдётся. Событие уходит из `шаг_рукопожатия`, когда
        // соединение действительно готово к данным.
        Ok(handle)
    }

    fn take_handle(&mut self) -> u64 {
        let h = self.next_handle;
        self.next_handle += 1;
        h
    }

    /// Принимает всё, что накопилось, и возвращает события.
    pub fn poll(&mut self) -> Result<Vec<TransportEvent>, TransportError> {
        if self.listener.is_none() {
            return Err(TransportError::NotRunning);
        }

        self.announce_if_due();
        self.accept_all();
        self.read_discovery();
        self.pump_peers();

        Ok(std::mem::take(&mut self.events))
    }

    /// Повторяет объявление, если подошло время.
    fn announce_if_due(&mut self) {
        let Some(discovery) = &self.discovery else {
            return;
        };

        let due = self
            .last_announce
            .map(|t| t.elapsed() >= Duration::from_millis(ANNOUNCE_INTERVAL_MS))
            .unwrap_or(true);
        if !due {
            return;
        }
        self.last_announce = Some(Instant::now());

        let msg = match self.build_announcement(now_ms(), now_ms()) {
            Ok(m) => m,
            Err(e) => {
                self.events
                    .push(TransportEvent::Notice(TransportError::Other(e.to_string())));
                return;
            }
        };

        let target = SocketAddrV4::new(
            Ipv4Addr::new(
                BROADCAST_ADDR[0],
                BROADCAST_ADDR[1],
                BROADCAST_ADDR[2],
                BROADCAST_ADDR[3],
            ),
            DISCOVERY_PORT,
        );

        // Ошибка отправки не фатальна: подсеть вправе запрещать широковещание.
        // Тогда устройство найдут по адресу вручную, и это честнее отказа в
        // канале.
        if let Err(e) = discovery.send_to(&msg, target) {
            if e.kind() != ErrorKind::WouldBlock {
                self.events
                    .push(TransportEvent::Notice(TransportError::Io(e)));
            }
        }
    }

    /// Принимает все входящие соединения, не блокируя.
    ///
    /// Сокет заимствуется на первый проход, а соединения заводятся во втором:
    /// иначе компилятор не даст одновременно держать ссылку на сокет и менять
    /// список соединений. Такое разбиение выглядит лишним, но прямое
    /// написание — ошибка заимствования, а не ошибка логики.
    fn accept_all(&mut self) {
        let mut принято = Vec::new();

        if let Some(listener) = &self.listener {
            loop {
                match listener.accept() {
                    Ok((stream, remote)) => {
                        if stream.set_nonblocking(true).is_err() {
                            continue;
                        }
                        let _ = stream.set_nodelay(true);
                        принято.push((stream, remote));
                    }
                    Err(ref e) if e.kind() == ErrorKind::WouldBlock => break,
                    Err(ref e) if e.kind() == ErrorKind::Interrupted => continue,
                    Err(e) => {
                        self.events
                            .push(TransportEvent::Notice(TransportError::Io(e)));
                        break;
                    }
                }
            }
        }

        for (stream, remote) in принято {
            // Ответчик обязан принять первое сообщение от любого, кто
            // подключился: в Noise XX оно несёт только эфемерный ключ. Личность
            // проверяется подписью Hello поверх уже установленного канала.
            let handshake = match Handshake::accept(&self.keys) {
                Ok(h) => h,
                Err(e) => {
                    self.events
                        .push(TransportEvent::Notice(TransportError::Other(e.to_string())));
                    continue;
                }
            };

            let handle = self.take_handle();
            self.peers.insert(
                handle,
                Peer {
                    stream,
                    remote,
                    role: Role::Responder,
                    handle,
                    phase: Phase::Handshake(Box::new(handshake)),
                    reader: FrameReader::new(),
                    outbox: Vec::new(),
                    dead: false,
                    причина: None,
                },
            );

            self.events.push(TransportEvent::Incoming(Connection::new(
                handle,
                Role::Responder,
                remote,
                Transport::Lan,
            )));
        }
    }

    /// Читает объявления соседей.
    ///
    /// Подпись здесь не сверяется: по UDP у нас нет ключа напарника, есть только
    /// его собственное заявление. Сверка происходит на соединении, где ключ
    /// известен. Поэтому объявление годится, чтобы показать «устройство
    /// рядом», и не годится, чтобы пустить в дом.
    fn read_discovery(&mut self) {
        let mut найдено: Vec<(Vec<u8>, SocketAddr, String, String)> = Vec::new();
        let mut сбой: Option<TransportError> = None;

        if let Some(discovery) = &self.discovery {
            let mut buf = [0u8; DISCOVERY_BUF];
            loop {
                let (n, откуда) = match discovery.recv_from(&mut buf) {
                    Ok(v) => v,
                    Err(ref e) if e.kind() == ErrorKind::WouldBlock => break,
                    Err(ref e) if e.kind() == ErrorKind::Interrupted => continue,
                    Err(e) => {
                        сбой = Some(TransportError::Io(e));
                        break;
                    }
                };

                let Ok((header, body, _)) = frame::split(&buf[..n]) else {
                    // Чужой пакет в нашем порту — обычное дело, не ошибка.
                    continue;
                };
                if header.kind != MessageKind::KindHello as i32 {
                    continue;
                }

                let Ok(hello) = Hello::decode(body) else {
                    continue;
                };
                let Some(identity) = hello.identity.as_ref() else {
                    continue;
                };
                if identity.device_id.is_empty() || identity.signing.is_none() {
                    continue;
                }

                // Ключ подписи нужен не только для отпечатка. Идентификатор —
                // это хэш ключа, и объявление присылает оба, значит заявление
                // проверяется прямо здесь, без соединения.
                //
                // Раньше ключ только проверялся на существование и выбрасывался,
                // а идентификатор принимался как есть. Устройство из подсети могло
                // назваться чужим идентификатором, и в списке это выглядело
                // как обычное устройство — отличить его было нечем.
                let Some(ключ) = identity
                    .signing
                    .as_ref()
                    .and_then(|s| <[u8; 32]>::try_from(&s.key[..]).ok())
                else {
                    continue;
                };
                let отпечаток = crate::crypto::fingerprint_from_signing(&ключ);
                if !crate::crypto::device_id_matches_signing(&identity.device_id, &ключ) {
                    crate::android_log::предостережение(&format!(
                        "объявление с {}: идентификатор {} не соответствует ключу {отпечаток}, отброшено",
                        откуда,
                        пригодное_имя(&String::from_utf8_lossy(&identity.device_id))
                    ));
                    continue;
                }

                // Порт данных объявлен в самом сообщении. Портом отправки
                // подставлять нельзя: UDP-объявление уходит с порта обнаружения,
                // и клиент подключался бы туда, где никто не слушает.
                let порт = u16::try_from(hello.lan_port).unwrap_or(0);
                if порт == 0 {
                    // Устройство объявило себя, но данных не принимает. Показывать
                    // его незачем: нажать будет не на что.
                    continue;
                }

                let id = identity.device_id.to_vec();
                // Своё объявление слышно и себе: напарником оно не является.
                if id.as_slice() == self.keys.device_id().as_bytes() {
                    continue;
                }

                // IPv4 обязателен: клиент на Android передаёт адрес четырьмя
                // байтами, и для IPv6 пришлось бы менять контракт. Объявление по
                // IPv6 сюда не дойдёт, поэтому отбрасываем его молча — это не
                // ошибка, а «канал так не работает».
                let IpAddr::V4(ip) = откуда.ip() else {
                    continue;
                };

                найдено.push((
                    id,
                    SocketAddr::from((ip, порт)),
                    пригодное_имя(&identity.display_name),
                    отпечаток,
                ));
            }
        }

        if let Some(e) = сбой {
            self.events.push(TransportEvent::Notice(e));
        }
        for (device_id, addr, name, fingerprint) in найдено {
            self.events.push(TransportEvent::Discovered {
                device_id,
                addr,
                name,
                fingerprint,
            });
        }
    }

    /// Пишет накопленное и читает входящее во всех соединениях.
    ///
    /// Соединения, умершие по любой причине, уходят из таблицы и порождают
    /// событие `Closed` — см. обход в конце. Причина хранится в самом
    /// соединении, [Peer::причина], и отдельного списка здесь нет.
    fn pump_peers(&mut self) {
        // Таблица соединений вынимается на время обработки: разбор кадра
        // кладёт события в `self.events`, а держать заимствование на `self.peers`
        // и на `self` сразу нельзя. Соединений единицы, даже десятки — перенос
        // не стоит ничего.
        let mut peers = std::mem::take(&mut self.peers);

        for (handle, peer) in peers.iter_mut() {
            peer.сбросить_очередь();

            let mut buf = [0u8; READ_CHUNK];
            loop {
                match peer.stream.read(&mut buf) {
                    Ok(0) => {
                        // Ноль байт — это конец потока: напарник закрыл сокет
                        // у себя. Отличается от ошибки чтения, и различие важно:
                        // «он закрыл» и «сеть отвалилась» требуют разных правок.
                        //
                        // Причина — именно `PeerClosed`, а не `NetworkLost`.
                        //
                        // Раньше здесь клались сразу две записи, `NetworkLost` и
                        // `PeerClosed`, и первая всегда побеждала: журнал показывал
                        // «сеть потеряна» там, где напарник спокойно закрыл сокет
                        // у себя. Комментарий над этим местом требовал различать
                        // «он закрыл» и «сеть отвалилась», а код различал наоборот —
                        // и обе записи об одном соединении ещё и множили события
                        // закрытия вдвое.
                        crate::android_log::инфо(&format!(
                            "{} закрыл соединение {}",
                            peer.remote, peer.handle
                        ));
                        peer.убить(CloseReason::PeerClosed);
                        break;
                    }
                    Ok(n) => {
                        peer.reader.push(&buf[..n]);
                        self.обработать(*handle, peer);
                    }
                    Err(ref e) if e.kind() == ErrorKind::WouldBlock => break,
                    Err(ref e) if e.kind() == ErrorKind::Interrupted => continue,
                    Err(e) => {
                        crate::android_log::ошибка(&format!(
                            "чтение из {} не удалось: {e}",
                            peer.remote
                        ));
                        peer.убить(CloseReason::NetworkLost);
                        break;
                    }
                }
            }
            peer.сбросить_очередь();
        }

        self.peers = peers;

        // Каждое соединение, помеченное мёртвым, обязано один раз уйти из
        // таблицы и один раз породить `Closed`.
        //
        // Раньше здесь обходился отдельный список, который наполняли только
        // обрывы чтения и записи, и обход шёл по нему. Соединения, умершие
        // при разборе кадра, при шаге рукопожатия или при неудачной
        // расшифровке, в список не попадали: сокет оставался открытым, запись в
        // таблице жила, клиент не получал ничего. Сокеты накапливались, и
        // `connect` рано или поздно падал с «слишком много открытых файлов» —
        // то есть ровно с тем `FL_ERR_TRANSPORT`, который и видели в журнале.
        //
        // Теперь список не нужен: у каждого соединения есть собственная
        // причина, и обход идёт по всем, а не по выборочно наполненному списку.
        let закрытые = std::mem::take(&mut self.peers);
        for (handle, peer) in закрытые {
            match peer.причина {
                Some(reason) => {
                    crate::android_log::инфо(&format!(
                        "соединение {} с {} закрыто: {:?}",
                        handle, peer.remote, reason
                    ));
                    self.events.push(TransportEvent::Closed {
                        conn: handle,
                        reason,
                        remote: peer.remote,
                    });
                }
                // Живое соединение возвращается на место.
                None => {
                    self.peers.insert(handle, peer);
                }
            }
        }
    }

    /// Продвигает рукопожатие или читает кадры.
    ///
    /// Обе фазы разбирают один и тот же поток, и различает их [Phase].
    fn обработать(&mut self, handle: u64, peer: &mut Peer) {
        loop {
            let frame = match peer.reader.next_frame() {
                Ok(Some(f)) => f,
                Ok(None) => return,
                Err(e) => {
                    // Плохой кадр — соединение разорвано: продолжать чтение
                    // нельзя, иначе напарник продолжит слать мусор.
                    //
                    // Причина обязана быть записана здесь же. Раньше тут стояло
                    // голое `dead = true`, и обход в `pump_peers` отправлял
                    // `Closed` только для обрывов чтения и записи, — а этот путь
                    // не отправлял ничего: соединение исчезало, не сказав ни
                    // слова ни клиенту, ни в журнал.
                    peer.убить(CloseReason::BadFrame);
                    self.events
                        .push(TransportEvent::Notice(TransportError::Frame(e)));
                    return;
                }
            };
            let (header, body) = frame;

            let is_handshake = matches!(peer.phase, Phase::Handshake(_));
            if is_handshake {
                if header.kind != MessageKind::KindHandshake as i32 {
                    // Ответчик не может начать с зашифрованного кадра:
                    // расшифровывать нечем.
                    peer.убить(CloseReason::BadFrame);
                    self.events
                        .push(TransportEvent::Notice(TransportError::Other(format!(
                            "в рукопожатии от {} пришёл кадр типа {} вместо рукопожатия",
                            peer.remote, header.kind
                        ))));
                    return;
                }
                self.шаг_рукопожатия(peer, &body);
            } else {
                if header.kind != MessageKind::KindSealed as i32 {
                    // Молча выбросить такой кадр нельзя: он ушёл бы в никуда, и
                    // получатель потом неделю гадал бы, куда делись его данные.
                    // Соединение при этом остаётся живым — тип кадра может
                    // оказаться новым, а не ошибочным.
                    self.events.push(TransportEvent::Notice(
                        TransportError::Other(format!(
                            "от {} в канале данных пришёл кадр типа {} вместо зашифрованного; кадр выброшен",
                            peer.remote, header.kind
                        )),
                    ));
                    continue;
                }
                self.открыть_кадр(handle, peer, &body);
            }

            if peer.dead {
                return;
            }
        }
    }

    /// Один шаг рукопожатия по полученному сообщению.
    fn шаг_рукопожатия(&mut self, peer: &mut Peer, body: &[u8]) {
        let result = match peer.phase.as_handshake_mut() {
            Some(h) => h.step(body),
            None => return,
        };

        match result {
            Ok(step) => {
                if let Err(e) = queue_handshake_step(&mut peer.outbox, step) {
                    self.events
                        .push(TransportEvent::Notice(TransportError::Other(e.to_string())));
                    return;
                }

                // Рукопожатие кончилось — переводим соединение в данные, иначе
                // следующий кадр читался бы как шаг рукопожатия.
                if let Some(handshake) = peer.phase.as_handshake_mut() {
                    if handshake.is_finished() {
                        match handshake.take_session() {
                            Ok(session) => {
                                peer.phase = Phase::Data(Box::new(session));

                                // Только теперь соединение действительно готово
                                // к данным. Событие на открытии TCP было бы
                                // враньём: рукопожатие ещё могло провалиться,
                                // и подпись не сошлась бы.
                                crate::android_log::инфо(&format!(
                                    "рукопожатие завершено с {} (соединение {})",
                                    peer.remote, peer.handle
                                ));
                                self.events.push(TransportEvent::Connected(Connection::new(
                                    peer.handle,
                                    peer.role,
                                    peer.remote,
                                    Transport::Lan,
                                )));
                            }
                            Err(e) => {
                                // Сессия не поднялась после объявления о готовности.
                                // Причина обязана попасть в `Closed`, иначе клиент
                                // навсегда оставит устройство «на связи».
                                peer.убить(CloseReason::HandshakeFailed);
                                self.events
                                    .push(TransportEvent::Notice(TransportError::Other(
                                        e.to_string(),
                                    )));
                            }
                        }
                    }
                }
            }
            Err(e) => {
                // Отказ самого Noise: не то сообщение, не тот порядок, подмена
                // эфемерного ключа. Раньше причина терялась — соединение
                // помечалось мёртвым и обрывалось без события.
                peer.убить(CloseReason::HandshakeFailed);
                self.events
                    .push(TransportEvent::Notice(TransportError::Other(e.to_string())));
            }
        }
    }

    /// Расшифровывает кадр и отдаёт клиенту.
    ///
    /// Под шифрование уходит **целый** кадр вместе с заголовком, а не только
    /// тело. Иначе тип сообщения пришлось бы объявлять дважды: один раз в
    /// заголовке кадра, другой — внутри. Расшифровав, получатель разбирает тот
    /// же кадр тем же кодом, что и отправитель, и расхождение между двумя
    /// разборщиками невозможно по построению.
    fn открыть_кадр(&mut self, handle: u64, peer: &mut Peer, body: &[u8]) {
        let result = match peer.phase.as_session_mut() {
            Some(s) => s.open(body),
            None => return,
        };

        match result {
            Ok(payload) => {
                let Ok((header, inner, _)) = frame::split(&payload) else {
                    // Канал сошёлся, а внутри — мусор. Значит ключи не те: тот,
                    // кто шифровал, использовал другие. Закрываем.
                    peer.убить(CloseReason::BadFrame);
                    self.events
                        .push(TransportEvent::Notice(TransportError::Frame(
                            frame::FrameError::BadMagic,
                        )));
                    return;
                };
                self.events.push(TransportEvent::Frame {
                    conn: handle,
                    header,
                    body: inner.to_vec(),
                });
            }
            Err(e) => {
                // Нерасшифровалось: это не наш канал или подмена. Соединение
                // закрывается — продолжать читать из него незачем.
                peer.убить(CloseReason::BadFrame);
                self.events
                    .push(TransportEvent::Notice(TransportError::Other(e.to_string())));
            }
        }
    }
}

impl Phase {
    fn as_handshake_mut(&mut self) -> Option<&mut Handshake> {
        match self {
            Phase::Handshake(h) => Some(h),
            Phase::Data(_) => None,
        }
    }

    fn as_session_mut(&mut self) -> Option<&mut Session> {
        match self {
            Phase::Data(s) => Some(s),
            Phase::Handshake(_) => None,
        }
    }
}

/// Кладёт результат шага рукопожатия в очередь отправки.
///
/// Кадр нужен, а не сырое сообщение Noise: иначе получатель не знал бы, где
/// кончилось сообщение. Шифровать здесь нечего — рукопожатие идёт открыто, и
/// это обязательный порядок протокола, а не утечка.
fn queue_handshake_step(outbox: &mut Vec<u8>, step: HandshakeStep) -> Result<(), TransportError> {
    match step {
        HandshakeStep::Send(data) => {
            let frame = frame::encode_raw(MessageKind::KindHandshake as i32, 0, &data)
                .map_err(TransportError::Frame)?;
            outbox.extend_from_slice(&frame);
            Ok(())
        }
        HandshakeStep::Complete => Ok(()),
        // Ждать ввода — значит сообщение от напарника уже обработано и
        // отправлять нечего. Молчание здесь правильное, а не потерянный кадр:
        // предыдущее сообщение мы уже положили в очередь.
        HandshakeStep::WaitForInput => Ok(()),
    }
}

/// Сколько байт имени можно показать в списке.
///
/// Модель телефона — это десятка-двадцать символов. Всё, что длиннее, не модель,
/// а мусор: либо чужое объявление, либо устройство, назвавшее себя описанием.
/// Обрезается здесь же, а не на экране, потому что длинная строка в списке
/// устройств ломает вёрстку плитки, и это видно сразу.
const МАКСИМУМ_ИМЕНИ: usize = 64;

/// Приводит имя из объявления к виду, безопасному для показа.
///
/// Объявление пришло от кого угодно в подсети и подписи не проверено, поэтому
/// проверяется всё, что может испортить интерфейс:
///
/// * управляющие символы — иначе имя может стереть строку после себя или
///   испортить вывод в журнал;
/// * длина — иначе плитка растянется;
/// * пустое имя — тогда показывать нечего, и подставляется честное «Устройство».
///
/// Возвращается `String`, а не срез: проверка границы UTF-8 обязана произойти
/// до отсечения, иначе обрыв в середине кириллического символа дал бы
/// невалидный UTF-8 на выходе.
fn пригодное_имя(сырое: &str) -> String {
    let чистое: String = сырое
        .chars()
        .filter(|c| !c.is_control())
        .collect::<String>()
        .trim()
        .to_owned();

    if чистое.is_empty() {
        return "Устройство".to_owned();
    }

    // Обрезка по границе символов: `.take()` на байтах мог бы разрезать
    // многобайтовый символ, и имя пришло бы в интерфейс битым.
    let обрезанное: String = чистое.chars().take(МАКСИМУМ_ИМЕНИ).collect();
    if обрезанное.is_empty() {
        "Устройство".to_owned()
    } else {
        обрезанное
    }
}

/// Текущее время в миллисекундах.
fn now_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

impl Channel for LanTransport {
    fn kind(&self) -> Transport {
        Transport::Lan
    }

    fn state(&self) -> TransportState {
        self.state
    }

    /// Отправляет клиенту собранный кадр: на вход целый кадр
    /// ([crate::frame::encode]), на выходе он же дойдёт до напарника в
    /// расшифрованном виде.
    ///
    /// До завершения рукопожатия отправка данных невозможна: `Unsupported`,
    /// а не молчаливая потеря. Клиент шлёт шаги рукопожатия сам, через
    /// [LanTransport::connect] и `poll`.
    fn send(&mut self, conn: u64, payload: &[u8]) -> Result<(), TransportError> {
        let peer = self
            .peers
            .get_mut(&conn)
            .ok_or(TransportError::NotRunning)?;
        if peer.dead {
            return Err(TransportError::NotRunning);
        }

        let sealed = match peer.phase.as_session_mut() {
            Some(session) => session
                .seal(payload)
                .map_err(|e| TransportError::Other(e.to_string()))?,
            None => return Err(TransportError::Unsupported("рукопожатие не завершено")),
        };

        let frame = frame::encode_raw(MessageKind::KindSealed as i32, 0, &sealed)
            .map_err(TransportError::Frame)?;
        peer.outbox.extend_from_slice(&frame);
        Ok(())
    }

    /// Закрывает соединение по просьбе клиента.
    ///
    /// Причина — «по команде», а не «напарник закрыл»: различать их нужно,
    /// иначе собственное действие выглядело бы как чужая поломка.
    fn close(&mut self, conn: u64) -> Result<(), TransportError> {
        // Адрес читается **до** удаления соединения: иначе в событии не осталось
        // бы ничего, кроме хэндла, и в журнале нельзя было бы понять, чьё это
        // было соединение.
        let remote = match self.peers.remove(&conn) {
            Some(peer) => peer.remote,
            None => {
                // Соединения уже нет: событие всё равно нужно, иначе клиент
                // ждал бы закрытия, которого не будет.
                return Err(TransportError::NotRunning);
            }
        };

        self.events.push(TransportEvent::Closed {
            conn,
            reason: CloseReason::ByRequest,
            remote,
        });
        Ok(())
    }

    fn stop(&mut self) -> Result<(), TransportError> {
        self.state = TransportState::Stopping;
        self.peers.clear();
        self.events.clear();
        self.listener = None;
        self.discovery = None;
        self.state = TransportState::Idle;
        Ok(())
    }

    fn announce(&mut self) -> Result<(), TransportError> {
        self.last_announce = None;
        self.announce_if_due();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pb::Heartbeat;
    // Постоянный порт нужен только тестам: приложение передаёт его само, а в
    // ядре константа живёт в документации и проверках перезапуска.
    use crate::transport::LAN_PORT;
    use prost::Message;

    fn транспорт() -> LanTransport {
        LanTransport::bind(DeviceKeys::generate(), "Тест".to_owned(), 0).expect("порт свободен")
    }

    /// Порт переживает перезапуск транспорта.
    ///
    /// Раньше транспорт поднимался на любом свободном порту, и он менялся при
    /// каждом запуске. Напарник узнаёт адрес из объявления, живого две секунды,
    /// поэтому после перезапуска у него оставался старый порт — и подключение
    /// падало с «недоступно» без всякого объяснения в журнале.
    #[test]
    fn порт_переживает_перезапуск() {
        let первый = LanTransport::bind(DeviceKeys::generate(), "A".to_owned(), LAN_PORT)
            .expect("постоянный порт свободен");
        let занятый = первый.port();
        drop(первый);

        let второй = LanTransport::bind(DeviceKeys::generate(), "A".to_owned(), LAN_PORT)
            .expect("порт освободился после остановки");
        assert_eq!(второй.port(), занятый, "порт изменился после перезапуска");
    }

    /// Занятый постоянный порт не мешает каналу: берётся свободный.
    ///
    /// Отказать в канале из-за занятого порта хуже, чем работать на другом:
    /// напарник всё равно узнает настоящий порт из объявления.
    #[test]
    fn занятый_порт_не_мешает_каналу() {
        let первый = LanTransport::bind(DeviceKeys::generate(), "A".to_owned(), LAN_PORT)
            .expect("первый поднялся");

        let второй = LanTransport::bind(DeviceKeys::generate(), "B".to_owned(), LAN_PORT)
            .expect("второй тоже поднялся, хоть порт занят");
        assert_ne!(
            второй.port(),
            первый.port(),
            "второй должен был взять свободный порт"
        );
    }

    #[test]
    fn мёртвое_соединение_всегда_даёт_одно_событие_и_уходит_из_таблицы() {
        let mut a = транспорт();
        let b = транспорт();

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        let handle = a.connect(target.into()).expect("соединение");

        // Убиваем соединение через тот же путь, каким пользовались при разборе
        // кадра: раньше он помечал соединение мёртвым, но не слал `Closed` и не
        // убирал его из таблицы.
        a.peers
            .get_mut(&handle)
            .expect("соединение на месте")
            .убить(CloseReason::HandshakeFailed);

        let mut закрытия = Vec::new();
        for _ in 0..5 {
            for событие in a.poll().expect("poll A") {
                if let TransportEvent::Closed { reason, conn, .. } = событие {
                    закрытия.push((conn, reason));
                }
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        assert_eq!(
            закрытия.len(),
            1,
            "ожидалось ровно одно событие Closed, а пришло {}: {закрытия:?}",
            закрытия.len()
        );
        assert_eq!(закрытия[0].0, handle);
        assert_eq!(
            закрытия[0].1,
            CloseReason::HandshakeFailed,
            "причина потерялась или подменилась"
        );
        assert!(
            a.peers.is_empty(),
            "мёртвое соединение осталось в таблице: {:?}",
            a.peers.keys().collect::<Vec<_>>()
        );
    }

    /// Живое соединение обход не трогает.
    ///
    /// Обход идёт по всем соединениям, а не по списку закрытий, и ошибка в нём
    /// была бы обратной: выбрасывание живого соединения с тихим `Closed`.
    #[test]
    fn живое_соединение_остаётся_в_таблице() {
        let mut a = транспорт();
        let mut b = транспорт();

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        let handle = a.connect(target.into()).expect("соединение");

        for _ in 0..3 {
            for событие in a.poll().expect("poll A") {
                assert!(
                    !matches!(событие, TransportEvent::Closed { .. }),
                    "живое соединение объявлено закрытым"
                );
            }
            let _ = b.poll();
        }

        assert!(
            a.peers.contains_key(&handle),
            "живое соединение выброшено из таблицы"
        );
    }

    /// Напарник закрыл сокет — причина «он закрыл», а не «сеть отвалилась».
    ///
    /// Раньше на конце потока клались сразу две причины, `NetworkLost` и
    /// `PeerClosed`, и первая всегда побеждала: журнал годами показывал «сеть
    /// потеряна» там, где напарник спокойно закрыл соединение у себя. Различие
    /// между «он закрыл» и «сеть отвалилась» и было смыслом всего журнала.
    #[test]
    fn конец_потока_это_не_обрыв_сети() {
        let mut a = транспорт();
        let mut b = транспорт();

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        let _handle = a.connect(target.into()).expect("соединение");

        // Даём рукопожатию дойти до конца, чтобы на той стороне не осталось
        // непрочитанного: иначе сокет закроется с resetting, и причина была бы
        // другой по существу, хотя и той же по коду.
        for _ in 0..50 {
            let _ = a.poll();
            let _ = b.poll();
            if b.peers.values().any(|p| matches!(p.phase, Phase::Data(_))) {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        drop(b);

        let mut причины = Vec::new();
        for _ in 0..50 {
            for событие in a.poll().expect("poll A") {
                if let TransportEvent::Closed { reason, .. } = событие {
                    причины.push(reason);
                }
            }
            if !причины.is_empty() {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        assert_eq!(
            причины,
            vec![CloseReason::PeerClosed],
            "причина закрытия определена неверно"
        );
    }

    /// Собственное объявление проходит сверку идентификатора с ключом.
    ///
    /// Проверка живёт в `read_discovery` и без неё отбросила бы всё подряд,
    /// включая честные устройства. Тест на собственном объявлении ловит именно это:
    /// ошибку в самой проверке, а не в подделке.
    #[test]
    fn своё_объявление_проходит_сверку() {
        let t = транспорт();
        let msg = t.build_announcement(1, 1).expect("объявление");
        let (_, body, _) = frame::split(&msg).expect("кадр объявления");
        let hello = Hello::decode(body).expect("hello");
        let identity = hello.identity.as_ref().expect("идентичность");
        let ключ: [u8; 32] = identity.signing.as_ref().expect("ключ подписи").key[..]
            .try_into()
            .expect("ключ на 32 байта");

        assert!(
            crate::crypto::device_id_matches_signing(&identity.device_id, &ключ),
            "честное объявление не прошло бы сверку и было бы отброшено"
        );
    }

    /// Объявление с чужим идентификатором отвергается.
    ///
    /// Устройство из подсети может прислать любой идентификатор с любым ключом.
    /// Раньше объявление принималось как есть, и подделка не отличалась от
    /// обычного устройства ничем.
    #[test]
    fn подделанный_идентификатор_отвергается() {
        let честное = транспорт();
        let чужое = транспорт();
        let msg = чужое.build_announcement(1, 1).expect("объявление");
        let (_, body, _) = frame::split(&msg).expect("кадр объявления");
        let mut hello = Hello::decode(body).expect("hello");

        // Подставляем чужой идентификатор, оставляя ключ прежним.
        let чужой_id = честное.device_id();
        // Идентификатор на проводе — 16 сырых байт, а `device_id()` отдаёт текст,
        // поэтому подставляем текст целиком: длина в проверке не оговорена, а
        // несовпадение всё равно обязано обнаружиться.
        if let Some(identity) = hello.identity.as_mut() {
            identity.device_id = prost::bytes::Bytes::from(чужой_id);
        }

        let identity = hello.identity.as_ref().expect("идентичность");
        let ключ: [u8; 32] = identity.signing.as_ref().expect("ключ подписи").key[..]
            .try_into()
            .expect("ключ на 32 байта");

        assert!(
            !crate::crypto::device_id_matches_signing(&identity.device_id, &ключ),
            "объявление с чужим идентификатором прошло сверку"
        );
    }

    /// Отпечаток в объявлении выводится из ключа, а не из имени.
    #[test]
    fn отпечаток_считается_из_объявления() {
        let t = транспорт();
        let msg = t.build_announcement(1, 1).expect("объявление");
        let (_, body, _) = frame::split(&msg).expect("кадр объявления");
        let hello = Hello::decode(body).expect("hello");
        let identity = hello.identity.as_ref().expect("идентичность");
        let ключ: [u8; 32] = identity.signing.as_ref().expect("ключ подписи").key[..]
            .try_into()
            .expect("ключ на 32 байта");

        let отпечаток = crate::crypto::fingerprint_from_signing(&ключ);
        assert_eq!(отпечаток.len(), 19, "получилось «{отпечаток}»");
        assert_ne!(
            отпечаток, identity.display_name,
            "отпечаток не должен совпадать с именем"
        );
    }

    #[test]
    fn поднимается_и_останавливается() {
        let mut t = транспорт();
        assert_eq!(t.kind(), Transport::Lan);
        assert_ne!(t.state(), TransportState::Idle);

        t.stop().expect("остановка");
        assert_eq!(t.state(), TransportState::Idle);
        assert!(t.poll().is_err(), "после остановки poll не работает");
    }

    /// Имя из объявления доходит до клиента.
    ///
    /// Раньше оно разбиралось и выбрасывалось, а список показывал «Устройство»:
    /// в общей квартире, где рядом лежит чужой телефон, это ни о чём не
    /// говорило.
    #[test]
    fn имя_устройства_доходит_из_объявления() {
        let t = LanTransport::bind(DeviceKeys::generate(), "Redmi Note 12".to_owned(), 0)
            .expect("порт");
        let msg = t.build_announcement(1, 1).expect("объявление");
        let (_, body, _) = frame::split(&msg).expect("кадр");
        let hello = Hello::decode(body).expect("тело");

        let имя = hello.identity.unwrap().display_name;
        assert_eq!(пригодное_имя(&имя), "Redmi Note 12");
    }

    /// Имя из чужого объявления не может испортить интерфейс.
    ///
    /// Проверяются именно те три свойства, из-за которых пришлось писать
    /// `пригодное_имя`: управляющие символы, длина и пустота.
    #[test]
    fn чужое_имя_не_ломает_интерфейс() {
        // Управляющие символы вырезаются, а не выводятся.
        assert_eq!(
            пригодное_имя("Redmi\u{0007}Note\u{0000}"),
            "RedmiNote",
            "управляющие символы попали бы в интерфейс"
        );

        // Длинное имя обрезается.
        let длинное = "A".repeat(500);
        let обрезанное = пригодное_имя(&длинное);
        assert_eq!(обрезанное.chars().count(), МАКСИМУМ_ИМЕНИ);

        // Кириллица не разрезается на середине символа: длина в символах, а не в
        // байтах, иначе на экране появился бы битый символ.
        let кириллица = "Ж".repeat(100);
        let имя = пригодное_имя(&кириллица);
        assert_eq!(имя.chars().count(), МАКСИМУМ_ИМЕНИ);
        assert!(имя.chars().all(|c| c == 'Ж'));

        // Пустое имя — честная заглушка, а не пустая строка в плитке.
        assert_eq!(пригодное_имя(""), "Устройство");
        assert_eq!(пригодное_имя("   "), "Устройство");
        assert_eq!(пригодное_имя("\u{0007}\u{0000}"), "Устройство");
    }

    /// Объявление несёт настоящие ключи устройства.
    ///
    /// Если бы оно собиралось на свежих ключах, напарник получил бы отказ при
    /// сверке: в `Hello` один ключ, а при соединении другой. Тест ловит именно
    /// это расхождение, потому что вручную его не заметить — а поломка
    /// выглядела бы как «устройство нашлось, но не подключается».
    #[test]
    fn объявление_несёт_настоящие_ключи() {
        let t = LanTransport::bind(DeviceKeys::generate(), "Ноутбук".to_owned(), 0).expect("порт");
        let msg = t
            .build_announcement(42, 1_700_000_000_000)
            .expect("объявление");

        let (header, body, total) = frame::split(&msg).expect("кадр целый");
        assert_eq!(total, msg.len());
        assert_eq!(header.kind, MessageKind::KindHello as i32);

        let hello = Hello::decode(body).expect("тело разобрано");
        let identity = hello.identity.as_ref().expect("идентичность");
        assert_eq!(identity.display_name, "Ноутбук");
        assert_eq!(hello.nonce, 42);

        let заявленный = identity.signing.as_ref().expect("ключ есть").key.to_vec();
        assert_eq!(
            заявленный.as_slice(),
            t.signing_public().as_slice(),
            "объявление подписано не тем ключом, который у устройства"
        );
        assert_eq!(
            identity.device_id.to_vec(),
            t.device_id(),
            "объявление от чужого идентификатора"
        );
    }

    #[test]
    fn порт_из_сокета_реален() {
        let t = транспорт();
        assert_ne!(t.port(), 0, "bind 0 обязан заменить порт на настоящий");
    }

    /// События забираются один раз: второй poll не повторяет первую порцию.
    ///
    /// Иначе список устройств до конца работы приложения повторял бы одни и те
    /// же объявления.
    #[test]
    fn события_не_повторяются() {
        let mut t = транспорт();
        assert!(
            t.poll().expect("первый poll").is_empty(),
            "при запуске событий нет"
        );
        assert!(
            t.poll().expect("второй poll").is_empty(),
            "первая порция событий не должна повторяться"
        );
    }

    /// Рукопожатие проходит по двум настоящим сокетам.
    ///
    /// Ключевая проверка транспорта: без неё канал может выглядеть рабочим,
    /// пока не выяснится, что данные не идут.
    #[test]
    fn два_транспорта_договариваются_и_передают_данные() {
        let mut a = LanTransport::bind(DeviceKeys::generate(), "A".to_owned(), 0).expect("порт A");
        let mut b = LanTransport::bind(DeviceKeys::generate(), "B".to_owned(), 0).expect("порт B");

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        let handle = a.connect(target.into()).expect("соединение");

        let mut видел_connect = false;
        let mut видел_incoming = false;

        for _ in 0..50 {
            for событие in a.poll().expect("poll A") {
                if let TransportEvent::Connected(c) = событие {
                    assert_eq!(c.handle, handle);
                    assert_eq!(c.role, Role::Initiator);
                    видел_connect = true;
                }
            }
            for событие in b.poll().expect("poll B") {
                if let TransportEvent::Incoming(c) = событие {
                    assert_eq!(c.role, Role::Responder);
                    видел_incoming = true;
                }
            }

            if видел_connect && видел_incoming {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        assert!(видел_connect, "инициатор не увидел события Connected");
        assert!(видел_incoming, "ответчик не увидел входящего соединения");

        // Рукопожатие обязано дойти до данных с обеих сторон.
        let mut данные = false;
        for _ in 0..50 {
            a.poll().expect("poll A");
            b.poll().expect("poll B");
            if a.peers
                .get(&handle)
                .is_some_and(|p| matches!(p.phase, Phase::Data(_)))
                && b.peers.values().any(|p| matches!(p.phase, Phase::Data(_)))
            {
                данные = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(данные, "рукопожатие не завершилось за отведённое время");

        // И главное: кадр, отправленный A, доходит до B в расшифрованном виде.
        let тело = Heartbeat {
            unix_time_ms: 1_700_000_000_000,
            battery_percent: 77,
            charging: true,
            transport: crate::pb::TransportKind::TransportLan as i32,
            measured_kbps: 4200,
        };
        let кадр = frame::encode(MessageKind::KindHeartbeat as i32, 0, &тело).expect("кадр собран");

        a.send(handle, &кадр).expect("отправка");

        let mut получен: Option<TransportEvent> = None;
        for _ in 0..50 {
            a.poll().expect("poll A");
            for событие in b.poll().expect("poll B") {
                if matches!(событие, TransportEvent::Frame { .. }) {
                    получен = Some(событие);
                }
            }
            if получен.is_some() {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        let Some(TransportEvent::Frame { header, body, .. }) = получен else {
            // Без этого утверждения тест выше проверял бы только то, что сокет
            // открылся, и тихо пропускал бы сломанное шифрование.
            panic!("кадр не дошёл: обмен данными не работает");
        };

        assert_eq!(header.kind, MessageKind::KindHeartbeat as i32);
        let разобран = Heartbeat::decode(body.as_slice()).expect("тело разобрано");
        assert_eq!(разобран.battery_percent, 77);
        assert_eq!(разобран.measured_kbps, 4200);
        assert!(разобран.charging);
    }

    /// Данные не проходят по чужому каналу.
    ///
    /// Если бы кадр шифровался и читался одними и теми же ключами, проверка
    /// была бы зелёной, а канал — открытым. Тест строит три независимые пары
    /// сессий и требует: своя пара читает, чужие — нет.
    ///
    /// Сокеты тут не нужны: речь о ключах, а не о сети.
    #[test]
    fn чужой_ключ_не_открывает_канал() {
        let (mut отправитель, mut получатель) =
            соглашение(&DeviceKeys::generate(), &DeviceKeys::generate());

        let кадр = frame::encode(
            MessageKind::KindHeartbeat as i32,
            0,
            &Heartbeat {
                unix_time_ms: 1,
                battery_percent: 10,
                charging: false,
                transport: 0,
                measured_kbps: 0,
            },
        )
        .expect("кадр собран");

        let шифр = отправитель.seal(&кадр).expect("зашифровано");

        // Своя пара читает: значит шифр вообще верный, и отказ ниже не из-за
        // того, что зашифровано мусором.
        let прочитано = получатель.open(&шифр).expect("своя пара читает");
        assert_eq!(прочитано, кадр);

        // Третье устройство, у которого с напарником своя сессия.
        let (mut чужой_отправитель, mut чужой_получатель) =
            соглашение(&DeviceKeys::generate(), &DeviceKeys::generate());
        assert!(
            чужой_получатель.open(&шифр).is_err(),
            "чужой ключ открыл канал: это не настоящее шифрование"
        );
        assert!(
            чужой_отправитель.open(&шифр).is_err(),
            "и отправитель с чужими ключами не должен открывать"
        );

        // Мусор вместо шифра тоже не открывается — разбор не должен падать.
        assert!(чужой_получатель.open(b"\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff\xff").is_err());
    }

    /// Прогоняет рукопожатие в памяти и отдаёт обе сессии.
    ///
    /// Без сокетов: нужно только проверить, что сессия на чужих ключах не
    /// расшифровывает чужой кадр, а сеть тут ни при чём.
    fn соглашение(
        ключи_a: &DeviceKeys, ключи_b: &DeviceKeys
    ) -> (Session, Session) {
        let mut a = Handshake::initiate(ключи_a).expect("инициатор");
        let mut b = Handshake::accept(ключи_b).expect("ответчик");

        let HandshakeStep::Send(первое) = a.step(&[]).expect("шаг 1") else {
            panic!("инициатор обязан отправить эфемерный ключ");
        };
        let HandshakeStep::Send(второе) = b.step(&первое).expect("шаг 2") else {
            panic!("ответчик обязан отправить своё и подтвердить эфемерный ключ");
        };
        let HandshakeStep::Send(третье) = a.step(&второе).expect("шаг 3") else {
            panic!("инициатор обязан отправить статический ключ");
        };
        let _ = b.step(&третье).expect("шаг 4");

        (
            a.take_session().expect("сессия инициатора"),
            b.take_session().expect("сессия ответчика"),
        )
    }

    /// «На связи» появляется только после рукопожатия, а не на открытии TCP.
    ///
    /// Раньше событие уходило сразу после `connect`, и подпись в плитке
    /// обещала связь заранее. Между открытием сокета и рукопожатием есть
    /// несколько обменов, и любая из них может провалиться — тогда пользователь
    /// увидел бы «на связи» у устройства, с которым связаться не удалось.
    ///
    /// Тест ловит именно момент: сразу после `connect` события быть не должно,
    /// а после рукопожатия — должно.
    #[test]
    fn на_связи_только_после_рукопожатия() {
        let mut a = LanTransport::bind(DeviceKeys::generate(), "A".to_owned(), 0).expect("порт A");
        let mut b = LanTransport::bind(DeviceKeys::generate(), "B".to_owned(), 0).expect("порт B");

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        let handle = a.connect(target.into()).expect("соединение");

        // Один опрос: TCP уже открыт, но ни одного байта рукопожатия ещё не
        // дошло, объявлять устройство «на связи» рано.
        a.poll().expect("poll A");
        // Один опрос: TCP уже открыт, но ни одного байта рукопожатия ещё не
        // дошло, объявлять устройство «на связи» рано.
        let первый = a.poll().expect("poll A");
        b.poll().expect("poll B");
        assert!(
            !первый
                .iter()
                .any(|e| matches!(e, TransportEvent::Connected(_))),
            "событие Connected пришло до рукопожатия: подпись обещала связь раньше времени"
        );

        // Доводим рукопожатие.
        let mut готово = false;
        for _ in 0..50 {
            for событие in a.poll().expect("poll A") {
                if let TransportEvent::Connected(c) = событие {
                    assert_eq!(c.handle, handle);
                    assert_eq!(c.role, Role::Initiator);
                    готово = true;
                }
            }
            b.poll().expect("poll B");
            if готово {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        assert!(
            готово,
            "после рукопожатия событие Connected так и не пришло"
        );
    }

    /// Ответчик не обязан знать напарника заранее: принимает первое сообщение
    /// от любого. Это свойство Noise XX, и его нельзя «усилить» проверкой в
    /// транспорте, не сломав протокол.
    #[test]
    fn ответчик_принимает_незнакомого() {
        let ключи_a = DeviceKeys::generate();
        let ключи_b = DeviceKeys::generate();
        let mut a = LanTransport::bind(ключи_a, "A".to_owned(), 0).expect("порт A");
        let mut b = LanTransport::bind(ключи_b, "B".to_owned(), 0).expect("порт B");

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        a.connect(target.into()).expect("соединение");

        let mut принято = false;
        for _ in 0..50 {
            a.poll().expect("poll A");
            for событие in b.poll().expect("poll B") {
                if matches!(событие, TransportEvent::Incoming(_)) {
                    принято = true;
                }
            }
            if принято {
                break;
            }
            std::thread::sleep(Duration::from_millis(10));
        }

        assert!(принято, "ответчик отверг первое сообщение незнакомого");
    }

    /// Очередь уходит в сокет целиком за один проход.
    ///
    /// Раньше запись делалась один раз за опрос, и остаток ждал следующего такта:
    /// 500 мс на каждый шаг рукопожатия. Цикл в [Peer::сбросить_очередь] убирает
    /// это ожидание там, где сокет действительно готов принять всё сразу.
    #[test]
    fn очередь_уходит_за_один_проход() {
        let mut a = транспорт();
        let b = транспорт();

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        let handle = a.connect(target.into()).expect("соединение");

        // Кладём в очередь заведомо больше, чем сокет примет с ходу, и смотрим,
        // что после одного прохода очередь уменьшилась, а не осталась целиком.
        // Размер подобран так, чтобы поместиться в буфер ядра на любой машине.
        let добавлено = 64 * 1024;
        a.peers
            .get_mut(&handle)
            .expect("соединение на месте")
            .outbox
            .resize(добавлено, 0);

        a.peers
            .get_mut(&handle)
            .expect("соединение на месте")
            .сбросить_очередь();

        let осталось = a.peers.get(&handle).expect("соединение живо").outbox.len();
        assert!(
            осталось < добавлено,
            "очередь не ушла за один проход: осталось {осталось} из {добавлено}"
        );
        assert!(
            !a.peers.get(&handle).expect("соединение живо").dead,
            "запись в свободный сокет не должна обрывать соединение"
        );
    }

    /// Ответные сообщения рукопожатия сбрасываются в сокет сразу при обработке,
    /// не дожидаясь следующего вызова poll.
    #[test]
    fn рукопожатие_проходит_без_задержки_в_очереди() {
        let mut a = LanTransport::bind(DeviceKeys::generate(), "A".to_owned(), 0).expect("порт A");
        let mut b = LanTransport::bind(DeviceKeys::generate(), "B".to_owned(), 0).expect("порт B");

        let target = std::net::SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
        let handle = a.connect(target.into()).expect("соединение");

        // Проход 1: B принимает TCP и читает msg1 (отправленный сразу в connect),
        // генерирует msg2 и сразу сбрасывает его в сокет.
        let _ = b.poll().expect("poll B 1");

        // Проход 2: A читает msg2, генерирует msg3, сразу сбрасывает его в сокет
        // и переходит в Data.
        let _ = a.poll().expect("poll A 1");

        // Проход 3: B читает msg3 и переходит в Data.
        let _ = b.poll().expect("poll B 2");

        assert!(
            a.peers
                .get(&handle)
                .is_some_and(|p| matches!(p.phase, Phase::Data(_))),
            "инициатор не перешёл в режим данных"
        );
        assert!(
            b.peers.values().any(|p| matches!(p.phase, Phase::Data(_))),
            "ответчик не перешёл в режим данных"
        );
    }
}
