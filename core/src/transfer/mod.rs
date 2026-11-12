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
}
