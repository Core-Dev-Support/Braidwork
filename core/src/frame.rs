//! Кадрирование: заголовок пакета и разбор входящего потока.
//!
//! Тела сообщений — protobuf, а вот заголовок написан руками: его нужно читать
//! до разбора тела, чтобы незнакомый тип отбросить дёшево. Оборачивать
//! заголовок в protobuf ради единообразия означало бы платить разбором там, где
//! нужны четыре байта подряд.
//!
//! Формат задан в `PROTOCOL.md`, раздел 4:
//!
//! ```text
//! magic   2 байта, 0x42 0x57 ("BW")
//! version 1 байт,  1
//! kind    1 байт,  MessageKind
//! flags   2 байта, FrameFlags
//! length  4 байта, длина protobuf-тела
//! ```
//!
//! Многобайтовые числа — от младшего к старшему, как varint в protobuf.
//! Android, Windows и Linux одинаково собирают такой порядок, а отдельная
//! договорённость про порядок байт потребовала бы её проверки на каждой
//! стороне.
//!
//! `magic` читается первым и не совпадает ни с одним известным значением: в
//! потоке по TCP это способ отличить наш трафик от чужого и от мусора в порту,
//! не тратя на разбор ничего.

use crate::pb::MessageKind;
use prost::Message;

/// Начало пакета: «BW».
pub const FRAME_MAGIC: [u8; 2] = [0x42, 0x57];

/// Версия заголовка.
pub const FRAME_VERSION: u8 = 1;

/// Длина заголовка: 2 + 1 + 1 + 2 + 4.
pub const HEADER_LEN: usize = 10;

/// Предел размера тела.
///
/// Ограничение не техническое. Тело приходит от кого угодно, кто занял порт, а
/// `length` в заголовке — это число, которому поверить нельзя: без предела
/// достаточно одного кадра с `length = 4 гигабайта`, чтобы клиент попытался
/// выделить память и упал. 4 МиБ с запасом перекрывает крупнейший блок файла
/// (256 КиБ) и фрагмент видео.
pub const MAX_BODY_LEN: usize = 4 * 1024 * 1024;

/// Разобранный заголовок кадра.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct FrameHeader {
    pub kind: i32,
    pub flags: u16,
    pub body_len: usize,
}

/// Ошибка разбора кадра.
#[derive(Debug, PartialEq, Eq, Clone, Copy)]
pub enum FrameError {
    /// Данных пока меньше заголовка.
    Short,
    /// Данных меньше, чем обещает заголовок.
    BodyShort,
    /// Начало пакета не наше: это чужой трафик или мусор в порту.
    BadMagic,
    /// Версия заголовка неизвестна.
    BadVersion,
    /// Тело больше предела.
    BodyTooLarge,
}

impl std::fmt::Display for FrameError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            FrameError::Short => write!(f, "данных меньше заголовка"),
            FrameError::BodyShort => write!(f, "тело кадра ещё не пришло"),
            FrameError::BadMagic => write!(f, "начало пакета не наше"),
            FrameError::BadVersion => write!(f, "версия заголовка неизвестна"),
            FrameError::BodyTooLarge => write!(f, "тело кадра больше предела"),
        }
    }
}

impl std::error::Error for FrameError {}

/// Собирает кадр целиком.
pub fn encode<M: Message>(kind: i32, flags: u16, body: &M) -> Result<Vec<u8>, FrameError> {
    let body = body.encode_to_vec();
    if body.len() > MAX_BODY_LEN {
        return Err(FrameError::BodyTooLarge);
    }

    let mut out = Vec::with_capacity(HEADER_LEN + body.len());
    out.extend_from_slice(&FRAME_MAGIC);
    out.push(FRAME_VERSION);
    out.push(kind as u8);
    out.extend_from_slice(&flags.to_le_bytes());
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend_from_slice(&body);
    Ok(out)
}

/// Собирает кадр с уже готовым телом.
///
/// Отдельная функция нужна там, где тело уже собрано руками или пришло из
/// другой подсистемы: повторное кодирование protobuf там, где тело уже верно,
/// только тратит время.
pub fn encode_raw(kind: i32, flags: u16, body: &[u8]) -> Result<Vec<u8>, FrameError> {
    if body.len() > MAX_BODY_LEN {
        return Err(FrameError::BodyTooLarge);
    }

    let mut out = Vec::with_capacity(HEADER_LEN + body.len());
    out.extend_from_slice(&FRAME_MAGIC);
    out.push(FRAME_VERSION);
    out.push(kind as u8);
    out.extend_from_slice(&flags.to_le_bytes());
    out.extend_from_slice(&(body.len() as u32).to_le_bytes());
    out.extend_from_slice(body);
    Ok(out)
}

/// Читает заголовок, не разбирая тело.
///
/// Требует хотя бы [HEADER_LEN] байт, иначе — [FrameError::Short].
pub fn read_header(buf: &[u8]) -> Result<FrameHeader, FrameError> {
    if buf.len() < HEADER_LEN {
        return Err(FrameError::Short);
    }
    if buf[0..2] != FRAME_MAGIC {
        return Err(FrameError::BadMagic);
    }
    if buf[2] != FRAME_VERSION {
        return Err(FrameError::BadVersion);
    }

    let flags = u16::from_le_bytes([buf[4], buf[5]]);
    let body_len = u32::from_le_bytes([buf[6], buf[7], buf[8], buf[9]]) as usize;

    if body_len > MAX_BODY_LEN {
        // Проверяется до того, как клиент поверит числу и под него выделит
        // память: тело может быть ещё не вовсе в буфере.
        return Err(FrameError::BodyTooLarge);
    }

    Ok(FrameHeader {
        kind: buf[3] as i32,
        flags,
        body_len,
    })
}

/// Отрезает от буфера кадр и тело целиком.
///
/// Возвращает заголовок, тело и сколько байт кадра занято. Остаток буфера
/// оставляется нетронутым — это начало следующего кадра.
pub fn split(buf: &[u8]) -> Result<(FrameHeader, &[u8], usize), FrameError> {
    let header = read_header(buf)?;
    let total = HEADER_LEN + header.body_len;
    if buf.len() < total {
        return Err(FrameError::BodyShort);
    }
    Ok((header, &buf[HEADER_LEN..total], total))
}

/// Накопитель входящих байтов.
///
/// Поток приходит кусками: один `read` может вернуть половину заголовка или
/// тело без хвоста. Накопитель прячет эту дробь от вызывающего — он зовёт
/// `push` с очередным куском и забирает `next` столько раз, сколько кадров
/// целиком пришло.
///
/// Данные не копируются дважды: внутри держится один буфер, и каждый
/// извлечённый кадр копируется ровно один раз, когда его забирают.
#[derive(Default)]
pub struct FrameReader {
    buf: Vec<u8>,
}

impl FrameReader {
    pub fn new() -> Self {
        FrameReader { buf: Vec::new() }
    }

    /// Сколько байт ждёт завершения кадра.
    pub fn buffered(&self) -> usize {
        self.buf.len()
    }

    /// Добавляет очередной кусок из сокета.
    pub fn push(&mut self, data: &[u8]) {
        self.buf.extend_from_slice(data);
    }

    /// Забирает очередной целый кадр.
    ///
    /// `None` означает «данных пока не хватает». Ошибка разбора — это не
    /// «пока нет», и после неё поток разорван: вызывающий обязан закрыть
    /// соединение, а не ждать следующего кадра.
    pub fn next_frame(&mut self) -> Result<Option<(FrameHeader, Vec<u8>)>, FrameError> {
        match split(&self.buf) {
            Ok((header, body, total)) => {
                let body = body.to_vec();
                self.buf.drain(..total);
                Ok(Some((header, body)))
            }
            Err(FrameError::Short) | Err(FrameError::BodyShort) => Ok(None),
            Err(e) => Err(e),
        }
    }

    /// Забирает все целые кадры, сколько есть.
    pub fn drain(&mut self) -> Result<Vec<(FrameHeader, Vec<u8>)>, FrameError> {
        let mut out = Vec::new();
        while let Some(frame) = self.next_frame()? {
            out.push(frame);
        }
        Ok(out)
    }

    /// Выбрасывает накопленное.
    ///
    /// Нужен при разрыве соединения: недособранный кадр от прежнего напарника
    /// не должен склеиться с первым кадром следующего.
    pub fn reset(&mut self) {
        self.buf.clear();
    }
}

/// Известен ли тип сообщения этой сборке.
///
/// Неизвестный тип не ошибка: устройство с более новым ядром может слать то,
/// чего эта сборка не знает. Вызывающий отбрасывает кадр и идёт дальше.
///
/// Нулевой тип отдельно не считается известным, хотя формально он есть в
/// перечислении. KIND_UNSPECIFIED означает «тип не задан», и ни один
/// обработчик на него не подписан: пропустить такой кадр в разборщик значит
/// уронить разборщик, полагаясь на то, что отправитель ошибся.
pub fn is_known_kind(kind: i32) -> bool {
    MessageKind::try_from(kind).is_ok() && kind != MessageKind::KindUnspecified as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pb::Heartbeat;

    #[test]
    fn кадр_собирается_и_разбирается_обратно() {
        let body = Heartbeat {
            unix_time_ms: 1_700_000_000_000,
            battery_percent: 77,
            charging: false,
            transport: crate::pb::TransportKind::TransportLan as i32,
            measured_kbps: 500,
        };

        let bytes = encode(MessageKind::KindHeartbeat as i32, 0, &body).expect("кадр собран");
        assert_eq!(bytes.len(), HEADER_LEN + body.encoded_len());

        let (header, raw, total) = split(&bytes).expect("кадр разобран");
        assert_eq!(header.kind, MessageKind::KindHeartbeat as i32);
        assert_eq!(header.flags, 0);
        assert_eq!(header.body_len, body.encoded_len());
        assert_eq!(total, bytes.len());

        let parsed = Heartbeat::decode(raw).expect("тело разобрано");
        assert_eq!(parsed.battery_percent, 77);
        assert_eq!(parsed.measured_kbps, 500);
    }

    #[test]
    fn начало_пакета_помещается_в_две_байты() {
        // «BW» читается в дампе глазами: 0x42 0x57.
        let bytes = encode_raw(MessageKind::KindBye as i32, 0, &[]).expect("кадр");
        assert_eq!(&bytes[..2], &[0x42, 0x57]);
        assert_eq!(&bytes[..2], &FRAME_MAGIC);
    }

    #[test]
    fn чужой_мусор_отвергается_по_началу() {
        let mut bytes = encode_raw(MessageKind::KindBye as i32, 0, &[]).expect("кадр");
        bytes[0] = 0x00;

        assert_eq!(read_header(&bytes).err(), Some(FrameError::BadMagic));
        assert_eq!(split(&bytes).err(), Some(FrameError::BadMagic));
    }

    #[test]
    fn незнакомая_версия_отвергается() {
        let mut bytes = encode_raw(MessageKind::KindBye as i32, 0, &[]).expect("кадр");
        bytes[2] = 99;
        assert_eq!(read_header(&bytes).err(), Some(FrameError::BadVersion));
    }

    #[test]
    fn недостаток_данных_это_не_ошибка() {
        let bytes = encode_raw(MessageKind::KindBye as i32, 0, &[1, 2, 3]).expect("кадр");

        assert_eq!(
            split(&bytes[..HEADER_LEN - 1]).err(),
            Some(FrameError::Short)
        );
        assert_eq!(
            split(&bytes[..HEADER_LEN]).err(),
            Some(FrameError::BodyShort)
        );

        // Как только тело дошло, разбор обязан удаться.
        let (header, raw, total) = split(&bytes).expect("кадр целый");
        assert_eq!(header.body_len, 3);
        assert_eq!(raw, &[1, 2, 3]);
        assert_eq!(total, bytes.len());
    }

    /// Длина из заголовка проверяется до того, как под неё что-то выделяется.
    ///
    /// Ровно этот сценарий и защищает предел: заголовок от чужого, который
    /// обещает гигабайт, не должен приводить к попытке выделить гигабайт.
    #[test]
    fn огромная_длина_отвергается_по_заголовку() {
        let mut bytes = encode_raw(MessageKind::KindBye as i32, 0, &[]).expect("кадр");
        // Просим 4 ГиБ при пределе 4 МиБ.
        bytes[6..10].copy_from_slice(&(u32::MAX).to_le_bytes());

        assert_eq!(read_header(&bytes).err(), Some(FrameError::BodyTooLarge));
        assert_eq!(split(&bytes).err(), Some(FrameError::BodyTooLarge));
    }

    #[test]
    fn тело_больше_предела_не_собирается() {
        let тело = vec![0u8; MAX_BODY_LEN + 1];
        assert_eq!(
            encode_raw(MessageKind::KindFileBlock as i32, 0, &тело).err(),
            Some(FrameError::BodyTooLarge)
        );
    }

    /// Поток приходит кусками, и границы кусков не совпадают с границами
    /// кадров. Это самая частая ошибка в разборе потока: наивный код читает
    /// «как пришло» и рассыпается.
    #[test]
    fn накопитель_собирает_кадры_из_произвольных_кусков() {
        let mut отправитель = Vec::new();
        for i in 0..5u64 {
            let body = Heartbeat {
                unix_time_ms: i,
                battery_percent: i as u32,
                charging: false,
                transport: 0,
                measured_kbps: 0,
            };
            отправитель.extend(encode(MessageKind::KindHeartbeat as i32, 0, &body).expect("кадр"));
        }

        // Режем по одному байту: худший случай для наивного разбора.
        let mut reader = FrameReader::new();
        let mut собрано = 0;
        for байт in &отправитель {
            reader.push(&[*байт]);
            while let Some((_, body)) = reader.next_frame().expect("кадр целый") {
                let parsed = Heartbeat::decode(body.as_slice()).expect("тело разобрано");
                assert_eq!(parsed.unix_time_ms, собрано as u64);
                собрано += 1;
            }
        }

        assert_eq!(собрано, 5);
        assert_eq!(reader.buffered(), 0, "в накопителе ничего не осталось");
    }

    #[test]
    fn накопитель_отдаёт_все_кадры_разом() {
        let mut поток = Vec::new();
        for _ in 0..3 {
            поток.extend(encode_raw(MessageKind::KindHeartbeat as i32, 0, &[7; 8]).expect("кадр"));
        }

        let mut reader = FrameReader::new();
        reader.push(&поток);
        let кадры = reader.drain().expect("все целые");
        assert_eq!(кадры.len(), 3);
        assert_eq!(reader.buffered(), 0);
    }

    /// Недособранный кадр прежнего напарника не должен склеиться с первым
    /// кадром следующего: иначе в начале нового соединения получится мусор.
    #[test]
    fn сброс_отбрасывает_недособранный_кадр() {
        let mut reader = FrameReader::new();
        let bytes = encode_raw(MessageKind::KindHeartbeat as i32, 0, &[1; 16]).expect("кадр");
        reader.push(&bytes[..HEADER_LEN + 4]);
        assert_eq!(reader.buffered(), HEADER_LEN + 4);

        reader.reset();
        assert_eq!(reader.buffered(), 0);
        assert!(reader.next_frame().expect("пусто").is_none());

        // После сброса приходит настоящий кадр — и он разбирается.
        reader.push(&bytes);
        assert!(reader.next_frame().expect("кадр целый").is_some());
    }

    #[test]
    fn незнакомый_тип_не_ошибка() {
        // Кадр с типом, которого нет в этой сборке, обязан быть прочитан и
        // отброшен вызывающим: устройство может оказаться новее нас.
        let bytes = encode_raw(200, 0, &[1, 2, 3]).expect("кадр собран");
        let (header, body, _) = split(&bytes).expect("кадр разобран");
        assert_eq!(header.kind, 200);
        assert_eq!(body, &[1, 2, 3]);
        assert!(!is_known_kind(200));

        assert!(is_known_kind(MessageKind::KindHeartbeat as i32));
        assert!(!is_known_kind(MessageKind::KindUnspecified as i32));
    }

    #[test]
    fn флаги_переживают_туда_и_обратно() {
        // Все флаги из схемы разом: 1 + 2 + 4 + 8 + 16 = 31.
        let bytes = encode_raw(MessageKind::KindFileBlock as i32, 31, &[]).expect("кадр");
        let (header, _, _) = split(&bytes).expect("разбор");
        assert_eq!(header.flags, 31);
    }
}
