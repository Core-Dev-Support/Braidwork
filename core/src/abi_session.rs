//! Сессия наружу, через C ABI.
//!
//! Сессия живёт дольше одного вызова: рукопожатие идёт несколькими сообщениями,
//! а после него через тот же объект идёт зашифрованный обмен. Поэтому клиенту
//! нужен дескриптор, а не «позови нас ещё раз».
//!
//! Правила, общие для всей границы (см. `lib.rs`), здесь особенно важны:
//!
//! * буферы, которые ядро выдаёт, освобождаются только через `fl_free`;
//! * ни одна функция не `async`: транспорт клиента крутит цикл и зовёт ядро по
//!   мере готовности;
//! * `catch_unwind` на каждой внешней функции — паника внутри `snow` не должна
//!   разматывать стек через кадры клиента;
//! * хэндл проверяется на каждом вызове, а не только при выдаче: клиент
//!   держит его в своих переменных и вполне может отдать освобождённый.
//!
//! Протокол состояний — тот же автомат, что и внутри `session.rs`. Наружу
//! отдаются только три состояния: рукопожатие, готова, обмен. Собственного
//! флага «я что-то сделал» здесь нет намеренно.

use crate::registry::Registry;
use crate::session::{build_hello, parse_and_verify_hello, Handshake, HandshakeStep, Session};
use crate::{
    guard, status_from_crypto, DeviceKeys, FlHandle, FlStatus, SessionError, DEVICE_ID_MAX,
    FL_ERR_CRYPTO, FL_ERR_INVALID_ARG, FL_ERR_STORAGE, FL_OK,
};
// Нужен ради `decode` в разборе сердцебиения и буфера обмена. Без него вызов
// не находится: prost генерирует `decode` трейтом, а не inherent-методом.
use prost::Message as _;

/// Сторона рукопожатия в C ABI.
pub const FL_ROLE_INITIATOR: u32 = 0;
pub const FL_ROLE_RESPONDER: u32 = 1;

/// Что вернул шаг рукопожатия.
pub const FL_STEP_SEND: u32 = 0;
/// Рукопожатие ещё идёт, отправлять нечего: ждём ввода напарника.
pub const FL_STEP_WAIT: u32 = 1;
/// Рукопожатие закрыто, объект готов к обмену.
pub const FL_STEP_COMPLETE: u32 = 2;

/// Что делать с объектом сессии.
///
/// Обе ветки в отдельных ящиках: `snow::HandshakeState` и
/// `snow::TransportState` разного размера, и без ящиков перечисление
/// «раздувается» до размера большей ветки. Сессия на телефоне может быть
/// десятки, а разница в слоте стека тут была бы чистым перерасходом.
enum SessionObject {
    Handshaking(Box<Handshake>),
    Ready(Box<Session>),
}

/// Реестр сессий.
///
/// Один на процесс: сессий может быть много, но все они одного типа, поэтому
/// достаточно одной таблицы. `OnceLock` потому, что инициализация должна быть
/// ленивой — на этапе загрузки библиотеки глобальное состояние трогать нельзя.
static SESSIONS: std::sync::OnceLock<Registry<SessionObject>> = std::sync::OnceLock::new();

fn sessions() -> &'static Registry<SessionObject> {
    SESSIONS.get_or_init(Registry::new)
}

fn status_from_session(err: &SessionError) -> FlStatus {
    match err {
        SessionError::Crypto(e) => status_from_crypto(e),
        // Кадр больше допустимого — это ошибка вызывающего, а не канала.
        SessionError::TooLarge => FL_ERR_INVALID_ARG,
        SessionError::Malformed => FL_ERR_CRYPTO,
        SessionError::Transport
        | SessionError::HandshakeIncomplete
        | SessionError::UnexpectedMessage => FL_ERR_CRYPTO,
    }
}

/// Копирует выходные данные в буфер клиента.
///
/// Отдельная функция, потому что размер выхода известен только после
/// вычисления, а буфер клиента уже выделен: если что-то не влезает, это ошибка
/// вызывающего, и молча обрезать нельзя — клиент получил бы правдоподобный, но
/// неполный кадр.
unsafe fn copy_out(data: &[u8], out: *mut u8, out_len: *mut usize) -> FlStatus {
    if out_len.is_null() {
        return FL_ERR_INVALID_ARG;
    }
    let needed = data.len();

    if needed > 0 {
        if out.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        // Пишем длину только после проверки буфера: иначе клиент узнал бы
        // подходящий размер уже после того, как записал не туда.
        let cap = std::slice::from_raw_parts_mut(out, needed);
        cap.copy_from_slice(data);
    }

    *out_len = needed;
    FL_OK
}

/// Создаёт сессию по сохранённым ключам устройства.
///
/// Ключи читаются из файла целиком и живут внутри объекта: клиенту приватные
/// ключи не нужны и отдавать их нельзя.
///
/// `role` — `FL_ROLE_INITIATOR` или `FL_ROLE_RESPONDER`. Ошибка на этом шаге
/// необратима, поэтому роль проверяется сразу: перепутать её на живом
/// устройстве означает зависшее рукопожатие без понятной причины.
///
/// # Safety
/// `blob` указывает на `blob_len` байт файла ключей, `out_handle` — на
/// `u64`. Указатели валидны и не пересекаются.
#[no_mangle]
pub unsafe extern "C" fn fl_session_create(
    blob: *const u8,
    blob_len: usize,
    role: u32,
    out_handle: *mut u64,
) -> FlStatus {
    guard(|| {
        if blob.is_null() || out_handle.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if blob_len != crate::DEVICE_KEYS_BLOB_LEN {
            return FL_ERR_STORAGE;
        }

        let keys = match DeviceKeys::import(std::slice::from_raw_parts(blob, blob_len)) {
            Ok(k) => k,
            Err(e) => return status_from_crypto(&e),
        };

        let handshake = match role {
            FL_ROLE_INITIATOR => Handshake::initiate(&keys),
            FL_ROLE_RESPONDER => Handshake::accept(&keys),
            _ => return FL_ERR_INVALID_ARG,
        };

        let handshake = match handshake {
            Ok(h) => h,
            Err(e) => return status_from_session(&e),
        };

        let handle = sessions().insert(SessionObject::Handshaking(Box::new(handshake)));
        *out_handle = handle.0;
        FL_OK
    })
}

/// Один шаг рукопожатия.
///
/// Пустой `input` означает «говори сам». Непустой — «продолжай после
/// полученного». Что именно делать дальше, решает `snow`, а не флаг у нас:
/// своя копия этого правила рано или поздно разошлась бы с библиотекой.
///
/// `out_data` вместе с `out_len`: когда отправлять нечего, клиент узнаёт об
/// этом по нулевой длине и по `out_step`.
///
/// # Safety
/// `input` указывает на `input_len` байт (ноль и null допустимы при
/// `input_len == 0`), `out_data` — на буфер, который клиент выделил под
/// выход, `out_len` и `out_step` — на `usize`.
#[no_mangle]
pub unsafe extern "C" fn fl_session_step(
    handle: u64,
    input: *const u8,
    input_len: usize,
    out_step: *mut usize,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_step.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if input.is_null() && input_len > 0 {
            return FL_ERR_INVALID_ARG;
        }
        let h = FlHandle(handle);
        let bytes = if input_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(input, input_len)
        };

        // Буфер результата пишем сюда и переносим наружу после снятия замка:
        // держать замок реестра на время копирования в клиентскую память незачем.
        let mut produced: Vec<u8> = Vec::new();
        let mut step = FL_STEP_WAIT;

        let status = sessions().with(h, |obj| match obj {
            SessionObject::Ready(_) => FL_ERR_CRYPTO,
            SessionObject::Handshaking(hs) => match hs.step(bytes) {
                Ok(HandshakeStep::Send(data)) => {
                    produced = data;
                    step = FL_STEP_SEND;
                    FL_OK
                }
                Ok(HandshakeStep::WaitForInput) => {
                    step = FL_STEP_WAIT;
                    FL_OK
                }
                Ok(HandshakeStep::Complete) => {
                    step = FL_STEP_COMPLETE;
                    FL_OK
                }
                Err(e) => status_from_session(&e),
            },
        });

        match status {
            Some(FL_OK) => {}
            Some(code) => return code,
            // Хэндла нет: освобождён, устарел или выдуман.
            None => return FL_ERR_INVALID_ARG,
        }

        *out_step = step as usize;
        copy_out(&produced, out_data, out_len)
    })
}

/// Готова ли сессия к обмену.
///
/// Отдельный вызов, а не признак внутри `fl_session_step`: клиенту нужно знать
/// состояние и до того, как он что-то отправил, — например, чтобы показать в
/// интерфейсе «соединение установлено».
///
/// # Safety
/// `out_ready` указывает на `u32`.
#[no_mangle]
pub unsafe extern "C" fn fl_session_ready(handle: u64, out_ready: *mut u32) -> FlStatus {
    guard(|| {
        if out_ready.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        let h = FlHandle(handle);
        match sessions().with(h, |obj| match obj {
            SessionObject::Ready(_) => 1u32,
            SessionObject::Handshaking(hs) if hs.is_finished() => 1,
            SessionObject::Handshaking(_) => 0,
        }) {
            Some(ready) => {
                *out_ready = ready;
                FL_OK
            }
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Переводит объект из рукопожатия в обмен.
///
/// Не вызывается автоматически после `fl_session_step`: внутри `snow` состояние
/// рукопожатия переиспользуется под транспортный ключ, и держать оба вида
/// состояния сразу нельзя. Переход явный, чтобы ошибку «забыли перевести» можно
/// было отличить от «ещё не готов».
///
/// # Safety
/// `handle` — значение из `fl_session_create`. Указателей не принимает.
#[no_mangle]
pub unsafe extern "C" fn fl_session_finish(handle: u64) -> FlStatus {
    guard(|| {
        let h = FlHandle(handle);
        match sessions().with(h, |obj| match obj {
            SessionObject::Ready(_) => FL_ERR_CRYPTO,
            SessionObject::Handshaking(hs) => match hs.take_session() {
                Ok(s) => {
                    *obj = SessionObject::Ready(Box::new(s));
                    FL_OK
                }
                // Состояние не трогаем: если рукопожатие ещё не закрыто,
                // клиент позовёт снова после следующего шага.
                Err(e) => status_from_session(&e),
            },
        }) {
            Some(code) => code,
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Шифрует сообщение.
///
/// # Safety
/// `payload` указывает на `payload_len` байт, `out_frame` — на буфер под
/// кадр, `out_len` — на `usize`.
#[no_mangle]
pub unsafe extern "C" fn fl_session_seal(
    handle: u64,
    payload: *const u8,
    payload_len: usize,
    out_frame: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_len.is_null() || (payload.is_null() && payload_len > 0) {
            return FL_ERR_INVALID_ARG;
        }
        let h = FlHandle(handle);
        let bytes = if payload_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(payload, payload_len)
        };

        let mut frame: Vec<u8> = Vec::new();
        let status = sessions().with(h, |obj| match obj {
            SessionObject::Handshaking(_) => FL_ERR_CRYPTO,
            SessionObject::Ready(s) => match s.seal(bytes) {
                Ok(f) => {
                    frame = f;
                    FL_OK
                }
                Err(e) => status_from_session(&e),
            },
        });

        match status {
            Some(FL_OK) => copy_out(&frame, out_frame, out_len),
            Some(code) => code,
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Расшифровывает кадр.
///
/// # Safety
/// `frame` указывает на `frame_len` байт, `out_payload` — на буфер под
/// сообщение, `out_len` — на `usize`.
#[no_mangle]
pub unsafe extern "C" fn fl_session_open(
    handle: u64,
    frame: *const u8,
    frame_len: usize,
    out_payload: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_len.is_null() || (frame.is_null() && frame_len > 0) {
            return FL_ERR_INVALID_ARG;
        }
        let h = FlHandle(handle);
        let bytes = if frame_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(frame, frame_len)
        };

        let mut payload: Vec<u8> = Vec::new();
        let status = sessions().with(h, |obj| match obj {
            SessionObject::Handshaking(_) => FL_ERR_CRYPTO,
            SessionObject::Ready(s) => match s.open(bytes) {
                Ok(p) => {
                    payload = p;
                    FL_OK
                }
                Err(e) => status_from_session(&e),
            },
        });

        match status {
            Some(FL_OK) => copy_out(&payload, out_payload, out_len),
            Some(code) => code,
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Освобождает сессию.
///
/// Повторный вызов с тем же хэндл возвращает `FL_ERR_INVALID_ARG`, а не
/// паникует: клиент вправе освободить объект в обработчике ошибки, не зная, что
/// он уже освобождён.
///
/// # Safety
/// `handle` — значение из `fl_session_create`.
#[no_mangle]
pub unsafe extern "C" fn fl_session_free(handle: u64) -> FlStatus {
    guard(|| {
        if sessions().remove(FlHandle(handle)) {
            FL_OK
        } else {
            FL_ERR_INVALID_ARG
        }
    })
}

/// Собирает `Hello` устройства.
///
/// Отдельная функция, а не часть создания сессии: `Hello` ходит по открытому
/// каналу обнаружения и должен быть готов до того, как напарник подключился.
///
/// # Safety
/// `blob` — файл ключей, `display_name` — UTF-8 длиной `name_len`,
/// `out_data`/`out_len` — выход.
#[no_mangle]
pub unsafe extern "C" fn fl_hello_build(
    blob: *const u8,
    blob_len: usize,
    display_name: *const u8,
    name_len: usize,
    nonce: u64,
    unix_time_ms: u64,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if blob.is_null() || out_len.is_null() || (display_name.is_null() && name_len > 0) {
            return FL_ERR_INVALID_ARG;
        }
        if blob_len != crate::DEVICE_KEYS_BLOB_LEN {
            return FL_ERR_STORAGE;
        }

        let keys = match DeviceKeys::import(std::slice::from_raw_parts(blob, blob_len)) {
            Ok(k) => k,
            Err(e) => return status_from_crypto(&e),
        };

        let name = if name_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(display_name, name_len)
        };
        // Имя приходит от клиента и может оказаться не UTF-8. Мягкая замена
        // лучше отказа: устройство с именем «�» в списке лучше, чем устройство,
        // которое не показалось вовсе.
        let name = String::from_utf8_lossy(name).into_owned();

        let hello = match build_hello(
            &keys,
            // `Hello` по соединению не несёт ни адреса, ни описания: соединение
            // уже установлено, а объявление — единственное место, где платформа и
            // тип устройства нужны сразу. Рукопожатие эти поля не читает, и
            // заполнять их тут значило бы объявлять то, о чём напарник не
            // спросил.
            &crate::session::Профиль::new(&name, "", ""),
            &crate::session::default_capabilities(),
            nonce,
            unix_time_ms,
            // Порт тоже не объявляем: соединение уже установлено, и порт здесь
            // означал бы «данные не принимаю». Ноль честнее выдуманного.
            0,
        ) {
            Ok(h) => h,
            Err(e) => return status_from_session(&e),
        };

        copy_out(&hello, out_data, out_len)
    })
}

/// Разбирает и проверяет `Hello` полученного устройства.
///
/// Проверяется и подпись, и соответствие ключу из файла: устройство подписало
/// бы чужим ключом и прошло верификацию, если бы сверялась только подпись.
///
/// Идентификатор и имя отдаются в `out_id` и `out_name`, чтобы клиенту не надо
/// было разбирать Protobuf самому: второй разборщик протокола — это вторая
/// возможность ошибиться в нём.
///
/// # Safety
/// Все указатели валидны; `out_name` вместе с `out_name_len` описывают буфер,
/// который клиент выделил сам.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fl_hello_verify(
    expected_blob: *const u8,
    expected_blob_len: usize,
    body: *const u8,
    body_len: usize,
    out_id: *mut u8,
    out_id_len: *mut usize,
    out_name: *mut u8,
    out_name_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if expected_blob.is_null()
            || out_id.is_null()
            || out_id_len.is_null()
            || out_name_len.is_null()
            || (body.is_null() && body_len > 0)
        {
            return FL_ERR_INVALID_ARG;
        }
        if expected_blob_len != crate::DEVICE_KEYS_BLOB_LEN {
            return FL_ERR_STORAGE;
        }

        let expected = match DeviceKeys::import(std::slice::from_raw_parts(
            expected_blob,
            expected_blob_len,
        )) {
            Ok(k) => k,
            Err(e) => return status_from_crypto(&e),
        };

        let bytes = if body_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(body, body_len)
        };

        let hello = match parse_and_verify_hello(bytes, &expected) {
            Ok(h) => h,
            Err(e) => return status_from_session(&e),
        };

        let identity = match hello.identity.as_ref() {
            Some(i) => i,
            None => return FL_ERR_CRYPTO,
        };

        let id = identity.device_id.as_ref();
        let id_len = id.len().min(DEVICE_ID_MAX);
        let id_out = std::slice::from_raw_parts_mut(out_id, DEVICE_ID_MAX);
        id_out[..id_len].copy_from_slice(&id[..id_len]);
        *out_id_len = id_len;

        let name = identity.display_name.as_bytes();
        let n = name.len();
        if out_name.is_null() && n > 0 {
            return FL_ERR_INVALID_ARG;
        }
        if n > 0 {
            std::slice::from_raw_parts_mut(out_name, n).copy_from_slice(name);
        }
        *out_name_len = n;

        FL_OK
    })
}

// =============================================================================
// Первые данные: сердцебиение и буфер обмена
//
// Разбор сообщений живёт здесь, а не в клиенте. Вторая реализация protobuf на
// стороне Kotlin разъехалась бы с ядром при первом же изменении схемы, и тогда
// клиент показал бы человеку неверные значения содержимого — то есть сказал бы
// «это то, что у соседа в буфере», показав чужое. Разборщик должен быть один.
// =============================================================================

/// Предел содержимого буфера обмена.
///
/// Число взято не из расчёта, а из возможностей клиента: Android держит в
/// буфере обмена крупный текст, а протокол на первом канале не дробит сообщения
/// и не жмёт их. Отправка большего должна упасть с честной ошибкой, а не
/// разрезаться молча наполовину.
pub const CLIPBOARD_MAX: usize = 64 * 1024;

/// Предел размера блока файла — 256 КиБ по PROTOCOL.md.
pub const FILE_BLOCK_MAX: usize = 256 * 1024;

/// Буфер под кадр блока файла: заголовок, protobuf-поля и данные блока.
pub const FILE_BLOCK_FRAME_MAX: usize = FILE_BLOCK_MAX + 1024;

/// Буфер под кадр предложения файла.
pub const FILE_OFFER_FRAME_MAX: usize = 4096;

/// Буфер под кадр запроса недостающих смещений при докачке файла.
pub const FILE_REQUEST_FRAME_MAX: usize = 16 * 1024;

/// Длина хэша BLAKE3.
pub const HASH_LEN: usize = 32;

/// Буфер под кадр `Heartbeat`: заголовок плюс пять небольших полей.
///
/// Взят с запасом. Тело сообщения — это время, процент, флажок и два целых
/// числа: оно не растёт вместе с текстом. Если бы размер зависел от чего-то
/// внешнего, константа разъехалась бы с ним тихо, и клиент получил бы кадр,
/// обрезанный на середине.
pub const HEARTBEAT_FRAME_MAX: usize = 256;

/// Собирает кадр `Heartbeat` — первое, что уходит по установленному каналу.
///
/// Канал данных поднят, а по нему не уходило ни одного кадра. Сердцебиение
/// выбрано потому, что несёт настоящие значения: время, заряд, зарядку. Ни
/// одного выдуманного поля.
///
/// `measured_kbps` остаётся нулём: скорость не измеряется, и ноль здесь означает
/// «не измерено», а не «нулевая скорость». Заполнять его средним значением или
/// скоростью входящего соединения — значило бы показать человеку правдоподобное
/// число, измеренное не тем методом.
///
/// Тип канала проставляет ядро: клиент знает, каким он его запустил, но не
/// обязан знать, как этот канал называется в протоколе.
///
/// # Safety
/// `out_data` — буфер не менее [`HEARTBEAT_FRAME_MAX`] байт, `out_len` — на
/// `usize`. Указатели валидны.
#[no_mangle]
pub unsafe extern "C" fn fl_heartbeat_build(
    unix_time_ms: u64,
    battery_percent: u32,
    charging: bool,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_data.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        // Заряд выше сотни означает ошибку на стороне клиента, а не устройство
        // с двумя батареями. Обрезать молча нельзя: в журнале появилось бы
        // «заряд 100 %» у телефона, который на самом деле разряжен.
        if battery_percent > 100 {
            return FL_ERR_INVALID_ARG;
        }

        let тело = crate::pb::Heartbeat {
            unix_time_ms,
            battery_percent,
            charging,
            transport: crate::pb::TransportKind::TransportLan as i32,
            measured_kbps: 0,
        };

        let кадр =
            match crate::frame::encode(crate::pb::MessageKind::KindHeartbeat as i32, 0, &тело) {
                Ok(f) => f,
                Err(_) => return crate::FL_ERR_INTERNAL,
            };

        copy_out(&кадр, out_data, out_len)
    })
}

/// Разбирает тело `Heartbeat` в значения для показа.
///
/// Возвращает ровно то, что прислал напарник, без округлений и подстановок:
/// человек сравнивает эти числа с экраном своего телефона, и любая правда «для
/// ровности» была бы враньём.
///
/// # Safety
/// `body` — `body_len` байт расшифрованного тела; выходы валидны.
#[no_mangle]
pub unsafe extern "C" fn fl_heartbeat_read(
    body: *const u8,
    body_len: usize,
    out_unix_ms: *mut u64,
    out_battery: *mut u32,
    out_charging: *mut u32,
) -> FlStatus {
    guard(|| {
        if body.is_null()
            || out_unix_ms.is_null()
            || out_battery.is_null()
            || out_charging.is_null()
        {
            return FL_ERR_INVALID_ARG;
        }
        let bytes = if body_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(body, body_len)
        };

        let разобранное = match crate::pb::Heartbeat::decode(bytes) {
            Ok(h) => h,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        *out_unix_ms = разобранное.unix_time_ms;
        *out_battery = разобранное.battery_percent;
        // `bool` в C — это `u32`: отдельный тип не пережил бы границу ABI.
        *out_charging = u32::from(разобранное.charging);

        FL_OK
    })
}

/// Собирает кадр `ClipboardContent` из содержимого буфера.
///
/// Хэш содержимого считает ядро. Клиент его не передаёт намеренно: посчитанный
/// на стороне приложения хэш нечем сверить, а молчащее несовпадение хэшей было
/// бы хуже их отсутствия.
///
/// # Safety
/// `content` — `content_len` байт UTF-8, `mime_type` — `mime_len` байт;
/// `out_data` — буфер не менее [`CLIPBOARD_MAX`], `out_len` — на `usize`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fl_clipboard_build(
    content: *const u8,
    content_len: usize,
    mime_type: *const u8,
    mime_len: usize,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_data.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if content.is_null() || content_len > CLIPBOARD_MAX {
            return FL_ERR_INVALID_ARG;
        }
        if mime_type.is_null() && mime_len > 0 {
            return FL_ERR_INVALID_ARG;
        }

        let содержимое = std::slice::from_raw_parts(content, content_len);
        // Содержимое обязано быть UTF-8: клиент показывает его человеку, и
        // содержимое с невалидными байтами превратилось бы в «�» посреди
        // текста, который человек собирается прочитать.
        let текст = match std::str::from_utf8(содержимое) {
            Ok(t) => t,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let mime = if mime_len == 0 {
            ""
        } else {
            match std::str::from_utf8(std::slice::from_raw_parts(mime_type, mime_len)) {
                Ok(m) => m,
                Err(_) => return FL_ERR_INVALID_ARG,
            }
        };

        let тело = crate::pb::ClipboardContent {
            content_hash: prost::bytes::Bytes::copy_from_slice(&хэш_содержимого(
                содержимое,
            )),
            content: prost::bytes::Bytes::copy_from_slice(текст.as_bytes()),
            mime_type: mime.to_string(),
        };

        let кадр = match crate::frame::encode(
            crate::pb::MessageKind::KindClipboardContent as i32,
            0,
            &тело,
        ) {
            Ok(f) => f,
            Err(_) => return crate::FL_ERR_INTERNAL,
        };

        copy_out(&кадр, out_data, out_len)
    })
}

/// Разбирает тело `ClipboardContent` в содержимое и тип.
///
/// Хэш содержимого наружу не отдаётся: он нужен для отсечения повторов между
/// устройствами, а не для показа. Возвращать его значило бы заставлять клиент
/// сравнивать то, с чем он ничего не может сделать.
///
/// # Safety
/// `body` — `body_len` байт расшифрованного тела; `out_content` — не менее
/// `content_cap` байт, `out_mime` — не менее `mime_cap`. Нулевая ёмкость при
/// ненулевом выходном указателе означает «узнай размер»: длины заполняются,
/// содержимое не копируется.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fl_clipboard_read(
    body: *const u8,
    body_len: usize,
    out_content: *mut u8,
    content_cap: usize,
    out_content_len: *mut usize,
    out_mime: *mut u8,
    mime_cap: usize,
    out_mime_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if body.is_null() || out_content_len.is_null() || out_mime_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        let bytes = if body_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(body, body_len)
        };

        let разобранное = match crate::pb::ClipboardContent::decode(bytes) {
            Ok(c) => c,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        // Нулевая ёмкость означает «только узнай размер»: длина заполняется, а
        // содержимое не копируется. Так клиент узнаёт, сколько выделить, не
        // заводя отдельного вызова ради размера.
        //
        // Ненулевая, но недостаточная ёмкость — ошибка вызывающего, а не
        // напарника: отправить было можно. Отдельного кода «буфер мал» в
        // контракте нет и не заводится ради одного случая.
        let места_хватает = |нужно: usize, есть: usize| есть == 0 || нужно <= есть;
        if !места_хватает(разобранное.content.len(), content_cap)
            || !места_хватает(разобранное.mime_type.len(), mime_cap)
        {
            return FL_ERR_INVALID_ARG;
        }
        // Пустой выходной указатель законен ровно при нулевой ёмкости: копировать
        // нечего. При ненулевой ёмкости он означал бы запись по адресу, которого
        // нет, — это ошибка вызывающего, а не напарника.
        if (content_cap > 0 && out_content.is_null()) || (mime_cap > 0 && out_mime.is_null()) {
            return FL_ERR_INVALID_ARG;
        }

        if content_cap > 0 && !разобранное.content.is_empty() {
            std::slice::from_raw_parts_mut(out_content, разобранное.content.len())
                .copy_from_slice(&разобранное.content);
        }
        *out_content_len = разобранное.content.len();

        let mime = разобранное.mime_type.as_bytes();
        if mime_cap > 0 && !mime.is_empty() {
            std::slice::from_raw_parts_mut(out_mime, mime.len()).copy_from_slice(mime);
        }
        *out_mime_len = mime.len();

        FL_OK
    })
}

/// Хэш содержимого буфера для дедупликации.
fn хэш_содержимого(bytes: &[u8]) -> [u8; 32] {
    *blake3::hash(bytes).as_bytes()
}

/// Инкрементальный вычислитель хэша BLAKE3.
struct Blake3Hasher(blake3::Hasher);

static HASHERS: std::sync::OnceLock<Registry<Blake3Hasher>> = std::sync::OnceLock::new();

fn hashers() -> &'static Registry<Blake3Hasher> {
    HASHERS.get_or_init(Registry::new)
}

/// Вычисляет хэш BLAKE3 (32 байта) от блока данных.
///
/// # Safety
/// `data` должен указывать на блок памяти длиной не менее `data_len` байт.
/// `out_hash` должен указывать на буфер размером не менее 32 байт.
#[no_mangle]
pub unsafe extern "C" fn fl_blake3_hash(
    data: *const u8,
    data_len: usize,
    out_hash: *mut u8,
) -> FlStatus {
    guard(|| {
        if out_hash.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if data.is_null() && data_len > 0 {
            return FL_ERR_INVALID_ARG;
        }
        let срез = if data_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(data, data_len)
        };
        let h = blake3::hash(срез);
        std::slice::from_raw_parts_mut(out_hash, HASH_LEN).copy_from_slice(h.as_bytes());
        FL_OK
    })
}

/// Создаёт инкрементальный вычислитель хэша BLAKE3 для поточной обработки больших файлов.
///
/// # Safety
/// `out_handle` должен указывать на доступную для записи ячейку `u64`.
#[no_mangle]
pub unsafe extern "C" fn fl_blake3_hasher_create(out_handle: *mut u64) -> FlStatus {
    guard(|| {
        if out_handle.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        let h = hashers().insert(Blake3Hasher(blake3::Hasher::new()));
        *out_handle = h.0;
        FL_OK
    })
}

/// Добавляет порцию данных в вычислитель хэша BLAKE3.
///
/// # Safety
/// `data` должен указывать на блок памяти длиной не менее `data_len` байт.
#[no_mangle]
pub unsafe extern "C" fn fl_blake3_hasher_update(
    handle: u64,
    data: *const u8,
    data_len: usize,
) -> FlStatus {
    guard(|| {
        if data.is_null() && data_len > 0 {
            return FL_ERR_INVALID_ARG;
        }
        let срез = if data_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(data, data_len)
        };
        match hashers().with(FlHandle(handle), |hasher| {
            hasher.0.update(срез);
        }) {
            Some(()) => FL_OK,
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Финализирует хэш BLAKE3 и записывает 32 байта в `out_hash`.
///
/// # Safety
/// `out_hash` должен указывать на буфер размером не менее 32 байт.
#[no_mangle]
pub unsafe extern "C" fn fl_blake3_hasher_finalize(handle: u64, out_hash: *mut u8) -> FlStatus {
    guard(|| {
        if out_hash.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        match hashers().with(FlHandle(handle), |hasher| hasher.0.finalize()) {
            Some(res) => {
                std::slice::from_raw_parts_mut(out_hash, HASH_LEN).copy_from_slice(res.as_bytes());
                FL_OK
            }
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Освобождает вычислитель хэша BLAKE3.
///
/// # Safety
/// `handle` должен быть валидным хэндлом или 0.
#[no_mangle]
pub unsafe extern "C" fn fl_blake3_hasher_free(handle: u64) -> FlStatus {
    guard(|| {
        if hashers().remove(FlHandle(handle)) {
            FL_OK
        } else {
            FL_ERR_INVALID_ARG
        }
    })
}

/// Собирает кадр `FileOffer` (предложение передачи файла).
///
/// # Safety
/// `transfer_id`, `file_name`, `total_hash` должны указывать на валидные буферы соответствующих длин.
/// `out_data` и `out_len` должны удовлетворять протоколу `copy_out`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fl_file_offer_build(
    transfer_id: *const u8,
    transfer_id_len: usize,
    file_name: *const u8,
    file_name_len: usize,
    total_size: u64,
    total_hash: *const u8,
    total_hash_len: usize,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_data.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if transfer_id.is_null() || transfer_id_len == 0 || transfer_id_len > 64 {
            return FL_ERR_INVALID_ARG;
        }
        if file_name.is_null() || file_name_len == 0 || file_name_len > 1024 {
            return FL_ERR_INVALID_ARG;
        }
        if total_hash.is_null() || total_hash_len != HASH_LEN {
            return FL_ERR_INVALID_ARG;
        }

        let id_slice = std::slice::from_raw_parts(transfer_id, transfer_id_len);
        let name_bytes = std::slice::from_raw_parts(file_name, file_name_len);
        let name_str = match std::str::from_utf8(name_bytes) {
            Ok(s) => s,
            Err(_) => return FL_ERR_INVALID_ARG,
        };
        let hash_slice = std::slice::from_raw_parts(total_hash, total_hash_len);

        let тело = crate::pb::FileOffer {
            transfer_id: prost::bytes::Bytes::copy_from_slice(id_slice),
            file_name: name_str.to_string(),
            total_size,
            total_hash: prost::bytes::Bytes::copy_from_slice(hash_slice),
            blocks: Vec::new(),
            origin: None,
        };

        let кадр =
            match crate::frame::encode(crate::pb::MessageKind::KindFileOffer as i32, 0, &тело) {
                Ok(f) => f,
                Err(_) => return crate::FL_ERR_INTERNAL,
            };

        copy_out(&кадр, out_data, out_len)
    })
}

/// Разбирает тело `FileOffer` в поля предложения передачи.
///
/// # Safety
/// Все входные указатели должны указывать на валидную память соответствующих размеров.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fl_file_offer_read(
    body: *const u8,
    body_len: usize,
    out_transfer_id: *mut u8,
    transfer_id_cap: usize,
    out_transfer_id_len: *mut usize,
    out_file_name: *mut u8,
    file_name_cap: usize,
    out_file_name_len: *mut usize,
    out_total_size: *mut u64,
    out_total_hash: *mut u8,
    total_hash_cap: usize,
    out_total_hash_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if body.is_null()
            || out_transfer_id_len.is_null()
            || out_file_name_len.is_null()
            || out_total_size.is_null()
            || out_total_hash_len.is_null()
        {
            return FL_ERR_INVALID_ARG;
        }
        let bytes = if body_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(body, body_len)
        };

        let offer = match crate::pb::FileOffer::decode(bytes) {
            Ok(o) => o,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let места_хватает = |нужно: usize, есть: usize| есть == 0 || нужно <= есть;
        if !места_хватает(offer.transfer_id.len(), transfer_id_cap)
            || !места_хватает(offer.file_name.len(), file_name_cap)
            || !места_хватает(offer.total_hash.len(), total_hash_cap)
        {
            return FL_ERR_INVALID_ARG;
        }

        if (transfer_id_cap > 0 && out_transfer_id.is_null())
            || (file_name_cap > 0 && out_file_name.is_null())
            || (total_hash_cap > 0 && out_total_hash.is_null())
        {
            return FL_ERR_INVALID_ARG;
        }

        if transfer_id_cap > 0 && !offer.transfer_id.is_empty() {
            std::slice::from_raw_parts_mut(out_transfer_id, offer.transfer_id.len())
                .copy_from_slice(&offer.transfer_id);
        }
        *out_transfer_id_len = offer.transfer_id.len();

        let name_bytes = offer.file_name.as_bytes();
        if file_name_cap > 0 && !name_bytes.is_empty() {
            std::slice::from_raw_parts_mut(out_file_name, name_bytes.len())
                .copy_from_slice(name_bytes);
        }
        *out_file_name_len = name_bytes.len();

        *out_total_size = offer.total_size;

        if total_hash_cap > 0 && !offer.total_hash.is_empty() {
            std::slice::from_raw_parts_mut(out_total_hash, offer.total_hash.len())
                .copy_from_slice(&offer.total_hash);
        }
        *out_total_hash_len = offer.total_hash.len();

        FL_OK
    })
}

/// Собирает кадр `FileBlock` (блок данных файла с подсчётом BLAKE3).
///
/// # Safety
/// `transfer_id` и `data` должны указывать на валидные буферы соответствующих длин.
/// `out_data` и `out_len` должны удовлетворять протоколу `copy_out`.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fl_file_block_build(
    transfer_id: *const u8,
    transfer_id_len: usize,
    offset: u64,
    data: *const u8,
    data_len: usize,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_data.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if transfer_id.is_null() || transfer_id_len == 0 || transfer_id_len > 64 {
            return FL_ERR_INVALID_ARG;
        }
        if data_len > FILE_BLOCK_MAX {
            return FL_ERR_INVALID_ARG;
        }
        if data.is_null() && data_len > 0 {
            return FL_ERR_INVALID_ARG;
        }

        let id_slice = std::slice::from_raw_parts(transfer_id, transfer_id_len);
        let data_slice = if data_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(data, data_len)
        };

        let block_hash = blake3::hash(data_slice);

        let тело = crate::pb::FileBlock {
            offset,
            length: data_len as u32,
            data: prost::bytes::Bytes::copy_from_slice(data_slice),
            block_hash: prost::bytes::Bytes::copy_from_slice(block_hash.as_bytes()),
            transfer_id: prost::bytes::Bytes::copy_from_slice(id_slice),
        };

        let кадр =
            match crate::frame::encode(crate::pb::MessageKind::KindFileBlock as i32, 0, &тело) {
                Ok(f) => f,
                Err(_) => return crate::FL_ERR_INTERNAL,
            };

        copy_out(&кадр, out_data, out_len)
    })
}

/// Разбирает тело `FileBlock`, проверяет целостность по BLAKE3 хэшу блока.
///
/// # Safety
/// Все указатели должны указывать на валидную память соответствующих размеров.
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub unsafe extern "C" fn fl_file_block_read(
    body: *const u8,
    body_len: usize,
    out_transfer_id: *mut u8,
    transfer_id_cap: usize,
    out_transfer_id_len: *mut usize,
    out_offset: *mut u64,
    out_data: *mut u8,
    data_cap: usize,
    out_data_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if body.is_null()
            || out_transfer_id_len.is_null()
            || out_offset.is_null()
            || out_data_len.is_null()
        {
            return FL_ERR_INVALID_ARG;
        }
        let bytes = if body_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(body, body_len)
        };

        let block = match crate::pb::FileBlock::decode(bytes) {
            Ok(b) => b,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        // Проверка целостности блока: считаем BLAKE3 от принятых данных
        let calc_hash = blake3::hash(&block.data);
        if calc_hash.as_bytes() != &block.block_hash[..] {
            return FL_ERR_CRYPTO;
        }

        let места_хватает = |нужно: usize, есть: usize| есть == 0 || нужно <= есть;
        if !места_хватает(block.transfer_id.len(), transfer_id_cap)
            || !места_хватает(block.data.len(), data_cap)
        {
            return FL_ERR_INVALID_ARG;
        }

        if (transfer_id_cap > 0 && out_transfer_id.is_null())
            || (data_cap > 0 && out_data.is_null())
        {
            return FL_ERR_INVALID_ARG;
        }

        if transfer_id_cap > 0 && !block.transfer_id.is_empty() {
            std::slice::from_raw_parts_mut(out_transfer_id, block.transfer_id.len())
                .copy_from_slice(&block.transfer_id);
        }
        *out_transfer_id_len = block.transfer_id.len();

        *out_offset = block.offset;

        if data_cap > 0 && !block.data.is_empty() {
            std::slice::from_raw_parts_mut(out_data, block.data.len()).copy_from_slice(&block.data);
        }
        *out_data_len = block.data.len();

        FL_OK
    })
}

/// Собирает кадр `FileRequest` (запрос недостающих смещений при докачке).
///
/// # Safety
/// `transfer_id` и `missing_offsets` должны указывать на валидную память.
/// `out_data` и `out_len` должны удовлетворять протоколу `copy_out`.
#[no_mangle]
pub unsafe extern "C" fn fl_file_request_build(
    transfer_id: *const u8,
    transfer_id_len: usize,
    missing_offsets: *const u64,
    missing_offsets_len: usize,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_data.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if transfer_id.is_null() || transfer_id_len == 0 || transfer_id_len > 64 {
            return FL_ERR_INVALID_ARG;
        }
        if missing_offsets.is_null() && missing_offsets_len > 0 {
            return FL_ERR_INVALID_ARG;
        }

        let id_slice = std::slice::from_raw_parts(transfer_id, transfer_id_len);
        let offsets_vec = if missing_offsets_len == 0 {
            Vec::new()
        } else {
            std::slice::from_raw_parts(missing_offsets, missing_offsets_len).to_vec()
        };

        let тело = crate::pb::FileRequest {
            transfer_id: prost::bytes::Bytes::copy_from_slice(id_slice),
            missing_offsets: offsets_vec,
            partial_hash: prost::bytes::Bytes::new(),
        };

        let кадр =
            match crate::frame::encode(crate::pb::MessageKind::KindFileRequest as i32, 0, &тело)
            {
                Ok(f) => f,
                Err(_) => return crate::FL_ERR_INTERNAL,
            };

        copy_out(&кадр, out_data, out_len)
    })
}

/// Разбирает тело `FileRequest`.
///
/// # Safety
/// Все указатели должны указывать на валидную память соответствующих размеров.
#[no_mangle]
pub unsafe extern "C" fn fl_file_request_read(
    body: *const u8,
    body_len: usize,
    out_transfer_id: *mut u8,
    transfer_id_cap: usize,
    out_transfer_id_len: *mut usize,
    out_missing_offsets: *mut u64,
    missing_offsets_cap: usize,
    out_missing_offsets_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if body.is_null() || out_transfer_id_len.is_null() || out_missing_offsets_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        let bytes = if body_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(body, body_len)
        };

        let req = match crate::pb::FileRequest::decode(bytes) {
            Ok(r) => r,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let места_хватает = |нужно: usize, есть: usize| есть == 0 || нужно <= есть;
        if !места_хватает(req.transfer_id.len(), transfer_id_cap)
            || !места_хватает(req.missing_offsets.len(), missing_offsets_cap)
        {
            return FL_ERR_INVALID_ARG;
        }

        if (transfer_id_cap > 0 && out_transfer_id.is_null())
            || (missing_offsets_cap > 0 && out_missing_offsets.is_null())
        {
            return FL_ERR_INVALID_ARG;
        }

        if transfer_id_cap > 0 && !req.transfer_id.is_empty() {
            std::slice::from_raw_parts_mut(out_transfer_id, req.transfer_id.len())
                .copy_from_slice(&req.transfer_id);
        }
        *out_transfer_id_len = req.transfer_id.len();

        if missing_offsets_cap > 0 && !req.missing_offsets.is_empty() {
            std::slice::from_raw_parts_mut(out_missing_offsets, req.missing_offsets.len())
                .copy_from_slice(&req.missing_offsets);
        }
        *out_missing_offsets_len = req.missing_offsets.len();

        FL_OK
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pb::MessageKind;
    use crate::registry::HANDLE_INVALID;
    // Используется только в проверках, поэтому импорт здесь: иначе сборка без
    // тестов ругается на неиспользованный.
    use crate::FL_ERR_NOT_TRUSTED;

    /// Создаёт пару ключевых файлов: по одному на сторону рукопожатия.
    fn пара_устройств() -> (Vec<u8>, Vec<u8>) {
        let a = DeviceKeys::generate();
        let b = DeviceKeys::generate();
        (a.export(), b.export())
    }

    fn создать(blob: &[u8], role: u32) -> u64 {
        let mut h = 0u64;
        let code = unsafe { fl_session_create(blob.as_ptr(), blob.len(), role, &mut h) };
        assert_eq!(code, FL_OK, "сессия создана");
        h
    }

    fn шаг(handle: u64, input: &[u8]) -> (FlStatus, usize, Vec<u8>) {
        let mut step = 0usize;
        let mut buf = vec![0u8; 64 * 1024];
        let mut len = 0usize;
        let code = unsafe {
            fl_session_step(
                handle,
                if input.is_empty() {
                    std::ptr::null()
                } else {
                    input.as_ptr()
                },
                input.len(),
                &mut step,
                buf.as_mut_ptr(),
                &mut len,
            )
        };
        buf.truncate(len);
        (code, step, buf)
    }

    fn готов(handle: u64) -> bool {
        let mut ready = 0u32;
        let code = unsafe { fl_session_ready(handle, &mut ready) };
        assert_eq!(code, FL_OK);
        ready == 1
    }

    fn зашифровать(handle: u64, payload: &[u8]) -> (FlStatus, Vec<u8>) {
        let mut buf = vec![0u8; 64 * 1024];
        let mut len = 0usize;
        let code = unsafe {
            fl_session_seal(
                handle,
                payload.as_ptr(),
                payload.len(),
                buf.as_mut_ptr(),
                &mut len,
            )
        };
        buf.truncate(len);
        (code, buf)
    }

    fn расшифровать(handle: u64, frame: &[u8]) -> (FlStatus, Vec<u8>) {
        let mut buf = vec![0u8; 64 * 1024];
        let mut len = 0usize;
        let code = unsafe {
            fl_session_open(
                handle,
                frame.as_ptr(),
                frame.len(),
                buf.as_mut_ptr(),
                &mut len,
            )
        };
        buf.truncate(len);
        (code, buf)
    }

    /// Полный прогон рукопожатия через C ABI.
    ///
    /// Проверяется ровно то, что проверит клиент на телефоне: инициатор шлёт три
    /// сообщения, ответчик два, оба доходят до готовности, после чего сообщения
    /// идут в обе стороны.
    #[test]
    fn рукопожатие_через_abi_даёт_двусторонний_обмен() {
        let (a, b) = пара_устройств();

        let init = создать(&a, FL_ROLE_INITIATOR);
        let resp = создать(&b, FL_ROLE_RESPONDER);

        // 1. Инициатор говорит сам.
        let (code, step, msg1) = шаг(init, &[]);
        assert_eq!(code, FL_OK);
        assert_eq!(step, FL_STEP_SEND as usize);

        // 2. Ответчик читает и сразу отвечает.
        let (code, step, msg2) = шаг(resp, &msg1);
        assert_eq!(code, FL_OK);
        assert_eq!(step, FL_STEP_SEND as usize);

        // 3. Инициатор отвечает третьим сообщением.
        let (code, step, msg3) = шаг(init, &msg2);
        assert_eq!(code, FL_OK);
        assert_eq!(step, FL_STEP_SEND as usize);

        // 4. Ответчик читает хвост и закрывает рукопожатие.
        let (code, step, пусто) = шаг(resp, &msg3);
        assert_eq!(code, FL_OK);
        assert_eq!(step, FL_STEP_COMPLETE as usize);
        assert!(
            пусто.is_empty(),
            "закрывающее рукопожатие сообщение не отправляет"
        );

        assert!(готов(init) && готов(resp));

        // До перехода в обмен шифровать нельзя.
        let (code, _) = зашифровать(init, b"x");
        assert_eq!(code, FL_ERR_CRYPTO, "до fl_session_finish обмен недоступен");

        assert_eq!(unsafe { fl_session_finish(init) }, FL_OK);
        assert_eq!(unsafe { fl_session_finish(resp) }, FL_OK);

        // Второй переход отвергается: состояние ушло в транспорт.
        assert_eq!(unsafe { fl_session_finish(init) }, FL_ERR_CRYPTO);

        // Сообщение в обе стороны. Кириллица — частый случай буфера обмена.
        let привет = "привет".as_bytes();
        let (code, frame) = зашифровать(init, привет);
        assert_eq!(code, FL_OK);
        let (code, text) = расшифровать(resp, &frame);
        assert_eq!(code, FL_OK);
        assert_eq!(text, привет);

        let ответ = "и тебе".as_bytes();
        let (code, frame) = зашифровать(resp, ответ);
        assert_eq!(code, FL_OK);
        let (code, text) = расшифровать(init, &frame);
        assert_eq!(code, FL_OK);
        assert_eq!(text, ответ);
    }

    #[test]
    fn освобождённая_сессия_перестаёт_работать() {
        let (a, _) = пара_устройств();
        let h = создать(&a, FL_ROLE_INITIATOR);

        assert_eq!(unsafe { fl_session_free(h) }, FL_OK);
        assert_eq!(
            unsafe { fl_session_free(h) },
            FL_ERR_INVALID_ARG,
            "повторное освобождение не должно паниковать"
        );

        let mut step = 0usize;
        let mut len = 0usize;
        assert_eq!(
            unsafe {
                fl_session_step(
                    h,
                    std::ptr::null(),
                    0,
                    &mut step,
                    std::ptr::null_mut(),
                    &mut len,
                )
            },
            FL_ERR_INVALID_ARG
        );
    }

    #[test]
    fn нулевой_хэндл_отвергается() {
        let mut step = 0usize;
        let mut len = 0usize;
        let code = unsafe {
            fl_session_step(
                HANDLE_INVALID.0,
                std::ptr::null(),
                0,
                &mut step,
                std::ptr::null_mut(),
                &mut len,
            )
        };
        assert_eq!(code, FL_ERR_INVALID_ARG);
    }

    /// Плохая роль отвергается сразу: перепутанная роль на живом устройстве
    /// выглядит как рукопожатие, которое зависло навсегда.
    #[test]
    fn неизвестная_роль_отвергается() {
        let (a, _) = пара_устройств();
        let mut h = 0u64;
        let code = unsafe { fl_session_create(a.as_ptr(), a.len(), 99, &mut h) };
        assert_eq!(code, FL_ERR_INVALID_ARG);
    }

    #[test]
    fn файл_ключей_не_проходит_в_сессию() {
        let mut h = 0u64;
        let мусор = [0u8; 16];
        assert_eq!(
            unsafe {
                fl_session_create(мусор.as_ptr(), мусор.len(), FL_ROLE_INITIATOR, &mut h)
            },
            FL_ERR_STORAGE
        );
    }

    /// Две пары сессий на одном устройстве не мешают друг другу.
    ///
    /// Рукопожатия идут вперемешку, и обе пары в конце работают: состояние
    /// хранится в слотах реестра, а не «в последней сессии».
    ///
    /// Отдельно зафиксировано то, чего рукопожатие **не** определяет. В Noise XX
    /// первое сообщение несёт только эфемерный ключ, поэтому ответчик обязан
    /// принять его от любого, кто подключился: отказ был бы отказом от самого
    /// протокола. Личность связывается на третьем сообщении, а сверяется
    /// подписью `Hello` поверх уже установленного канала. Отсюда правило из
    /// заголовка ABI: подпись проверяется на **каждом** соединении, а не только
    /// при первом сопряжении.
    #[test]
    fn две_пары_сессий_независимы() {
        let (a1, b1) = пара_устройств();
        let (a2, _) = пара_устройств();

        let first_i = создать(&a1, FL_ROLE_INITIATOR);
        let first_r = создать(&b1, FL_ROLE_RESPONDER);
        let second_i = создать(&a2, FL_ROLE_INITIATOR);

        // Первые сообщения двух рукопожатий идут независимо.
        let (code, _, first_msg) = шаг(first_i, &[]);
        assert_eq!(code, FL_OK);
        let (code, _, second_msg) = шаг(second_i, &[]);
        assert_eq!(code, FL_OK);
        assert_ne!(
            first_msg, second_msg,
            "у разных сессий эфемерные ключи разные"
        );
        // Второе сообщение пока никому не адресовано: ответчик на него ещё не
        // подключён. Дальше важно лишь, что оно не испортило первую сессию.
        assert!(!second_msg.is_empty());

        // Первая пара доходит до конца.
        let (code, _, reply) = шаг(first_r, &first_msg);
        assert_eq!(code, FL_OK);
        let (code, _, tail) = шаг(first_i, &reply);
        assert_eq!(code, FL_OK);
        let (code, step, _) = шаг(first_r, &tail);
        assert_eq!(code, FL_OK);
        assert_eq!(step, FL_STEP_COMPLETE as usize);
        assert_eq!(unsafe { fl_session_finish(first_i) }, FL_OK);
        assert_eq!(unsafe { fl_session_finish(first_r) }, FL_OK);

        // И шифрование в ней работает после всех ошибок в чужой сессии.
        let текст = "сработало".as_bytes();
        let (code, frame) = зашифровать(first_i, текст);
        assert_eq!(code, FL_OK);
        let (code, прочитано) = расшифровать(first_r, &frame);
        assert_eq!(code, FL_OK);
        assert_eq!(прочитано, текст);

        // Вторая сессия при этом не тронута и всё ещё ждёт ввода.
        assert!(!готов(second_i));
    }

    /// После отправки своего сообщения инициатор ждёт, а не говорит снова.
    ///
    /// Проверяется явно, потому что «позвать шаг без ввода, когда ход не наш»
    /// — это рассинхронизация, а не «молча подождём».
    #[test]
    fn повторный_шаг_без_ввода_отвергается() {
        let (a, b) = пара_устройств();
        let init = создать(&a, FL_ROLE_INITIATOR);
        let resp = создать(&b, FL_ROLE_RESPONDER);

        let (_, _, msg1) = шаг(init, &[]);
        assert_eq!(шаг(init, &[]).0, FL_ERR_CRYPTO);

        // Состояние не пострадало: с ответчиком рукопожатие доходит до конца.
        let (code, _, _) = шаг(resp, &msg1);
        assert_eq!(code, FL_OK);
    }

    /// `Hello` собирается и проверяется через границу, а имя и идентификатор
    /// возвращаются готовыми: клиенту не нужен свой разборщик протокола.
    #[test]
    fn hello_через_abi_собирается_и_проверяется() {
        let (a, _) = пара_устройств();

        let имя = "Ноутбук Linux";
        let mut body = vec![0u8; 4096];
        let mut len = 0usize;
        let code = unsafe {
            fl_hello_build(
                a.as_ptr(),
                a.len(),
                имя.as_ptr(),
                имя.len(),
                7,
                1_700_000_000_000,
                body.as_mut_ptr(),
                &mut len,
            )
        };
        assert_eq!(code, FL_OK);
        body.truncate(len);

        let mut id = vec![0u8; DEVICE_ID_MAX];
        let mut id_len = 0usize;
        let mut name = vec![0u8; 256];
        let mut name_len = 0usize;
        let code = unsafe {
            fl_hello_verify(
                a.as_ptr(),
                a.len(),
                body.as_ptr(),
                body.len(),
                id.as_mut_ptr(),
                &mut id_len,
                name.as_mut_ptr(),
                &mut name_len,
            )
        };
        assert_eq!(code, FL_OK);
        assert_eq!(&name[..name_len], имя.as_bytes());
        assert_eq!(id_len, 26, "идентификатор — 26 символов base32");

        // Чужое устройство тот же Hello не принимает.
        let (_, чужая) = пара_устройств();
        let mut id2 = vec![0u8; DEVICE_ID_MAX];
        let mut id2_len = 0usize;
        let mut name2 = vec![0u8; 256];
        let mut name2_len = 0usize;
        let code = unsafe {
            fl_hello_verify(
                чужая.as_ptr(),
                чужая.len(),
                body.as_ptr(),
                body.len(),
                id2.as_mut_ptr(),
                &mut id2_len,
                name2.as_mut_ptr(),
                &mut name2_len,
            )
        };
        assert_eq!(code, FL_ERR_NOT_TRUSTED);
    }

    /// Мусор вместо Hello не должен проходить как «устройство с пустым именем».
    #[test]
    fn испорченный_hello_отвергается() {
        let (a, _) = пара_устройств();
        let мусор = [0xffu8; 64];

        let mut id = vec![0u8; DEVICE_ID_MAX];
        let mut id_len = 0usize;
        let mut name = vec![0u8; 256];
        let mut name_len = 0usize;
        let code = unsafe {
            fl_hello_verify(
                a.as_ptr(),
                a.len(),
                мусор.as_ptr(),
                мусор.len(),
                id.as_mut_ptr(),
                &mut id_len,
                name.as_mut_ptr(),
                &mut name_len,
            )
        };
        assert_ne!(code, FL_OK);
    }

    // =========================================================================
    // Сердцебиение
    // =========================================================================

    /// Собирает кадр сердцебиения и возвращает его вместе с телом.
    fn собрать_сердцебиение(
        ms: u64, заряд: u32, зарядка: bool
    ) -> Vec<u8> {
        let mut кадр = vec![0u8; HEARTBEAT_FRAME_MAX];
        let mut длина = 0usize;
        let код = unsafe {
            fl_heartbeat_build(
                ms,
                заряд,
                зарядка,
                кадр.as_mut_ptr(),
                &mut длина as *mut usize,
            )
        };
        assert_eq!(код, FL_OK, "сердцебиение собрано");

        let (заголовок, тело, _) = crate::frame::split(&кадр[..длина])
            .expect("кадр разобран тем же кодом, что и на приёме");
        assert_eq!(заголовок.kind, MessageKind::KindHeartbeat as i32);
        тело.to_vec()
    }

    /// Сердцебиение доходит в том виде, в каком было собрано.
    ///
    /// Человек сравнивает эти числа с экраном своего телефона. Если сборка и
    /// разбор разошлись бы — например, из-за другого формата времени или знака
    /// процента, — на экране соседа появилось бы правдоподобное, но не то
    /// значение, и человек решил бы, что у него разряжается чужой телефон.
    #[test]
    fn сердцебиение_доходит_таким_как_собрано() {
        let тело = собрать_сердцебиение(1_700_000_000_000, 77, true);

        let mut время = 0u64;
        let mut заряд = 0u32;
        let mut зарядка = 0u32;
        let код = unsafe {
            fl_heartbeat_read(
                тело.as_ptr(),
                тело.len(),
                &mut время as *mut u64,
                &mut заряд as *mut u32,
                &mut зарядка as *mut u32,
            )
        };

        assert_eq!(код, FL_OK);
        assert_eq!(время, 1_700_000_000_000);
        assert_eq!(заряд, 77);
        assert_eq!(зарядка, 1);
    }

    /// Заряд выше сотни — ошибка клиента, а не устройства с двумя батареями.
    ///
    /// Обрезать молча нельзя: в журнале появилось бы «заряд 100 %» у телефона,
    /// который на самом деле разряжен, и человек снял бы его с зарядки зря.
    #[test]
    fn заряд_выше_сотни_не_проходит() {
        let mut кадр = vec![0u8; 256];
        let mut длина = 0usize;
        let код = unsafe {
            fl_heartbeat_build(1, 101, false, кадр.as_mut_ptr(), &mut длина as *mut usize)
        };
        assert_eq!(код, FL_ERR_INVALID_ARG, "заряд 101 отвергнут");
        assert_eq!(длина, 0, "в кадр ничего не записано");
    }

    /// Разряженный телефон — это ноль, а не «нет данных».
    ///
    /// Ноль заряда и «заряд неизвестен» выглядят на экране одинаково, а значат
    /// разное: первый телефон можно заряжать прямо сейчас.
    #[test]
    fn нулевой_заряд_это_ноль_а_не_пустота() {
        let тело = собрать_сердцебиение(1, 0, false);

        let mut время = 0u64;
        let mut заряд = 99u32;
        let mut зарядка = 7u32;
        let код = unsafe {
            fl_heartbeat_read(
                тело.as_ptr(),
                тело.len(),
                &mut время as *mut u64,
                &mut заряд as *mut u32,
                &mut зарядка as *mut u32,
            )
        };

        assert_eq!(код, FL_OK);
        assert_eq!(заряд, 0, "ноль заряда доехал как ноль");
        assert_eq!(зарядка, 0, "зарядки нет");
    }

    // =========================================================================
    // Буфер обмена
    // =========================================================================

    /// Собирает кадр буфера и возвращает его вместе с телом.
    fn собрать_буфер(текст: &str, mime: &str) -> Vec<u8> {
        let mut кадр = vec![0u8; CLIPBOARD_MAX];
        let mut длина = 0usize;
        let код = unsafe {
            fl_clipboard_build(
                текст.as_ptr(),
                текст.len(),
                mime.as_ptr(),
                mime.len(),
                кадр.as_mut_ptr(),
                &mut длина as *mut usize,
            )
        };
        assert_eq!(код, FL_OK, "кадр буфера собран");

        let (заголовок, _, _) =
            crate::frame::split(&кадр[..длина]).expect("кадр разобран тем же кодом");
        assert_eq!(заголовок.kind, MessageKind::KindClipboardContent as i32);

        let (_, тело, _) = crate::frame::split(&кадр[..длина]).expect("тело выделено");
        тело.to_vec()
    }

    /// Текст буфера доходит в том виде, в каком был отправлен.
    ///
    /// Это первая настоящая передача содержимого между устройствами. Если сборка
    /// и разбор разошлись бы, человек получил бы в буфер обмена чужой текст,
    /// то есть вставил бы его куда-нибудь, ничего не проверив.
    #[test]
    fn текст_буфера_доходит_таким_как_отправлен() {
        let текст = "Пароль от Wi-Fi:домпрощание";
        let тело = собрать_буфер(текст, "text/plain");

        let mut содержимое = vec![0u8; CLIPBOARD_MAX];
        let mut mime = vec![0u8; 64];
        let mut длина = 0usize;
        let mut mime_len = 0usize;
        let код = unsafe {
            fl_clipboard_read(
                тело.as_ptr(),
                тело.len(),
                содержимое.as_mut_ptr(),
                содержимое.len(),
                &mut длина as *mut usize,
                mime.as_mut_ptr(),
                mime.len(),
                &mut mime_len as *mut usize,
            )
        };

        assert_eq!(код, FL_OK);
        assert_eq!(&содержимое[..длина], текст.as_bytes());
        assert_eq!(&mime[..mime_len], b"text/plain");
    }

    /// Клиент узнаёт размер содержимого до того, как выделил под него буфер.
    ///
    /// Нулевая ёмкость означает «только размер». Если бы проверка ёмкости
    /// отвергала такой вызов, клиент не смог бы узнать длину и был бы обязан
    /// угадать буфер — то есть отправка работала бы только на коротком тексте.
    #[test]
    fn размер_содержимого_узнаётся_без_буфера() {
        let текст = "очень длинный текст, который не влезет в guess";
        let тело = собрать_буфер(текст, "text/plain");

        let mut длина = 0usize;
        let mut mime_len = 0usize;
        let код = unsafe {
            fl_clipboard_read(
                тело.as_ptr(),
                тело.len(),
                std::ptr::null_mut(),
                0,
                &mut длина as *mut usize,
                std::ptr::null_mut(),
                0,
                &mut mime_len as *mut usize,
            )
        };

        assert_eq!(код, FL_OK, "размер отдан без копирования");
        assert_eq!(длина, текст.len());
        assert_eq!(mime_len, b"text/plain".len());
    }

    /// Буфер меньше содержимого — ошибка вызывающего, и она честная.
    ///
    /// Молча обрезать нельзя: человек получил бы половину пароля и счёл бы его
    /// неверным, а отправитель не узнал бы, что что-то потерялось.
    #[test]
    fn маленький_буфер_не_обрезает_молча() {
        let текст = "Пароль от Wi-Fi:домпрощание";
        let тело = собрать_буфер(текст, "text/plain");

        let mut содержимое = vec![0u8; 4];
        let mut mime = vec![0u8; 64];
        let mut длина = 0usize;
        let mut mime_len = 0usize;
        let код = unsafe {
            fl_clipboard_read(
                тело.as_ptr(),
                тело.len(),
                содержимое.as_mut_ptr(),
                содержимое.len(),
                &mut длина as *mut usize,
                mime.as_mut_ptr(),
                mime.len(),
                &mut mime_len as *mut usize,
            )
        };

        assert_eq!(код, FL_ERR_INVALID_ARG);
        assert_eq!(длина, 0, "половинный текст не показан");
    }

    /// Пустой буфер обмена — законное состояние, а не ошибка.
    ///
    /// Буфер может оказаться пустым у кого угодно, и в этом нет ничего
    /// необычного. Отвергать такое — значит отказать отправке с сообщением
    /// «содержимое нечитаемо», хотя с отправляемым всё в порядке.
    #[test]
    fn пустой_буфер_это_законное_состояние() {
        let тело = собрать_буфер("", "");

        let mut содержимое = vec![0u8; CLIPBOARD_MAX];
        let mut mime = vec![0u8; 64];
        let mut длина = 0usize;
        let mut mime_len = 0usize;
        let код = unsafe {
            fl_clipboard_read(
                тело.as_ptr(),
                тело.len(),
                содержимое.as_mut_ptr(),
                содержимое.len(),
                &mut длина as *mut usize,
                mime.as_mut_ptr(),
                mime.len(),
                &mut mime_len as *mut usize,
            )
        };

        assert_eq!(код, FL_OK);
        assert_eq!(длина, 0, "пустота доехала как пустота");
    }

    /// Не-UTF-8 в буфере не отправляется.
    ///
    /// Такой текст нечего показывать человеку: на экране он превратился бы в
    /// «�» посреди строки, которую человек собирается прочитать.
    #[test]
    fn не_utf8_не_отправляется() {
        let мусор = [0xffu8, 0xfe, 0x41];
        let mut кадр = vec![0u8; CLIPBOARD_MAX];
        let mut длина = 0usize;
        let код = unsafe {
            fl_clipboard_build(
                мусор.as_ptr(),
                мусор.len(),
                b"text/plain".as_ptr(),
                b"text/plain".len(),
                кадр.as_mut_ptr(),
                &mut длина as *mut usize,
            )
        };
        assert_eq!(код, FL_ERR_INVALID_ARG);
    }

    /// Больше предела не отправляется, а не режется наполовину.
    #[test]
    fn больше_предела_не_отправляется() {
        let длинный = vec![b'a'; CLIPBOARD_MAX + 1];
        let mut кадр = vec![0u8; CLIPBOARD_MAX];
        let mut длина = 0usize;
        let код = unsafe {
            fl_clipboard_build(
                длинный.as_ptr(),
                длинный.len(),
                std::ptr::null(),
                0,
                кадр.as_mut_ptr(),
                &mut длина as *mut usize,
            )
        };
        assert_eq!(код, FL_ERR_INVALID_ARG);
        assert_eq!(длина, 0);
    }

    /// Предложение файла собирается и разбирается с сохранением всех полей.
    #[test]
    fn file_offer_собирается_и_разбирается() {
        let tid = b"transfer_0123456";
        let name = "photo_2026.jpg";
        let size = 1048576u64;
        let hash = [0x42u8; 32];

        let mut frame = vec![0u8; FILE_OFFER_FRAME_MAX];
        let mut frame_len = 0usize;
        let code = unsafe {
            fl_file_offer_build(
                tid.as_ptr(),
                tid.len(),
                name.as_ptr(),
                name.len(),
                size,
                hash.as_ptr(),
                hash.len(),
                frame.as_mut_ptr(),
                &mut frame_len as *mut usize,
            )
        };
        assert_eq!(code, FL_OK);
        assert!(frame_len > 10);

        let (header, body, _) = crate::frame::split(&frame[..frame_len]).expect("кадр");
        assert_eq!(header.kind, crate::pb::MessageKind::KindFileOffer as i32);

        let mut out_tid = vec![0u8; 32];
        let mut out_tid_len = 0usize;
        let mut out_name = vec![0u8; 256];
        let mut out_name_len = 0usize;
        let mut out_size = 0u64;
        let mut out_hash = vec![0u8; 32];
        let mut out_hash_len = 0usize;

        let read_code = unsafe {
            fl_file_offer_read(
                body.as_ptr(),
                body.len(),
                out_tid.as_mut_ptr(),
                out_tid.len(),
                &mut out_tid_len as *mut usize,
                out_name.as_mut_ptr(),
                out_name.len(),
                &mut out_name_len as *mut usize,
                &mut out_size as *mut u64,
                out_hash.as_mut_ptr(),
                out_hash.len(),
                &mut out_hash_len as *mut usize,
            )
        };
        assert_eq!(read_code, FL_OK);
        assert_eq!(&out_tid[..out_tid_len], tid);
        assert_eq!(
            std::str::from_utf8(&out_name[..out_name_len]).unwrap(),
            name
        );
        assert_eq!(out_size, size);
        assert_eq!(&out_hash[..out_hash_len], &hash[..]);
    }

    /// Блок файла собирается, хэш считается автоматически, а при разборе сверяется.
    #[test]
    fn file_block_собирается_и_разбирается() {
        let tid = b"transfer_0123456";
        let offset = 65536u64;
        let data = vec![7u8; 4096];

        let mut frame = vec![0u8; FILE_BLOCK_FRAME_MAX];
        let mut frame_len = 0usize;
        let code = unsafe {
            fl_file_block_build(
                tid.as_ptr(),
                tid.len(),
                offset,
                data.as_ptr(),
                data.len(),
                frame.as_mut_ptr(),
                &mut frame_len as *mut usize,
            )
        };
        assert_eq!(code, FL_OK);

        let (header, body, _) = crate::frame::split(&frame[..frame_len]).expect("кадр");
        assert_eq!(header.kind, crate::pb::MessageKind::KindFileBlock as i32);

        let mut out_tid = vec![0u8; 32];
        let mut out_tid_len = 0usize;
        let mut out_offset = 0u64;
        let mut out_data = vec![0u8; 8192];
        let mut out_data_len = 0usize;

        let read_code = unsafe {
            fl_file_block_read(
                body.as_ptr(),
                body.len(),
                out_tid.as_mut_ptr(),
                out_tid.len(),
                &mut out_tid_len as *mut usize,
                &mut out_offset as *mut u64,
                out_data.as_mut_ptr(),
                out_data.len(),
                &mut out_data_len as *mut usize,
            )
        };
        assert_eq!(read_code, FL_OK);
        assert_eq!(&out_tid[..out_tid_len], tid);
        assert_eq!(out_offset, offset);
        assert_eq!(&out_data[..out_data_len], &data[..]);
    }

    /// Повреждённый блок файла отвергается проверкой хэша.
    #[test]
    fn file_block_повреждение_отвергается() {
        let tid = b"transfer_0123456";
        let offset = 0u64;
        let data = vec![9u8; 1024];

        let mut frame = vec![0u8; FILE_BLOCK_FRAME_MAX];
        let mut frame_len = 0usize;
        unsafe {
            fl_file_block_build(
                tid.as_ptr(),
                tid.len(),
                offset,
                data.as_ptr(),
                data.len(),
                frame.as_mut_ptr(),
                &mut frame_len as *mut usize,
            );
        }

        let (_hdr, body, _) = crate::frame::split(&frame[..frame_len]).expect("кадр");
        let mut corrupted_body = body.to_vec();
        // Портим байт в середине тела
        let mid = corrupted_body.len() / 2;
        corrupted_body[mid] ^= 0xFF;

        let mut out_tid = vec![0u8; 32];
        let mut out_tid_len = 0usize;
        let mut out_offset = 0u64;
        let mut out_data = vec![0u8; 2048];
        let mut out_data_len = 0usize;

        let read_code = unsafe {
            fl_file_block_read(
                corrupted_body.as_ptr(),
                corrupted_body.len(),
                out_tid.as_mut_ptr(),
                out_tid.len(),
                &mut out_tid_len as *mut usize,
                &mut out_offset as *mut u64,
                out_data.as_mut_ptr(),
                out_data.len(),
                &mut out_data_len as *mut usize,
            )
        };
        // Либо повреждён protobuf, либо хэш не сошёлся: ни в коем случае не FL_OK!
        assert_ne!(read_code, FL_OK);
    }

    /// Вычисление хэша BLAKE3 напрямую и через поточный Hasher дают один и тот же результат.
    #[test]
    fn blake3_хэш_и_поточный_hasher() {
        let data = b"Hello, Braidwork file transfer!";
        let mut direct_hash = [0u8; 32];
        let code = unsafe { fl_blake3_hash(data.as_ptr(), data.len(), direct_hash.as_mut_ptr()) };
        assert_eq!(code, FL_OK);

        let mut hasher_handle = 0u64;
        assert_eq!(
            unsafe { fl_blake3_hasher_create(&mut hasher_handle as *mut u64) },
            FL_OK
        );
        assert_eq!(
            unsafe { fl_blake3_hasher_update(hasher_handle, data[..10].as_ptr(), 10) },
            FL_OK
        );
        assert_eq!(
            unsafe { fl_blake3_hasher_update(hasher_handle, data[10..].as_ptr(), data.len() - 10) },
            FL_OK
        );

        let mut stream_hash = [0u8; 32];
        assert_eq!(
            unsafe { fl_blake3_hasher_finalize(hasher_handle, stream_hash.as_mut_ptr()) },
            FL_OK
        );
        assert_eq!(unsafe { fl_blake3_hasher_free(hasher_handle) }, FL_OK);

        assert_eq!(direct_hash, stream_hash);
        assert_eq!(direct_hash, *blake3::hash(data).as_bytes());
    }

    #[test]
    fn file_request_собирается_и_разбирается() {
        let tid = b"req_tid_999";
        let missing = [0u64, 32768, 65536];
        let mut frame = vec![0u8; FILE_REQUEST_FRAME_MAX];
        let mut frame_len = 0usize;

        let code = unsafe {
            fl_file_request_build(
                tid.as_ptr(),
                tid.len(),
                missing.as_ptr(),
                missing.len(),
                frame.as_mut_ptr(),
                &mut frame_len as *mut usize,
            )
        };
        assert_eq!(code, FL_OK);
        assert!(frame_len > 0);
        frame.truncate(frame_len);

        let (header, body, _) = crate::frame::split(&frame).unwrap();
        assert_eq!(header.kind, MessageKind::KindFileRequest as i32);

        let mut out_tid = vec![0u8; 64];
        let mut out_tid_len = 0usize;
        let mut out_missing = vec![0u64; 16];
        let mut out_missing_len = 0usize;

        let read_code = unsafe {
            fl_file_request_read(
                body.as_ptr(),
                body.len(),
                out_tid.as_mut_ptr(),
                out_tid.len(),
                &mut out_tid_len as *mut usize,
                out_missing.as_mut_ptr(),
                out_missing.len(),
                &mut out_missing_len as *mut usize,
            )
        };
        assert_eq!(read_code, FL_OK);
        assert_eq!(&out_tid[..out_tid_len], tid);
        assert_eq!(&out_missing[..out_missing_len], &missing[..]);
    }
}
