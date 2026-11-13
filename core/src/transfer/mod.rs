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
