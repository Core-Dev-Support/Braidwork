//! Модуль надёжной передачи файлов (File Transfer Engine).
//!
//! Обеспечивает независимую от ОС и клиентских сред передачу файлов:
//! - Потоковая нарезка на чанки с лимитом размера (Noise safe).
//! - Двухуровневая проверка целостности: хэш каждого блока + итоговый BLAKE3 всего файла.
//! - Подтверждение доставки (ACK) через битовую карту и выборочная докачка (`FileRequest`).
//! - Сохранение состояния (`ResumeState`) и безопасная работа с временными файлами.
//! - Исключение монолита: чёткое разделение ответственности на отдельные подмодули.

pub mod ack;
pub mod checksum;
pub mod chunk;
pub mod errors;
pub mod receiver;
pub mod resume;
pub mod sender;

pub use ack::{build_file_request, parse_missing_chunks, ChunkBitmap};
pub use checksum::{compute_block_hash, compute_file_hash, verify_block_hash, verify_file_hash};
pub use chunk::{ChunkInfo, ChunkPlan, DEFAULT_CHUNK_SIZE, MAX_CHUNK_SIZE, MIN_CHUNK_SIZE};
pub use errors::TransferError;
pub use receiver::{BlockWriteResult, FileReceiver};
pub use resume::{cleanup_files, ResumeState};
pub use sender::FileSender;

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn тест_взаимодействия_sender_и_receiver_из_конца_в_конец() {
        let dir = std::env::temp_dir().join("bw_transfer_e2e");
        let _ = std::fs::create_dir_all(&dir);

        let temp_path = dir.join(".bw_e2e.part");
        let dest_path = dir.join("e2e_received.dat");

        // 100 КБ тестовых данных (3 блока: 32K, 32K, 32K, 4K)
        let mut original_data = Vec::with_capacity(100 * 1024);
        for i in 0..(100 * 1024) {
            original_data.push((i % 251) as u8);
        }

        let mut sender_reader = Cursor::new(original_data.clone());
        let transfer_id = b"e2e_test_transfer_123".to_vec();

        let mut sender = FileSender::from_reader(
            transfer_id.clone(),
            "e2e_file.dat".to_string(),
            &mut sender_reader,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        assert_eq!(sender.plan().total_chunks(), 4);

        // 1. Инициация через Offer
        let offer = sender.build_offer();
        let mut receiver = FileReceiver::from_offer(
            &offer,
            temp_path.clone(),
            dest_path.clone(),
            None,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        // 2. Передача всех блоков
        let mut block_count = 0;
        while let Some(block) = sender.next_block(&mut sender_reader).unwrap() {
            block_count += 1;
            let res = receiver.write_block(&block).unwrap();
            if block_count == 4 {
                assert_eq!(res, BlockWriteResult::Complete);
            }
        }
        assert_eq!(block_count, 4);

        // 3. Завершение и сверка хэша
        let final_file = receiver.finish_and_verify().unwrap();
        assert_eq!(final_file, dest_path);

        let received_bytes = std::fs::read(&dest_path).unwrap();
        assert_eq!(received_bytes, original_data);

        // Уборка
        let _ = std::fs::remove_file(dest_path);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn тест_симуляции_потерь_и_докачки_через_file_request() {
        let dir = std::env::temp_dir().join("bw_transfer_loss");
        let _ = std::fs::create_dir_all(&dir);

        let temp_path = dir.join(".bw_loss.part");
        let dest_path = dir.join("loss_received.dat");

        // 128 КБ тестовых данных (4 блока по 32КБ)
        let data = vec![0x99u8; 128 * 1024];
        let mut reader = Cursor::new(data.clone());
        let transfer_id = b"loss_recovery_tid".to_vec();

        let mut sender = FileSender::from_reader(
            transfer_id.clone(),
            "loss_test.bin".to_string(),
            &mut reader,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let offer = sender.build_offer();
        let mut receiver = FileReceiver::from_offer(
            &offer,
            temp_path.clone(),
            dest_path.clone(),
            None,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        // Получаем все блоки у отправителя
        let b0 = sender.next_block(&mut reader).unwrap().unwrap();
        let _b1 = sender.next_block(&mut reader).unwrap().unwrap(); // БУДЕТ "ПОТЕРЯН" В СЕТИ
        let b2 = sender.next_block(&mut reader).unwrap().unwrap();
        let b3 = sender.next_block(&mut reader).unwrap().unwrap();

        // Доставляем блоки 0, 2, 3 (блок 1 потерялся)
        receiver.write_block(&b0).unwrap();
        receiver.write_block(&b2).unwrap();
        receiver.write_block(&b3).unwrap();

        assert!(!receiver.is_complete());
        assert_eq!(receiver.bitmap().received_count(), 3);
        assert_eq!(receiver.bitmap().missing_indices(), vec![1]);

        // Получатель формирует FileRequest
        let req = receiver.build_request();
        assert_eq!(req.missing_offsets, vec![32 * 1024]); // смещение потерянного блока 1

        // Отправитель принимает FileRequest и ставит недостающий блок в очередь
        let retransmit_count = sender.handle_request(&req).unwrap();
        assert_eq!(retransmit_count, 1);

        // Отправитель высылает повторный блок
        let recovered_b1 = sender.next_block(&mut reader).unwrap().unwrap();
        assert_eq!(recovered_b1.offset, 32 * 1024);

        // Получатель принимает восстановленный блок
        let res = receiver.write_block(&recovered_b1).unwrap();
        assert_eq!(res, BlockWriteResult::Complete);

        // Финализация успешна!
        let final_path = receiver.finish_and_verify().unwrap();
        assert_eq!(final_path, dest_path);

        let received_bytes = std::fs::read(&dest_path).unwrap();
        assert_eq!(received_bytes, data);

        let _ = std::fs::remove_file(dest_path);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn тест_возобновления_передачи_со_state_файла_и_пропуска_блоков_отправителем() {
        let dir = std::env::temp_dir().join(format!("bw_resume_flow_{}", std::process::id()));
        let _ = std::fs::create_dir_all(&dir);

        let temp_path = dir.join(".bw_resume.part");
        let dest_path = dir.join("resume_received.dat");
        let state_path = dir.join(".bw_resume.state");

        // 128 КБ (4 блока по 32КБ)
        let mut data = Vec::with_capacity(128 * 1024);
        for i in 0..(128 * 1024) {
            data.push(((i * 7) % 256) as u8);
        }
        let transfer_id = b"resume_flow_tid_99".to_vec();

        // ФАЗА 1: Передаём только блоки 0 и 1, затем имитируем обрыв связи
        {
            let mut reader = Cursor::new(data.clone());
            let mut sender1 = FileSender::from_reader(
                transfer_id.clone(),
                "test.bin".to_string(),
                &mut reader,
                DEFAULT_CHUNK_SIZE,
            )
            .unwrap();

            let offer = sender1.build_offer();
            let mut receiver1 = FileReceiver::from_offer(
                &offer,
                temp_path.clone(),
                dest_path.clone(),
                Some(state_path.clone()),
                DEFAULT_CHUNK_SIZE,
            )
            .unwrap();

            let b0 = sender1.next_block(&mut reader).unwrap().unwrap();
            let b1 = sender1.next_block(&mut reader).unwrap().unwrap();
            receiver1.write_block(&b0).unwrap();
            receiver1.write_block(&b1).unwrap();

            assert_eq!(receiver1.received_chunks_count(), 2);
            // Drop receiver1 и sender1 (симуляция обрыва соединения или перезапуска)
        }

        assert!(state_path.exists());
        assert!(temp_path.exists());

        // ФАЗА 2: Возобновление после перезапуска / повторное подключение
        {
            let mut reader2 = Cursor::new(data.clone());
            let mut sender2 = FileSender::from_reader(
                transfer_id.clone(),
                "test.bin".to_string(),
                &mut reader2,
                DEFAULT_CHUNK_SIZE,
            )
            .unwrap();

            let offer2 = sender2.build_offer();
            // Получатель создаётся с тем же offer и путями к .part и .state
            let mut receiver2 = FileReceiver::from_offer(
                &offer2,
                temp_path.clone(),
                dest_path.clone(),
                Some(state_path.clone()),
                DEFAULT_CHUNK_SIZE,
            )
            .unwrap();

            // Проверяем, что receiver2 автоматически восстановил 2 блока
            assert_eq!(receiver2.received_chunks_count(), 2);

            // Получатель формирует FileRequest со списком недостающих блоков (2 и 3)
            let req = receiver2.build_request();
            assert_eq!(req.missing_offsets.len(), 2);
            assert_eq!(req.missing_offsets, vec![64 * 1024, 96 * 1024]);

            // Отправитель принимает FileRequest в начале сессии:
            // пропускает блоки 0 и 1 и ставит в очередь ТОЛЬКО блоки 2 и 3!
            let missing_count = sender2.handle_request(&req).unwrap();
            assert_eq!(missing_count, 2);

            // Отправитель высылает блоки: проверяем, что высылаются ровно блоки 2 и 3, а не 0 и 1!
            let sent_b2 = sender2.next_block(&mut reader2).unwrap().unwrap();
            assert_eq!(sent_b2.offset, 64 * 1024);
            receiver2.write_block(&sent_b2).unwrap();

            let sent_b3 = sender2.next_block(&mut reader2).unwrap().unwrap();
            assert_eq!(sent_b3.offset, 96 * 1024);
            let res = receiver2.write_block(&sent_b3).unwrap();
            assert_eq!(res, BlockWriteResult::Complete);

            // Больше блоков отправителю передавать не нужно
            assert!(sender2.next_block(&mut reader2).unwrap().is_none());

            // Итоговая верификация BLAKE3 всего собранного файла
            let final_path = receiver2.finish_and_verify().unwrap();
            assert_eq!(final_path, dest_path);

            let disk_bytes = std::fs::read(&dest_path).unwrap();
            assert_eq!(disk_bytes, data);

            // Временные файлы удалены
            assert!(!state_path.exists());
            assert!(!temp_path.exists());
        }

        let _ = std::fs::remove_file(&dest_path);
        let _ = std::fs::remove_dir_all(&dir);
    }
}

#[cfg(all(test, feature = "transport-lan"))]
mod recovery_e2e_tests {
    use super::*;
    use crate::crypto::DeviceKeys;
    use crate::frame;
    use crate::pb::{FileBlock, FileOffer, FileRequest, MessageKind};
    use crate::session::Role;
    use crate::transfer::ack::{build_completion_ack, is_transfer_complete_ack};
    use crate::transfer::chunk::DEFAULT_CHUNK_SIZE;
    use crate::transport::lan::LanTransport;
    use crate::transport::{Channel, ConnState, TransportError, TransportEvent};
    use prost::Message;
    use std::collections::{HashMap, HashSet};
    use std::io::Cursor;
    use std::net::{Ipv4Addr, SocketAddrV4};
    use std::path::PathBuf;
    use std::time::{Duration, SystemTime, UNIX_EPOCH};

    fn make_temp_dir(label: &str) -> PathBuf {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let dir =
            std::env::temp_dir().join(format!("bw_e2e_{}_{}_{}", label, std::process::id(), nanos));
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    struct LanPair {
        a: LanTransport,
        b: LanTransport,
        conn_a: u64,
        conn_b: u64,
        static_b: [u8; 32],
    }

    impl LanPair {
        fn new() -> Self {
            let keys_a = DeviceKeys::generate();
            let keys_b = DeviceKeys::generate();
            let static_b = keys_b.static_public();
            let mut a = LanTransport::bind(keys_a, "PeerA".into(), "Linux".into(), 0).unwrap();
            let mut b = LanTransport::bind(keys_b, "PeerB".into(), "Android".into(), 0).unwrap();
            let (conn_a, conn_b) = Self::handshake_pair(&mut a, &mut b);
            Self {
                a,
                b,
                conn_a,
                conn_b,
                static_b,
            }
        }

        fn connect_unready(&mut self) -> u64 {
            let addr = SocketAddrV4::new(Ipv4Addr::LOCALHOST, self.b.port());
            self.a.connect(addr.into()).unwrap()
        }

        fn handshake_pair(a: &mut LanTransport, b: &mut LanTransport) -> (u64, u64) {
            let addr = SocketAddrV4::new(Ipv4Addr::LOCALHOST, b.port());
            let conn_a = a.connect(addr.into()).unwrap();
            let conn_b = Self::finish_handshake(a, b, conn_a);
            (conn_a, conn_b)
        }

        fn finish_handshake(a: &mut LanTransport, b: &mut LanTransport, conn_a: u64) -> u64 {
            let mut conn_b = 0u64;
            let mut connected_a = false;
            let mut connected_b = false;
            for _ in 0..100 {
                for ev in a.poll().unwrap() {
                    if let TransportEvent::Connected(c) = ev {
                        if c.handle == conn_a && c.role == Role::Initiator {
                            connected_a = true;
                        }
                    }
                }
                for ev in b.poll().unwrap() {
                    match ev {
                        TransportEvent::Incoming(c) => {
                            conn_b = c.handle;
                        }
                        TransportEvent::Connected(c) if c.role == Role::Responder => {
                            conn_b = c.handle;
                            connected_b = true;
                        }
                        _ => {}
                    }
                }
                if connected_a
                    && connected_b
                    && a.conn_state(conn_a) == ConnState::Ready
                    && b.conn_state(conn_b) == ConnState::Ready
                {
                    return conn_b;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            panic!("Noise XX рукопожатие не завершилось");
        }

        fn drop_and_reconnect(&mut self) -> (u64, u64) {
            let old_a = self.conn_a;
            let old_b = self.conn_b;
            let _ = self.b.close(old_b);
            let mut a_saw_closed = false;
            for _ in 0..80 {
                for ev in self.a.poll().unwrap() {
                    if let TransportEvent::Closed { conn, .. } = ev {
                        if conn == old_a {
                            a_saw_closed = true;
                        }
                    }
                }
                let _ = self.b.poll().unwrap();
                if a_saw_closed && self.a.conn_state(old_a) != ConnState::Ready {
                    break;
                }
                std::thread::sleep(Duration::from_millis(5));
            }
            assert!(a_saw_closed, "A должен увидеть Closed для старого conn");
            assert_ne!(self.a.conn_state(old_a), ConnState::Ready);

            let (new_a, new_b) = Self::handshake_pair(&mut self.a, &mut self.b);
            assert_ne!(new_a, old_a);
            assert_eq!(self.a.conn_remote_static(new_a), Some(self.static_b));
            self.conn_a = new_a;
            self.conn_b = new_b;
            (old_a, new_a)
        }
    }

    /// Идемпотентный обработчик входящей стороны (зеркалит логику Android-получателя:
    /// повторный FileOffer, повторный последний блок, защита от дублей и выдача ACK только после BLAKE3).
    struct ReceiverEndpoint {
        dir: PathBuf,
        active: HashMap<Vec<u8>, (FileReceiver, u64)>,
        completed: HashSet<Vec<u8>>,
        acks_sent: usize,
        last_ack_conn: Option<u64>,
    }

    impl ReceiverEndpoint {
        fn new(dir: PathBuf) -> Self {
            Self {
                dir,
                active: HashMap::new(),
                completed: HashSet::new(),
                acks_sent: 0,
                last_ack_conn: None,
            }
        }

        fn send_ack(&mut self, b: &mut LanTransport, conn: u64, tid: &[u8]) {
            let ack = build_completion_ack(tid);
            let frame_bytes = frame::encode(MessageKind::KindFileRequest as i32, 0, &ack).unwrap();
            b.send(conn, &frame_bytes).unwrap();
            self.acks_sent += 1;
            self.last_ack_conn = Some(conn);
        }

        fn handle_frame(
            &mut self,
            b: &mut LanTransport,
            conn: u64,
            kind: i32,
            body: &[u8],
            suppress_ack_once: &mut bool,
        ) {
            if kind == MessageKind::KindFileOffer as i32 {
                let offer = FileOffer::decode(body).unwrap();
                let tid = offer.transfer_id.to_vec();
                if self.completed.contains(&tid) {
                    self.send_ack(b, conn, &tid);
                    return;
                }
                if let Some((rec, active_conn)) = self.active.get_mut(&tid) {
                    *active_conn = conn;
                    let req = rec.build_request();
                    if is_transfer_complete_ack(&req) {
                        if rec.finish_and_verify().is_ok() {
                            self.completed.insert(tid.clone());
                            self.active.remove(&tid);
                            self.send_ack(b, conn, &tid);
                        }
                    } else {
                        let req_frame =
                            frame::encode(MessageKind::KindFileRequest as i32, 0, &req).unwrap();
                        b.send(conn, &req_frame).unwrap();
                    }
                    return;
                }
                let temp = self.dir.join("transfer.part");
                let state = self.dir.join("transfer.state");
                let dest = self.dir.join(&offer.file_name);
                let rec =
                    FileReceiver::from_offer(&offer, temp, dest, Some(state), DEFAULT_CHUNK_SIZE)
                        .unwrap();
                self.active.insert(tid, (rec, conn));
            } else if kind == MessageKind::KindFileBlock as i32 {
                let block = FileBlock::decode(body).unwrap();
                let tid = block.transfer_id.to_vec();
                if self.completed.contains(&tid) {
                    self.send_ack(b, conn, &tid);
                    return;
                }
                if let Some((rec, active_conn)) = self.active.get_mut(&tid) {
                    *active_conn = conn;
                    let outcome = rec.write_block(&block).unwrap();
                    if outcome == BlockWriteResult::Complete && rec.finish_and_verify().is_ok() {
                        self.completed.insert(tid.clone());
                        self.active.remove(&tid);
                        if *suppress_ack_once {
                            *suppress_ack_once = false;
                        } else {
                            self.send_ack(b, conn, &tid);
                        }
                    }
                }
            }
        }
    }

    fn drain_a_into_sender(a: &mut LanTransport, sender: &mut FileSender) -> bool {
        let mut got_ack = false;
        for ev in a.poll().unwrap() {
            if let TransportEvent::Frame { header, body, .. } = ev {
                if header.kind == MessageKind::KindFileRequest as i32 {
                    let req = FileRequest::decode(body.as_slice()).unwrap();
                    if is_transfer_complete_ack(&req) {
                        got_ack = true;
                    } else {
                        sender.handle_request(&req).unwrap();
                    }
                }
            }
        }
        got_ack
    }

    fn drain_b_into_receiver(
        b: &mut LanTransport,
        receiver: &mut ReceiverEndpoint,
        suppress_ack_once: &mut bool,
    ) {
        for ev in b.poll().unwrap() {
            if let TransportEvent::Frame {
                conn, header, body, ..
            } = ev
            {
                receiver.handle_frame(b, conn, header.kind, &body, suppress_ack_once);
            }
        }
    }

    #[test]
    fn e2e_обычная_передача_без_разрывов() {
        let dir = make_temp_dir("normal");
        let data = vec![0x5Au8; (DEFAULT_CHUNK_SIZE as usize) * 4 + 123];
        let mut cursor = Cursor::new(data.clone());
        let mut sender = FileSender::from_reader(
            b"tid_e2e_normal_1".to_vec(),
            "normal.bin".into(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let mut pair = LanPair::new();
        let mut receiver = ReceiverEndpoint::new(dir.clone());
        let mut suppress = false;

        let offer_frame =
            frame::encode(MessageKind::KindFileOffer as i32, 0, &sender.build_offer()).unwrap();
        pair.a.send(pair.conn_a, &offer_frame).unwrap();

        let mut acked = false;
        for _ in 0..100 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress);
            if drain_a_into_sender(&mut pair.a, &mut sender) {
                acked = true;
                break;
            }
            if let Some(block) = sender.next_block(&mut cursor).unwrap() {
                let f = frame::encode(MessageKind::KindFileBlock as i32, 0, &block).unwrap();
                pair.a.send(pair.conn_a, &f).unwrap();
            }
            std::thread::sleep(Duration::from_millis(5));
        }

        assert!(acked);
        assert_eq!(std::fs::read(dir.join("normal.bin")).unwrap(), data);
        assert!(!dir.join("transfer.part").exists());
        assert!(!dir.join("transfer.state").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn e2e_отправка_до_рукопожатия_отвергается_и_затем_проходит() {
        let dir = make_temp_dir("handshake_gate");
        let data = vec![0x11u8; 4096];
        let mut cursor = Cursor::new(data.clone());
        let sender = FileSender::from_reader(
            b"tid_e2e_hs_gate1".to_vec(),
            "hs.bin".into(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let mut pair = LanPair::new();
        let pending_conn = pair.connect_unready();
        assert_eq!(pair.a.conn_state(pending_conn), ConnState::Handshaking);

        let offer_frame =
            frame::encode(MessageKind::KindFileOffer as i32, 0, &sender.build_offer()).unwrap();
        let early = pair.a.send(pending_conn, &offer_frame);
        assert!(matches!(early, Err(TransportError::Unsupported(_))));

        // После завершения Noise XX кадр уходит успешно
        let _ = LanPair::finish_handshake(&mut pair.a, &mut pair.b, pending_conn);
        assert_eq!(pair.a.conn_state(pending_conn), ConnState::Ready);
        assert!(pair.a.send(pending_conn, &offer_frame).is_ok());
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn e2e_разрыв_переподключение_новый_conn_без_дублей_и_с_blake3() {
        let dir = make_temp_dir("reconnect");
        let data = (0..((DEFAULT_CHUNK_SIZE as usize) * 6 + 777))
            .map(|i| (i % 251) as u8)
            .collect::<Vec<_>>();
        let mut cursor = Cursor::new(data.clone());
        let mut sender = FileSender::from_reader(
            b"tid_e2e_reconn_1".to_vec(),
            "photo.bin".into(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let mut pair = LanPair::new();
        let mut receiver = ReceiverEndpoint::new(dir.clone());
        let mut suppress = false;

        let offer_frame =
            frame::encode(MessageKind::KindFileOffer as i32, 0, &sender.build_offer()).unwrap();
        pair.a.send(pair.conn_a, &offer_frame).unwrap();

        // Отправляем только 3 блока из 7 по первому соединению conn1
        for _ in 0..3 {
            let block = sender.next_block(&mut cursor).unwrap().unwrap();
            let f = frame::encode(MessageKind::KindFileBlock as i32, 0, &block).unwrap();
            pair.a.send(pair.conn_a, &f).unwrap();
        }
        for _ in 0..30 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress);
            if receiver
                .active
                .values()
                .next()
                .is_some_and(|(r, _)| r.received_chunks_count() == 3)
            {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(dir.join("transfer.part").exists());
        assert!(dir.join("transfer.state").exists());

        // Имитация обрыва и переподключения -> conn1 умирает, появляется conn2
        let (old_conn, new_conn) = pair.drop_and_reconnect();
        assert!(matches!(
            pair.a.send(old_conn, &offer_frame),
            Err(TransportError::NotRunning)
        ));

        // По conn2 отправляем idempotent FileOffer probe -> получатель сохраняет .part/.state и просит оставшиеся 4 блока
        pair.a.send(new_conn, &offer_frame).unwrap();
        let mut acked = false;
        for _ in 0..120 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress);
            if drain_a_into_sender(&mut pair.a, &mut sender) {
                acked = true;
                break;
            }
            if let Some(block) = sender.next_block(&mut cursor).unwrap() {
                let f = frame::encode(MessageKind::KindFileBlock as i32, 0, &block).unwrap();
                pair.a.send(new_conn, &f).unwrap();
            }
            std::thread::sleep(Duration::from_millis(5));
        }

        assert!(acked);
        assert_eq!(receiver.last_ack_conn, Some(pair.conn_b));
        assert_eq!(std::fs::read(dir.join("photo.bin")).unwrap(), data);
        assert!(!dir.join("transfer.part").exists());
        assert!(!dir.join("transfer.state").exists());
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn e2e_потеря_первого_ack_и_идемпотентный_ответ_на_probe() {
        let dir = make_temp_dir("lost_ack");
        let data = vec![0x7Cu8; (DEFAULT_CHUNK_SIZE as usize) * 2 + 55];
        let mut cursor = Cursor::new(data.clone());
        let mut sender = FileSender::from_reader(
            b"tid_e2e_lostack1".to_vec(),
            "ack.bin".into(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let mut pair = LanPair::new();
        let mut receiver = ReceiverEndpoint::new(dir.clone());
        // Первый Positive ACK намеренно теряется по пути!
        let mut suppress_ack = true;

        let offer_frame =
            frame::encode(MessageKind::KindFileOffer as i32, 0, &sender.build_offer()).unwrap();
        pair.a.send(pair.conn_a, &offer_frame).unwrap();
        while let Some(block) = sender.next_block(&mut cursor).unwrap() {
            let f = frame::encode(MessageKind::KindFileBlock as i32, 0, &block).unwrap();
            pair.a.send(pair.conn_a, &f).unwrap();
        }
        for _ in 0..50 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress_ack);
            assert!(!drain_a_into_sender(&mut pair.a, &mut sender));
            if receiver.completed.contains(b"tid_e2e_lostack1".as_slice()) {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(receiver.completed.contains(b"tid_e2e_lostack1".as_slice()));
        assert_eq!(receiver.acks_sent, 0);

        // Отправитель не дождался ACK и шлёт повторный FileOffer probe -> получатель идемпотентно отдаёт ACK
        pair.a.send(pair.conn_a, &offer_frame).unwrap();
        let mut acked = false;
        for _ in 0..50 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress_ack);
            if drain_a_into_sender(&mut pair.a, &mut sender) {
                acked = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(acked);
        assert_eq!(receiver.acks_sent, 1);
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1);
        assert_eq!(std::fs::read(dir.join("ack.bin")).unwrap(), data);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn e2e_разрыв_между_последним_блоком_и_ack_и_повторный_ack_на_новый_conn() {
        let dir = make_temp_dir("drop_before_ack");
        let data = vec![0x33u8; (DEFAULT_CHUNK_SIZE as usize) * 2];
        let mut cursor = Cursor::new(data.clone());
        let mut sender = FileSender::from_reader(
            b"tid_e2e_dropack1".to_vec(),
            "lastblock.bin".into(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let mut pair = LanPair::new();
        let mut receiver = ReceiverEndpoint::new(dir.clone());
        let mut suppress_ack = true; // обрыв срывает доставку первого ACK до A

        let offer_frame =
            frame::encode(MessageKind::KindFileOffer as i32, 0, &sender.build_offer()).unwrap();
        pair.a.send(pair.conn_a, &offer_frame).unwrap();
        while let Some(block) = sender.next_block(&mut cursor).unwrap() {
            let f = frame::encode(MessageKind::KindFileBlock as i32, 0, &block).unwrap();
            pair.a.send(pair.conn_a, &f).unwrap();
        }
        for _ in 0..50 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress_ack);
            if receiver.completed.contains(b"tid_e2e_dropack1".as_slice()) {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(receiver.completed.contains(b"tid_e2e_dropack1".as_slice()));

        // Связь рвётся между последним блоком и ACK, затем поднимается conn2
        let (old_conn, new_conn) = pair.drop_and_reconnect();
        assert_ne!(old_conn, new_conn);

        pair.a.send(new_conn, &offer_frame).unwrap();
        let mut acked = false;
        for _ in 0..50 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress_ack);
            if drain_a_into_sender(&mut pair.a, &mut sender) {
                acked = true;
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(acked);
        assert_eq!(receiver.last_ack_conn, Some(pair.conn_b));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn e2e_дубликат_последнего_блока_после_финализации_безопасен() {
        let dir = make_temp_dir("dup_last_block");
        let data = vec![0x99u8; (DEFAULT_CHUNK_SIZE as usize) + 512];
        let mut cursor = Cursor::new(data.clone());
        let mut sender = FileSender::from_reader(
            b"tid_e2e_duplast1".to_vec(),
            "dup.bin".into(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let mut pair = LanPair::new();
        let mut receiver = ReceiverEndpoint::new(dir.clone());
        let mut suppress = false;

        let offer_frame =
            frame::encode(MessageKind::KindFileOffer as i32, 0, &sender.build_offer()).unwrap();
        pair.a.send(pair.conn_a, &offer_frame).unwrap();

        let mut last_block_frame = Vec::new();
        while let Some(block) = sender.next_block(&mut cursor).unwrap() {
            last_block_frame = frame::encode(MessageKind::KindFileBlock as i32, 0, &block).unwrap();
            pair.a.send(pair.conn_a, &last_block_frame).unwrap();
        }
        for _ in 0..50 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress);
            if drain_a_into_sender(&mut pair.a, &mut sender) {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }
        assert!(receiver.completed.contains(b"tid_e2e_duplast1".as_slice()));
        let first_acks = receiver.acks_sent;

        // После финализации повторно приходит последний блок
        pair.a.send(pair.conn_a, &last_block_frame).unwrap();
        for _ in 0..30 {
            drain_b_into_receiver(&mut pair.b, &mut receiver, &mut suppress);
            if drain_a_into_sender(&mut pair.a, &mut sender) {
                break;
            }
            std::thread::sleep(Duration::from_millis(5));
        }

        assert_eq!(receiver.acks_sent, first_acks + 1);
        assert_eq!(std::fs::read_dir(&dir).unwrap().count(), 1);
        assert_eq!(std::fs::read(dir.join("dup.bin")).unwrap(), data);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn e2e_подмена_данных_ломает_blake3_и_запрещает_ack() {
        let dir = make_temp_dir("bad_blake3");
        let data = vec![0x42u8; DEFAULT_CHUNK_SIZE as usize];
        let mut cursor = Cursor::new(data.clone());
        let mut sender = FileSender::from_reader(
            b"tid_e2e_badhash1".to_vec(),
            "corrupt.bin".into(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let mut bad_offer = sender.build_offer();
        bad_offer.total_hash = prost::bytes::Bytes::from(vec![0u8; 32]); // чужой хэш файла

        let temp = dir.join("transfer.part");
        let state = dir.join("transfer.state");
        let dest = dir.join("corrupt.bin");
        let mut rec = FileReceiver::from_offer(
            &bad_offer,
            temp,
            dest.clone(),
            Some(state),
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();
        let block = sender.next_block(&mut cursor).unwrap().unwrap();
        assert_eq!(rec.write_block(&block).unwrap(), BlockWriteResult::Complete);
        assert!(
            rec.finish_and_verify().is_err(),
            "BLAKE3 несоответствие обязано отклонять финализацию и не выдавать Positive ACK"
        );
        assert!(!dest.exists());
        let _ = std::fs::remove_dir_all(&dir);
    }
}
