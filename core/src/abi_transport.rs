//! Транспорт в C ABI.
//!
//! Наружу отдаётся ровно то, что нужно клиенту, и ничего больше: хэндл
//! транспорта, неблокирующий опрос событий и отправка готового кадра.
//!
//! ## События возвращаются одним вызовом
//!
//! События не висят в глобальной очереди и не забираются отдельным
//! `take_events`. Клиент зовёт `fl_transport_poll` и получает список целиком.
//! Два способа забрать одно и то же — ловушка: вторая попытка всегда
//! приходит пустой, и тест, проверяющий события, проходит, ничего не
//! проверяя. Такая ошибка уже была в LAN-транспорте до вывода наружу.
//!
//! ## Переменная длина вынесена в отдельный буфер
//!
//! Тела кадров разной длины, а событие — фиксированная структура, потому что
//! клиент на Android и Windows размещает её в своём коде. Событие несёт
//! *смещение* тела в буфере ответов, а не указатель: указатель в чужой памяти
//! клиенту бесполезен.

use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::OnceLock;

use crate::crypto::DeviceKeys;
use crate::registry::Registry;
use crate::transport::lan::LanTransport;
use crate::transport::Channel;
use crate::transport::{TransportEvent, TransportState};
use crate::{guard, guard_value, FlHandle, FlStatus, FL_ERR_INVALID_ARG, FL_ERR_NO_MEMORY, FL_OK};

// =============================================================================
// Типы событий
// =============================================================================

/// Состояние изменилось.
pub const FL_EV_STATE: u32 = 0;
/// Найдено устройство в сети.
pub const FL_EV_DISCOVERED: u32 = 1;
/// Пришло входящее соединение, рукопожатие начато.
pub const FL_EV_INCOMING: u32 = 2;
/// Соединение открыто нами.
pub const FL_EV_CONNECTED: u32 = 3;
/// Пришёл целый кадр в расшифрованном виде.
pub const FL_EV_FRAME: u32 = 4;
/// Соединение закрыто.
pub const FL_EV_CLOSED: u32 = 5;
/// Нефатальная ошибка: транспорт продолжает работать.
pub const FL_EV_NOTICE: u32 = 6;

/// Идентификатор устройства в байтах.
///
/// 32, а не 16, хотя идентификатор занимает 26 символов base32: при 16 он
/// **обрезался на полпути**, и клиент получал первую половину, показанную
/// шестнадцатеричным видом. В списке и на карточке это читалось как
/// «идентификатор», но им не являлось, и рядом с отпечатком ключа два числа
/// об одном устройстве выглядели независимыми.
///
/// С запасом: длина изменится вместе с форматом, и обрезание повторится, а
/// молча и незаметно. Лишние байты ничего не стоят.
pub const DEVICE_ID_BYTES: usize = 32;

/// Событие транспорта в виде фиксированной структуры.
///
/// Размер и порядок полей — часть контракта: клиент размещает эту структуру у
/// себя, и любая перестановка сломала бы его молча, а не с ошибкой сборки.
/// Обновление обязано быть дополнением в конце, а не перестановкой.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct FlTransEvent {
    /// Одно из `FL_EV_*`.
    pub event_type: u32,
    /// Тип сообщения для `FL_EV_FRAME`.
    pub kind: u32,
    /// Флаги кадра для `FL_EV_FRAME`.
    pub flags: u16,
    /// Состояние транспорта для `FL_EV_STATE`.
    pub state: u16,
    /// Хэндл соединения. Ноль там, где соединения нет.
    pub conn: u64,
    /// Длина тела в буфере ответов.
    pub payload_len: u32,
    /// Смещение тела в буфере ответов от его начала.
    pub payload_off: u32,
    /// Идентификатор устройства для `FL_EV_DISCOVERED`, до
    /// [DEVICE_ID_BYTES] байт, остальное нули.
    pub device_id: [u32; DEVICE_ID_BYTES / 4],
    /// Порт напарника для `FL_EV_DISCOVERED`.
    pub port: u16,
    /// Код ошибки для `FL_EV_NOTICE`.
    pub error: i32,
    /// Адрес напарника для `FL_EV_DISCOVERED`, четыре байта в сетевом порядке.
    ///
    /// Добавлено в конец структуры — единственное дополнение, которое правило
    /// «только в конец» разрешает. Перестановка или вставка в середину сломала бы
    /// клиента молча: у него уже собран код с прежними размерами, и ошибка
    /// была бы в данных, а не в сборке.
    ///
    /// Четыре байта, а не строка и не `struct sockaddr`: клиент на Android и так
    /// передаёт адрес в `fl_transport_connect` четырьмя байтами, и разбор строки
    /// дал бы ему лишний разборщик с правом ошибаться.
    pub ipv4: [u8; 4],
}

// =============================================================================
// Реестр транспортов
// =============================================================================

fn реестр() -> &'static Registry<LanTransport> {
    static РЕЕСТР: OnceLock<Registry<LanTransport>> = OnceLock::new();
    РЕЕСТР.get_or_init(Registry::new)
}

fn код_ошибки(e: &crate::transport::TransportError) -> i32 {
    use crate::transport::TransportError as E;
    match e {
        E::NotRunning | E::Unsupported(_) => FL_ERR_INVALID_ARG,
        E::Unreachable(_) => crate::FL_ERR_TRANSPORT,
        _ => crate::FL_ERR_TRANSPORT,
    }
}

fn код_состояния(s: TransportState) -> u16 {
    match s {
        TransportState::Idle => 0,
        TransportState::Announcing => 1,
        TransportState::Ready => 2,
        TransportState::Stopping => 3,
        TransportState::Failed => 4,
    }
}

// =============================================================================
// Наружные функции
// =============================================================================

/// Поднимает канал локальной сети.
///
/// Ключи берутся из файла устройства, а не создаются здесь: транспорт обязан
/// работать от настоящей личности, иначе напарник не сверит подпись.
///
/// `port` = 0 означает «выбери свободный».
///
/// # Safety
/// `keys_blob` — указатель на `keys_len` байт файла ключей, `display_name` —
/// на `name_len` байт UTF-8, `platform` — на `platform_len` байт UTF-8,
/// `device_kind` — на `device_kind_len` байт UTF-8.
/// Четыре указателя валидны и не пересекаются.
#[no_mangle]
pub unsafe extern "C" fn fl_transport_lan_start(
    keys_blob: *const u8,
    keys_len: usize,
    display_name: *const u8,
    name_len: usize,
    platform: *const u8,
    platform_len: usize,
    device_kind: *const u8,
    device_kind_len: usize,
    port: u16,
    out_handle: *mut FlHandle,
) -> FlStatus {
    guard(|| {
        if out_handle.is_null() || display_name.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let keys = match DeviceKeys::import(std::slice::from_raw_parts(keys_blob, keys_len)) {
            Ok(k) => k,
            Err(_) => return crate::FL_ERR_STORAGE,
        };
        let имя = match std::str::from_utf8(std::slice::from_raw_parts(display_name, name_len)) {
            Ok(s) => s.to_owned(),
            Err(_) => return FL_ERR_INVALID_ARG,
        };
        // Пустая платформа законна: клиент о системе может не знать, а ядро
        // знает о ней и подальше. Тогда строка на карточке останется пустой,
        // и это честнее, чем подставлять «неизвестно» от ядра.
        let платформа = if platform.is_null() {
            String::new()
        } else {
            match std::str::from_utf8(std::slice::from_raw_parts(platform, platform_len)) {
                Ok(s) => s.to_owned(),
                Err(_) => return FL_ERR_INVALID_ARG,
            }
        };

        // Тип устройства — тоже от клиента и тоже может быть неизвестен: у ядра
        // нет ни экрана, ни окна, и определить его можно только на стороне,
        // которая видит своё окно. Пустая строка честно дойдёт до карточки как
        // «неизвестно».
        let тип_устройства = if device_kind.is_null() {
            String::new()
        } else {
            match std::str::from_utf8(std::slice::from_raw_parts(device_kind, device_kind_len)) {
                Ok(s) => s.to_owned(),
                Err(_) => return FL_ERR_INVALID_ARG,
            }
        };

        match LanTransport::bind(keys, имя, платформа, port) {
            Ok(t) => {
                *out_handle = реестр().insert(t.с_типом_устройства(&тип_устройства));
                FL_OK
            }
            Err(_) => crate::FL_ERR_TRANSPORT,
        }
    })
}

/// Забирает накопившиеся события.
///
/// Один вызов возвращает всё, что есть. Тела кадров пишутся подряд в
/// `out_payloads`, а каждое событие несёт смещение своего тела.
///
/// Возвращает `FL_OK` и ноль событий, если ничего не пришло: пустой список —
/// не ошибка, а «жди дальше».
///
/// # Safety
/// `out_events` — буфер не менее чем на `max_events` структур,
/// `out_payloads` — буфер не менее чем на `payloads_cap` байт, `out_count` и
/// `out_used` — на `usize`. Указатели валидны и не пересекаются.
#[no_mangle]
pub unsafe extern "C" fn fl_transport_poll(
    handle: FlHandle,
    out_events: *mut FlTransEvent,
    max_events: usize,
    out_payloads: *mut u8,
    payloads_cap: usize,
    out_count: *mut usize,
    out_used: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_count.is_null() || out_used.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        *out_count = 0;
        *out_used = 0;

        if !out_events.is_null() && max_events == 0 {
            return FL_ERR_INVALID_ARG;
        }
        if payloads_cap > 0 && out_payloads.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        // Отличать «нет такого хэндла» от «опрос не удался» клиенту незачем:
        // в обоих случаях событий нет, а состояние он посмотрит через FL_EV_STATE.
        // Но молча выдавать пустоту на несуществующем хэндле нельзя — этим
        // освобождённый транспорт выглядел бы живым.
        let собранные = реестр().with(handle, |t| t.poll());
        let Some(результат) = собранные else {
            return FL_ERR_INVALID_ARG;
        };
        let события: Vec<TransportEvent> = результат.unwrap_or_default();
        if события.is_empty() {
            return FL_OK;
        }

        let mut unconsumed = Vec::new();
        let mut used = 0usize;
        let mut count = 0usize;

        for событие in события {
            if count >= max_events {
                unconsumed.push(событие);
                continue;
            }
            let (запись, тело) = в_c(&событие, used);
            let конец = used + тело.len();
            if конец > payloads_cap {
                // Тело не влезло: сохраняем событие для следующего опроса
                unconsumed.push(событие);
                continue;
            }

            if !out_payloads.is_null() && !тело.is_empty() {
                std::ptr::copy_nonoverlapping(тело.as_ptr(), out_payloads.add(used), тело.len());
            }
            used = конец;
            count += 1;

            if !out_events.is_null() {
                std::ptr::write(out_events.add(count - 1), запись);
            }
        }

        if !unconsumed.is_empty() {
            let _ = реестр().with(handle, |t| t.return_events(unconsumed));
        }

        *out_count = count;
        *out_used = used;
        FL_OK
    })
}

/// Переводит событие в структуру для клиента и отдаёт его тело отдельно.
fn в_c(событие: &TransportEvent, смещение: usize) -> (FlTransEvent, Vec<u8>) {
    let mut запись = FlTransEvent {
        payload_off: смещение as u32,
        ..Default::default()
    };

    match событие {
        TransportEvent::State(s) => {
            запись.event_type = FL_EV_STATE;
            запись.state = код_состояния(*s);
            (запись, Vec::new())
        }
        TransportEvent::Discovered {
            device_id,
            addr,
            name,
            fingerprint,
            platform,
            core_version,
            device_kind,
        } => {
            запись.event_type = FL_EV_DISCOVERED;
            запись.port = addr.port();
            // IPv4 обязателен: `read_discovery` отбрасывает объявления по IPv6,
            // и до сюда они не доходят. Если всё же дошли — адрес нулевой, и
            // клиент попытается подключиться к 0.0.0.0 и получит отказ.
            if let IpAddr::V4(ip) = addr.ip() {
                запись.ipv4 = ip.octets();
            }
            // Идентификатор короче 16 байт только у мусорного объявления.
            // Дописываем нулями, но не выходим за границу: иначе клиент на
            // Android получил бы запись за пределами своего буфера.
            let mut id = [0u8; DEVICE_ID_BYTES];
            let n = device_id.len().min(DEVICE_ID_BYTES);
            id[..n].copy_from_slice(&device_id[..n]);
            for (i, chunk) in id.chunks_exact(4).enumerate() {
                запись.device_id[i] = u32::from_le_bytes([chunk[0], chunk[1], chunk[2], chunk[3]]);
            }

            // Имя и отпечаток кладутся в слот тела, а не в отдельные поля
            // структуры. Причина та же, по которой тела кадров не в структуре:
            // переменная длина в фиксированной раскладке означала бы либо
            // обрезку, либо вторую копию раскладки в клиенте.
            //
            // Разделитель — нулевой байт. В имя он попасть не может:
            // `пригодное_имя` выбрасывает управляющие символы, и нулевой байт не
            // исключение. Проверять его отсутствие отдельно не нужно.
            let mut блоб = String::with_capacity(
                name.len()
                    + fingerprint.len()
                    + platform.len()
                    + core_version.len()
                    + device_kind.len()
                    + 4,
            );
            блоб.push_str(name);
            блоб.push('\0');
            блоб.push_str(fingerprint);
            блоб.push('\0');
            блоб.push_str(platform);
            блоб.push('\0');
            блоб.push_str(core_version);
            блоб.push('\0');
            // Тип устройства — пятым. Пустым он тоже бывает: напарник старый и
            // поля не прислал. Клиент читает пятую часть и показывает
            // «неизвестно», а не подставляет ноутбук.
            блоб.push_str(device_kind);
            запись.payload_off = смещение as u32;
            запись.payload_len = блоб.len() as u32;
            (запись, блоб.into_bytes())
        }
        TransportEvent::Incoming(c) => {
            запись.event_type = FL_EV_INCOMING;
            запись.conn = c.handle;
            // Адрес напарника заполняется и здесь, хотя поле называется как
            // для обнаружения: знать, **от кого** пришло соединение, нужно не
            // меньше, чем знать, что оно пришло. Без адреса входящее соединение
            // невозможно связать с устройством из списка, и на экране оно
            // выглядит как чужое.
            if let IpAddr::V4(ip) = c.remote.ip() {
                запись.ipv4 = ip.octets();
            }
            запись.port = c.remote.port();
            (запись, Vec::new())
        }
        TransportEvent::Connected(c) => {
            запись.event_type = FL_EV_CONNECTED;
            запись.conn = c.handle;
            if let IpAddr::V4(ip) = c.remote.ip() {
                запись.ipv4 = ip.octets();
            }
            запись.port = c.remote.port();
            (запись, Vec::new())
        }
        TransportEvent::Frame { conn, header, body } => {
            запись.event_type = FL_EV_FRAME;
            запись.conn = *conn;
            запись.kind = header.kind as u32;
            запись.flags = header.flags;
            запись.payload_len = body.len() as u32;
            (запись, body.to_vec())
        }
        TransportEvent::Closed {
            conn,
            reason,
            remote,
        } => {
            запись.event_type = FL_EV_CLOSED;
            запись.conn = *conn;
            // Причина кладётся в поле ошибки: для события закрытия оно иначе
            // не используется, а отдельное поле означало бы расширение раскладки
            // события ради одного случая.
            запись.error = reason.code() as i32;
            if let IpAddr::V4(ip) = remote.ip() {
                запись.ipv4 = ip.octets();
            }
            запись.port = remote.port();
            (запись, Vec::new())
        }
        TransportEvent::Notice(e) => {
            запись.event_type = FL_EV_NOTICE;
            запись.error = код_ошибки(e);
            (запись, Vec::new())
        }
    }
}

/// Подключается к устройству по адресу.
///
/// `ip4` — четыре байта адреса в сетевом порядке. Строками адрес не берётся
/// намеренно: разбор строки на стороне C дал бы клиенту лишний код и лишний
/// источник расхождений с тем, что уже напечатано в настройках.
///
/// # Safety
/// `ip4` — четыре байта, `out_conn` — на `FlHandle`. Оба валидны.
#[no_mangle]
pub unsafe extern "C" fn fl_transport_connect(
    handle: FlHandle,
    ip4: *const u8,
    port: u16,
    out_conn: *mut FlHandle,
) -> FlStatus {
    guard(|| {
        if out_conn.is_null() || ip4.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let октет = std::slice::from_raw_parts(ip4, 4);
        let addr = SocketAddr::from((Ipv4Addr::new(октет[0], октет[1], октет[2], октет[3]), port));

        match реестр().with(handle, |t| t.connect(addr)) {
            Some(Ok(conn)) => {
                *out_conn = FlHandle(conn);
                FL_OK
            }
            Some(Err(_)) => crate::FL_ERR_TRANSPORT,
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Отправляет готовый кадр в соединение.
///
/// На входе — целый кадр из `fl_frame_encode`, а не тело: тип сообщения
/// объявляется один раз, в своём заголовке.
///
/// # Safety
/// `frame` — указатель на `frame_len` байт. Указатель валиден.
#[no_mangle]
pub unsafe extern "C" fn fl_transport_send(
    handle: FlHandle,
    conn: u64,
    frame: *const u8,
    frame_len: usize,
) -> FlStatus {
    guard(|| {
        if frame.is_null() && frame_len > 0 {
            return FL_ERR_INVALID_ARG;
        }
        let данные = std::slice::from_raw_parts(frame, frame_len);

        match реестр().with(handle, |t| t.send(conn, данные)) {
            Some(Ok(())) => FL_OK,
            Some(Err(_)) => crate::FL_ERR_TRANSPORT,
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Возвращает объём неотправленных данных в очереди соединения (в байтах).
///
/// # Safety
/// `out_len` должен указывать на доступную для записи ячейку `usize`.
#[no_mangle]
pub unsafe extern "C" fn fl_transport_outbox_len(
    handle: FlHandle,
    conn: u64,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        match реестр().with(handle, |t| t.outbox_len(conn)) {
            Some(len) => {
                *out_len = len;
                FL_OK
            }
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Закрывает соединение, оставляя транспорт работать.
#[no_mangle]
pub extern "C" fn fl_transport_conn_close(handle: FlHandle, conn: u64) -> FlStatus {
    guard(|| match реестр().with(handle, |t| t.close(conn)) {
        Some(Ok(())) => FL_OK,
        Some(Err(_)) => crate::FL_ERR_TRANSPORT,
        None => FL_ERR_INVALID_ARG,
    })
}

/// Порт, на котором транспорт принимает соединения.
///
/// Клиенту нужен для показа пользователю и для ввода вручную, когда
/// широковещание запрещено.
///
/// Ноль означает «транспорта нет»: клиенту это нужно отличать от порта, и
/// показывать вместо него «0» в настройках было бы враньём.
#[no_mangle]
pub extern "C" fn fl_transport_port(handle: FlHandle) -> u16 {
    guard_value(|| реестр().with(handle, |t| t.port()).unwrap_or(0), 0)
}

/// Останавливает транспорт: сокеты закрыты, состояние сброшено.
#[no_mangle]
pub extern "C" fn fl_transport_stop(handle: FlHandle) -> FlStatus {
    guard(|| match реестр().with(handle, |t| t.stop()) {
        Some(Ok(())) => FL_OK,
        Some(Err(_)) => crate::FL_ERR_TRANSPORT,
        None => FL_ERR_INVALID_ARG,
    })
}

/// Освобождает транспорт.
///
/// Повторный вызов с тем же хэндл даёт ошибку, а не паникует: клиент вправе
/// освободить объект в обработчике ошибки, не зная, что он уже освобождён.
#[no_mangle]
pub extern "C" fn fl_transport_free(handle: FlHandle) -> FlStatus {
    guard(|| {
        if реестр().remove(handle) {
            FL_OK
        } else {
            FL_ERR_INVALID_ARG
        }
    })
}

/// Собирает кадр из готового protobuf-тела.
///
/// Клиенту нужен кадр, а не тело: тип сообщения объявляется в заголовке, и на
/// провод уходит он вместе с телом. Собирать кадр в клиенте запрещено — второй
/// разборщик рано или поздно разошёлся бы с ядром, и поломка выглядела бы как
/// «устройство не отвечает», а не как ошибка формата.
///
/// Как и в `fl_session_step`: `out_frame = NULL` спрашивает нужную длину и
/// возвращает `FL_ERR_INVALID_ARG`. Так клиенту не нужно выделять 4 МиБ на
/// каждый кадр.
///
/// # Safety
/// `body` — на `body_len` байт, `out_frame` — буфер не менее `*out_len` байт
/// либо NULL для запроса длины, `out_len` — на `usize`. Указатели валидны.
#[no_mangle]
pub unsafe extern "C" fn fl_frame_encode(
    kind: u32,
    flags: u16,
    body: *const u8,
    body_len: usize,
    out_frame: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_len.is_null() || (body.is_null() && body_len > 0) {
            return FL_ERR_INVALID_ARG;
        }
        // Нулевой указатель с нулевой длиной — это пустой срез, а не
        // from_raw_parts: тот на нулевом указателе неопределён и в отладочной
        // сборке валит проверка. Пустое тело законно — кадр без содержимого.
        let тело: &[u8] = if body.is_null() {
            &[]
        } else {
            std::slice::from_raw_parts(body, body_len)
        };

        // Тип в один байт не влезает — это ошибка вызывающего, а не кадра.
        if kind > u8::MAX as u32 {
            return FL_ERR_INVALID_ARG;
        }

        // Превышение предела тела — ошибка вызывающего, а не кадра: разницы
        // для клиента нет, оба случая означают «собери кадр заново».
        let кадр = match crate::frame::encode_raw(kind as i32, flags, тело) {
            Ok(f) => f,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        if out_frame.is_null() {
            if *out_len != 0 {
                return FL_ERR_INVALID_ARG;
            }
            *out_len = кадр.len();
            return FL_OK;
        }

        if *out_len < кадр.len() {
            // Буфер мал: сообщаем нужную длину, чтобы клиент перевыделил и
            // позвал снова. Молча обрезанный кадр хуже отказа.
            *out_len = кадр.len();
            return FL_ERR_NO_MEMORY;
        }

        std::ptr::copy_nonoverlapping(кадр.as_ptr(), out_frame, кадр.len());
        *out_len = кадр.len();
        FL_OK
    })
}

/// Размер структуры события для клиента.
///
/// Клиент сверяет это число со своим, чтобы замететь расхождение версий
/// заголовка: иначе он записал бы события мимо своего буфера.
#[no_mangle]
pub extern "C" fn fl_transport_event_size() -> usize {
    guard_value(std::mem::size_of::<FlTransEvent>, 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::crypto::DEVICE_KEYS_BLOB_LEN;
    use std::ptr;

    /// Две пары ключей для тестов: у каждой свой файл.
    fn пара() -> (DeviceKeys, Vec<u8>) {
        let keys = DeviceKeys::generate();
        let blob = keys.export();
        assert_eq!(blob.len(), DEVICE_KEYS_BLOB_LEN);
        (keys, blob)
    }

    unsafe fn старт(blob: &[u8]) -> FlHandle {
        let mut h = FlHandle(0);
        let status = fl_transport_lan_start(
            blob.as_ptr(),
            blob.len(),
            "Устройство".as_ptr(),
            "Устройство".len(),
            ptr::null(),
            0,
            ptr::null(),
            0,
            0,
            &mut h,
        );
        assert_eq!(status, FL_OK);
        h
    }

    #[test]
    fn транспорт_поднимается_по_файлу_ключей() {
        let (_, blob) = пара();
        let h = unsafe { старт(&blob) };
        assert!(h.is_valid());

        let порт = fl_transport_port(h);
        assert_ne!(порт, 0, "bind 0 обязан заменить порт");

        assert_eq!(fl_transport_free(h), FL_OK);
        assert_eq!(
            fl_transport_free(h),
            FL_ERR_INVALID_ARG,
            "повторное освобождение"
        );
    }

    /// Чужой или испорченный файл ключей обязан быть отвергнут.
    ///
    /// Иначе транспорт поднялся бы с чужими ключами, и устройство выглядело бы
    /// новым при каждом запуске — ровно та поломка, что была с идентификатором.
    #[test]
    fn испорченный_файл_ключей_отвергается() {
        let mut h = FlHandle(0);
        let мусор = [0u8; 16];
        let status = unsafe {
            fl_transport_lan_start(
                мусор.as_ptr(),
                мусор.len(),
                "X".as_ptr(),
                1,
                ptr::null(),
                0,
                ptr::null(),
                0,
                0,
                &mut h,
            )
        };
        assert_eq!(status, crate::FL_ERR_STORAGE);
    }

    #[test]
    fn пустое_имя_не_ломает_запуск() {
        // Имя может быть пустым: устройство ещё не названо. Это не повод
        // отказывать в канале.
        let (_, blob) = пара();
        let mut h = FlHandle(0);
        let status = unsafe {
            fl_transport_lan_start(
                blob.as_ptr(),
                blob.len(),
                "".as_ptr(),
                0,
                ptr::null(),
                0,
                ptr::null(),
                0,
                0,
                &mut h,
            )
        };
        assert_eq!(status, FL_OK);
        fl_transport_free(h);
    }

    /// Пустой опрос — не ошибка.
    ///
    /// Клиент зовёт `poll` в цикле, и отсутствие событий должно быть обычным
    /// ответом, а не сообщением об ошибке в журнал каждый кадр.
    #[test]
    fn пустой_опрос_возвращает_ноль_событий() {
        let (_, blob) = пара();
        let h = unsafe { старт(&blob) };

        let mut события = [FlTransEvent::default(); 8];
        let mut полезной = [0u8; 80];
        let mut count = 0usize;
        let mut used = 0usize;

        let status = unsafe {
            fl_transport_poll(
                h,
                события.as_mut_ptr(),
                события.len(),
                полезной.as_mut_ptr(),
                полезной.len(),
                &mut count,
                &mut used,
            )
        };
        assert_eq!(status, FL_OK);
        assert!(count <= события.len());
        fl_transport_free(h);
    }

    /// Устаревший хэндл не трогает новый транспорт.
    ///
    /// Слот переиспользуется, поэтому без поколения хэндл указал бы на чужой
    /// транспорт и клиент писал бы события чужому соединению.
    #[test]
    fn устаревший_хэндл_не_трогает_другой() {
        let (_, blob) = пара();
        let первый = unsafe { старт(&blob) };
        assert_eq!(fl_transport_free(первый), FL_OK);

        let второй = unsafe { старт(&blob) };
        assert_ne!(
            второй, первый,
            "поколение обязано вырасти, иначе старый хэндл указывал бы на новый транспорт"
        );

        // Старый хэндл обязан быть мёртвым.
        assert_eq!(fl_transport_port(первый), 0);
        assert_ne!(fl_transport_port(второй), 0);

        fl_transport_free(второй);
    }

    #[test]
    fn нули_в_указателях_отвергаются() {
        let mut h = FlHandle(0);
        let blob = [0u8; 4];
        assert_eq!(
            unsafe {
                fl_transport_lan_start(
                    blob.as_ptr(),
                    blob.len(),
                    "x".as_ptr(),
                    1,
                    // Платформа в этом вызове не проверяется: нулевой указатель на
                    // неё законен, а проверяется нулевой указатель на хранилище.
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0,
                    0,
                    std::ptr::null_mut(),
                )
            },
            FL_ERR_INVALID_ARG
        );
        assert_eq!(
            unsafe {
                fl_transport_lan_start(
                    blob.as_ptr(),
                    blob.len(),
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0,
                    std::ptr::null(),
                    0,
                    0,
                    &mut h,
                )
            },
            FL_ERR_INVALID_ARG
        );
        assert_eq!(fl_transport_free(FlHandle(0)), FL_ERR_INVALID_ARG);
        assert_eq!(fl_transport_port(FlHandle(0)), 0);
    }

    /// Идентификатор устройства ложится в структуру без выхода за границу.
    ///
    /// Ровно этот сценарий и защищает проверка длины: объявление из сети может
    /// нести идентификатор любой длины, а на Android запись за границей буфера
    /// убила бы процесс.
    #[test]
    fn идентификатор_любой_длины_кладётся_безопасно() {
        for длина in [0usize, 1, 15, 16, 17, 64] {
            let событие = TransportEvent::Discovered {
                device_id: vec![0xABu8; длина],
                addr: SocketAddr::from(([192, 168, 1, 5], 42424)),
                name: "Устройство".to_owned(),
                fingerprint: "ABCD EFGH IJKL MNOP".to_owned(),
                platform: "Android 13".to_owned(),
                core_version: "0.9.2".to_owned(),
                device_kind: "phone".to_owned(),
            };
            let (запись, тело) = в_c(&событие, 0);
            // Пятая часть — тип устройства. Проверяется он здесь же: забыть
            // дописать разделитель значит склеить версию с типом в одно
            // поле, и клиент показал бы «0.9.2phone» вместо «телефон».
            assert_eq!(
                тело,
                "Устройство\x00ABCD EFGH IJKL MNOP\x00Android 13\x000.9.2\x00phone".as_bytes()
            );
            assert_eq!(запись.event_type, FL_EV_DISCOVERED);
            assert_eq!(запись.port, 42424);
            // Всё, что поместилось, — правильные байты; лишнее — нули.
            let первый: [u8; 4] = запись.device_id[0].to_le_bytes();
            if длина >= 4 {
                assert_eq!(первый, [0xAB; 4]);
            }
        }
    }

    /// Имя и отпечаток доходят до клиента в теле, а не отдельными полями.
    ///
    /// Отпечаток отделён от имени нулевым байтом. Разделитель безопасен: имя
    /// очищено от управляющих символов ещё в транспорте, и нулевой байт туда
    /// попасть не может, — а проверить это в клиенте нечем.
    /// Тело объявления не разъезжается с идентификатором в структуре.
    ///
    /// Оба поля описывают одно устройство, и пользователь сверяет их глазами прямо
    /// на карточке. Если они расходятся хотя бы на символ, сверять нечего, и
    /// выглядит это как «приложение показывает разные данные».
    ///
    /// Проверяется здесь, а не в транспорте: тело события собирается отдельно от
    /// структуры, и разъехаться могут именно эти два места.
    #[test]
    fn отпечаток_в_теле_и_идентификатор_в_структуре_про_одно_устройство() {
        // Идентификатор как он приходит по проводу: текст base32.
        let id = b"AYZND56QWD2KTU3F4PV3TPLOFA".to_vec();
        let событие = TransportEvent::Discovered {
            device_id: id.clone(),
            addr: SocketAddr::from(([192, 168, 1, 140], 42424)),
            name: "Xiaomi 23021RAA2Y".to_owned(),
            // Отпечаток — первые 16 символов того же идентификатора.
            fingerprint: "AYZN D56Q WD2K TU3F".to_owned(),
            platform: "Android 13".to_owned(),
            core_version: "0.9.2".to_owned(),
            device_kind: "phone".to_owned(),
        };

        let (запись, тело) = в_c(&событие, 0);

        // Идентификатор из структуры: клиент читает его по DEVICE_ID_BYTES байт
        // и показывает как есть.
        let mut из_структуры = Vec::new();
        for chunk in запись.device_id.iter() {
            из_структуры.extend_from_slice(&chunk.to_le_bytes());
        }
        let id_сырой = String::from_utf8_lossy(&из_структуры).into_owned();
        let id_из_структуры = id_сырой.trim_end_matches('\0');
        assert_eq!(id_из_структуры, String::from_utf8_lossy(&id));

        // Отпечаток из тела: клиент делит тело по нулевому байту.
        //
        // Поля идут по порядку и жёстко: имя, отпечаток, платформа, версия,
        // тип устройства. Порядок проверяется числом частей, а содержимое — по
        // индексам, и перестановка полей местами сломала бы обе проверки.
        //
        // Пятой части раньше не было. Число частей проверяется именно потому,
        // что добавление поля без разделителя склеило бы версию с типом в одно
        // значение, а клиент показал бы «0.9.2phone» вместо «телефон».
        let части: Vec<&[u8]> = тело.split(|b| *b == 0).collect();
        assert_eq!(
            части.len(),
            5,
            "ожидались имя, отпечаток, платформа, версия и тип устройства, получилось {}",
            части.len()
        );
        let отпечаток = String::from_utf8_lossy(части[1]).into_owned();
        assert_eq!(отпечаток, "AYZN D56Q WD2K TU3F");
        assert_eq!(String::from_utf8_lossy(части[2]), "Android 13");
        assert_eq!(String::from_utf8_lossy(части[3]), "0.9.2");
        assert_eq!(String::from_utf8_lossy(части[4]), "phone");

        // И главное: отпечаток — начало идентификатора.
        let слитно: String = отпечаток.chars().filter(|c| *c != ' ').collect();
        assert!(
            id_из_структуры.starts_with(&слитно),
            "отпечаток «{отпечаток}» не начало идентификатора «{id_из_структуры}»"
        );
    }

    #[test]
    fn имя_и_отпечаток_идут_в_теле_события() {
        let событие = TransportEvent::Discovered {
            device_id: vec![7u8; 16],
            addr: SocketAddr::from(([192, 168, 1, 5], 42424)),
            name: "Redmi Note 12".to_owned(),
            fingerprint: "ABCD EFGH IJKL MNOP".to_owned(),
            platform: "Android 13".to_owned(),
            core_version: "0.9.2".to_owned(),
            device_kind: "phone".to_owned(),
        };
        let (запись, тело) = в_c(&событие, 24);

        assert_eq!(
            тело,
            "Redmi Note 12\x00ABCD EFGH IJKL MNOP\x00Android 13\x000.9.2\x00phone".as_bytes()
        );
        assert_eq!(запись.payload_off, 24, "смещение тела передаётся наружу");
        assert_eq!(запись.payload_len, тело.len() as u32);
    }

    /// Тело кадра кладётся в буфер по смещению, указанному в событии.
    #[test]
    fn тело_кладётся_по_смещению() {
        let событие = TransportEvent::Frame {
            conn: 7,
            header: crate::frame::FrameHeader {
                kind: 16,
                flags: 0,
                body_len: 3,
            },
            body: vec![1, 2, 3],
        };
        let (запись, тело) = в_c(&событие, 100);
        assert_eq!(запись.event_type, FL_EV_FRAME);
        assert_eq!(запись.conn, 7);
        assert_eq!(запись.kind, 16);
        assert_eq!(запись.payload_off, 100);
        assert_eq!(запись.payload_len, 3);
        assert_eq!(тело, &[1, 2, 3]);
    }

    /// Длина кадра узнаётся без буфера, потом кадр собирается в этот размер.
    ///
    /// Именно так клиент и будет пользоваться: иначе ему пришлось бы выделять
    /// 4 МиБ на каждый кадр.
    #[test]
    fn длина_кадра_узнаётся_без_буфера() {
        let тело = [1u8, 2, 3, 4, 5];

        let mut len = 0usize;
        let status = unsafe {
            fl_frame_encode(
                16,
                0,
                тело.as_ptr(),
                тело.len(),
                std::ptr::null_mut(),
                &mut len,
            )
        };
        assert_eq!(status, FL_OK);
        assert_eq!(len, crate::frame::HEADER_LEN + тело.len());

        let mut буфер = vec![0u8; len];
        let status = unsafe {
            fl_frame_encode(
                16,
                0,
                тело.as_ptr(),
                тело.len(),
                буфер.as_mut_ptr(),
                &mut len,
            )
        };
        assert_eq!(status, FL_OK);
        assert_eq!(&буфер[..2], &[0x42, 0x57], "начало пакета — «BW»");

        // Собранный кадр обязан разбираться тем же кодом, что и в ядре.
        let (header, содержимое, _) = crate::frame::split(&буфер).expect("кадр целый");
        assert_eq!(header.kind, 16);
        assert_eq!(содержимое, &тело);
    }

    /// Малый буфер даёт нужную длину, а не обрезанный кадр.
    ///
    /// Обрезанный кадр хуже отказа: клиент отправил бы половину файла и счёл бы
    /// передачу успешной.
    #[test]
    fn малый_буфер_даёт_нужную_длину() {
        let тело = [0u8; 32];
        let mut малый = [0u8; 4];
        let mut len = малый.len();

        let status = unsafe {
            fl_frame_encode(
                16,
                0,
                тело.as_ptr(),
                тело.len(),
                малый.as_mut_ptr(),
                &mut len,
            )
        };
        assert_eq!(status, FL_ERR_NO_MEMORY);
        assert_eq!(
            len,
            crate::frame::HEADER_LEN + тело.len(),
            "сообщена нужная длина"
        );
    }

    /// Ненулевая длина при запросе — ошибка вызывающего.
    #[test]
    fn запрос_длины_с_ненулевой_длиной_отвергается() {
        let тело = [1u8, 2, 3];
        let mut len = 99usize;
        let status = unsafe {
            fl_frame_encode(
                16,
                0,
                тело.as_ptr(),
                тело.len(),
                std::ptr::null_mut(),
                &mut len,
            )
        };
        assert_eq!(status, FL_ERR_INVALID_ARG);
    }

    /// Тип, не влезающий в байт заголовка, отвергается.
    ///
    /// Иначе кадр собрался бы с обрезанным типом и напарник прочитал бы его как
    /// совершенно другое сообщение.
    #[test]
    fn тип_не_влезающий_в_байт_отвергается() {
        let тело = [0u8];
        let mut буфер = [0u8; 80];
        let mut len = буфер.len();
        let status = unsafe {
            fl_frame_encode(
                300,
                0,
                тело.as_ptr(),
                тело.len(),
                буфер.as_mut_ptr(),
                &mut len,
            )
        };
        assert_eq!(status, FL_ERR_INVALID_ARG);
    }

    #[test]
    fn нули_в_кадре_отвергаются() {
        assert_eq!(
            unsafe {
                fl_frame_encode(
                    16,
                    0,
                    std::ptr::null(),
                    0,
                    std::ptr::null_mut(),
                    std::ptr::null_mut(),
                )
            },
            FL_ERR_INVALID_ARG
        );
        // Тело нулевой длины — законный кадр без содержимого, например bye.
        let mut буфер = [0u8; 32];
        let mut len = буфер.len();
        let status = unsafe {
            fl_frame_encode(16, 0, std::ptr::null(), 0, буфер.as_mut_ptr(), &mut len)
        };
        assert_eq!(status, FL_OK);
        assert_eq!(len, crate::frame::HEADER_LEN);
    }

    /// Размер структуры известен клиенту и не должен молча измениться.
    #[test]
    fn размер_структуры_стабилен() {
        assert_eq!(
            fl_transport_event_size(),
            std::mem::size_of::<FlTransEvent>()
        );
        // 64 байта: поля занимают 60, хвост добит выравниванием до 8.
        //
        // Структура выросла с 56, когда в конец добавили адрес напарника. Раньше
        // номер был зашит константой молча и проверялся только этим тестом:
        // без него клиент собрал бы массив событий по старому размеру, и ядро
        // записало бы записи мимо его буфера. Поэтому число и зафиксировано.
        assert_eq!(fl_transport_event_size(), 80);
    }

    /// Адрес напарника доходит до клиента.
    ///
    /// Без него подключиться было бы некуда: клиент знал бы только имя и порт,
    /// а порт у всех один, и подключение уходило бы в никуда.
    #[test]
    fn адрес_напарника_доходит_до_клиента() {
        let событие = TransportEvent::Discovered {
            device_id: vec![7u8; 16],
            addr: SocketAddr::from(([192, 168, 1, 42], 42424)),
            name: "Телефон".to_owned(),
            fingerprint: "ABCD EFGH IJKL MNOP".to_owned(),
            platform: "Android 13".to_owned(),
            core_version: "0.9.2".to_owned(),
            device_kind: "phone".to_owned(),
        };
        let (запись, _) = в_c(&событие, 0);

        assert_eq!(запись.ipv4, [192, 168, 1, 42]);
        assert_eq!(запись.port, 42424);
    }

    /// Структура не содержит ничего невыровненного.
    ///
    /// На Windows `u64` требует 8-байтового выравнивания, на Android — 4. Если
    /// поле `conn` окажется не по границе, клиент на одной из платформ прочитал бы
    /// мусор.
    #[test]
    fn выравнивание_совпадает_на_всех_платформах() {
        let s = std::mem::size_of::<FlTransEvent>();
        let a = std::mem::align_of::<FlTransEvent>();
        assert_eq!(s % a, 0);
        assert!(a >= std::mem::align_of::<u64>());
    }
}
