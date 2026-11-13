//! Получатель файлов (FileReceiver).
//!
//! Отвечает за надёжный приём, валидацию и сохранение файлов:
//! - Проверяет `FileOffer` (имя, размер, хэш BLAKE3).
//! - Записывает чанки во временный файл (`.bw_<tid>.part`) только после верификации хэша блока.
//! - Исключает повреждение дисковых данных: битый блок отвергается до записи.
//! - Поддерживает докачку и формирование `FileRequest` при потерях пакетов.
//! - После приёма всех блоков проводит финальную верификацию BLAKE3 всего файла и атомарно перемещает его в целевую папку.

use crate::pb::{FileBlock, FileOffer, FileRequest};
use crate::transfer::ack::{build_file_request, ChunkBitmap};
use crate::transfer::checksum::{verify_block_hash, verify_file_hash};
use crate::transfer::chunk::ChunkPlan;
use crate::transfer::errors::TransferError;
use crate::transfer::resume::{cleanup_files, ResumeState};
use std::collections::HashMap;
use std::fs::{File, OpenOptions};
use std::io::{Seek, SeekFrom, Write};
use std::path::PathBuf;

/// Результат записи одного блока.
#[derive(Debug, PartialEq, Eq)]
pub enum BlockWriteResult {
    /// Блок успешно проверен и записан.
    Accepted {
        chunk_index: usize,
        remaining: usize,
    },
    /// Блок уже был получен ранее (дубликат).
    Duplicate { chunk_index: usize },
    /// Все блоки приняты, файл готов к итоговой верификации.
    Complete,
}

/// Получатель файлов.
pub struct FileReceiver {
    transfer_id: Vec<u8>,
    file_name: String,
    total_size: u64,
    total_hash: [u8; 32],
    chunk_size: u32,
    plan: ChunkPlan,
    bitmap: ChunkBitmap,
    chunk_hashes: HashMap<usize, [u8; 32]>,
    temp_path: PathBuf,
    dest_path: PathBuf,
    state_path: Option<PathBuf>,
    file: Option<File>,
    cancelled: bool,
}

impl FileReceiver {
    /// Создаёт получателя на основе полученного `FileOffer`.
    pub fn from_offer(
        offer: &FileOffer,
        temp_path: PathBuf,
        dest_path: PathBuf,
        state_path: Option<PathBuf>,
        chunk_size: u32,
    ) -> Result<Self, TransferError> {
        if offer.transfer_id.is_empty() {
            return Err(TransferError::MalformedOffer(
                "пустой transfer_id".to_string(),
            ));
        }
        if offer.file_name.is_empty() {
            return Err(TransferError::MalformedOffer(
                "пустое имя файла".to_string(),
            ));
        }
        if offer.total_hash.len() != 32 {
            return Err(TransferError::MalformedOffer(format!(
                "неверная длина общего хэша {} вместо 32",
                offer.total_hash.len()
            )));
        }

        let mut total_hash = [0u8; 32];
        total_hash.copy_from_slice(&offer.total_hash);

        // Если файл состояния и .part файл уже существуют — автоматически возобновляем приём
        // со сверкой хэшей и границ
        if let Some(ref sp) = state_path {
            if sp.exists() && temp_path.exists() {
                if let Ok(state) = ResumeState::load_from_file(sp) {
                    if state.transfer_id == offer.transfer_id
                        && state.total_size == offer.total_size
                        && state.total_hash == total_hash
                    {
                        return Self::resume_from_state(state, temp_path, dest_path, state_path);
                    }
                }
            }
        }

        let plan = ChunkPlan::new(offer.total_size, chunk_size)?;
        let bitmap = ChunkBitmap::new(plan.total_chunks());
        let chunk_hashes = HashMap::new();

        // Открываем или создаём временный файл
        if let Some(parent) = temp_path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let file = OpenOptions::new()
            .create(true)
            .truncate(false)
            .read(true)
            .write(true)
            .open(&temp_path)?;

        let receiver = Self {
            transfer_id: offer.transfer_id.to_vec(),
            file_name: offer.file_name.clone(),
            total_size: offer.total_size,
            total_hash,
            chunk_size,
            plan,
            bitmap,
            chunk_hashes,
            temp_path,
            dest_path,
            state_path,
            file: Some(file),
            cancelled: false,
        };

        receiver.save_state_if_needed()?;
        Ok(receiver)
    }

    /// Возобновляет приём на основе сохранённого состояния `ResumeState`.
    pub fn resume_from_state(
        state: ResumeState,
        temp_path: PathBuf,
        dest_path: PathBuf,
        state_path: Option<PathBuf>,
    ) -> Result<Self, TransferError> {
        state.validate_part_file(&temp_path)?;

        let mut file = OpenOptions::new().read(true).write(true).open(&temp_path)?;

        let mut state = state;
        // Криптографическая проверка каждого ранее сохранённого блока на диске
        state.reconcile_and_verify_part_file(&mut file)?;

        let plan = ChunkPlan::new(state.total_size, state.chunk_size)?;

        Ok(Self {
            transfer_id: state.transfer_id,
            file_name: state.file_name,
            total_size: state.total_size,
            total_hash: state.total_hash,
            chunk_size: state.chunk_size,
            plan,
            bitmap: state.bitmap,
            chunk_hashes: state.chunk_hashes,
            temp_path,
            dest_path,
            state_path,
            file: Some(file),
            cancelled: false,
        })
    }

    #[inline]
    pub fn transfer_id(&self) -> &[u8] {
        &self.transfer_id
    }

    #[inline]
    pub fn file_name(&self) -> &str {
        &self.file_name
    }

    #[inline]
    pub fn total_size(&self) -> u64 {
        self.total_size
    }

    #[inline]
    pub fn total_hash(&self) -> &[u8; 32] {
        &self.total_hash
    }

    #[inline]
    pub fn bitmap(&self) -> &ChunkBitmap {
        &self.bitmap
    }

    #[inline]
    pub fn plan(&self) -> &ChunkPlan {
        &self.plan
    }

    #[inline]
    pub fn is_complete(&self) -> bool {
        self.bitmap.is_complete()
    }

    /// Количество уже полученных блоков (используется для отображения и подтверждения Resume).
    #[inline]
    pub fn received_chunks_count(&self) -> usize {
        self.bitmap.received_count()
    }

    /// Записывает блок данных в файл после проверки контрольной суммы.
    pub fn write_block(&mut self, block: &FileBlock) -> Result<BlockWriteResult, TransferError> {
        if self.cancelled {
            return Err(TransferError::Cancelled);
        }

        if block.transfer_id != self.transfer_id.as_slice() {
            return Err(TransferError::TransferMismatch {
                expected: self.transfer_id.clone(),
                actual: block.transfer_id.to_vec(),
            });
        }

        // Проверяем границы
        self.plan.validate_bounds(block.offset, block.data.len())?;

        let chunk = self.plan.chunk_for_offset(block.offset).ok_or_else(|| {
            TransferError::MalformedBlock(format!("не найден чанк для смещения {}", block.offset))
        })?;

        // Строгая проверка: смещение и длина блока обязаны точно совпадать с ожидаемым чанком
        if block.offset != chunk.offset || block.data.len() != chunk.length as usize {
            return Err(TransferError::MalformedBlock(format!(
                "несовпадение границ чанка {}: ожидалось offset={}, len={}, получено offset={}, len={}",
                chunk.index, chunk.offset, chunk.length, block.offset, block.data.len()
            )));
        }

        // Проверяем дубликат
        if self.bitmap.is_received(chunk.index) {
            return Ok(BlockWriteResult::Duplicate {
                chunk_index: chunk.index,
            });
        }

        // Проверяем контрольную сумму чанка ДО записи на диск!
        verify_block_hash(&block.data, block.offset, &block.block_hash)?;

        // Записываем в файл по точному смещению
        let file = self
            .file
            .as_mut()
            .ok_or_else(|| TransferError::InvalidState("файл уже закрыт".to_string()))?;

        file.seek(SeekFrom::Start(block.offset))?;
        file.write_all(&block.data)?;

        // Отмечаем в битовой карте и сохраняем проверенный хэш блока
        self.bitmap.mark_received(chunk.index);
        let mut block_hash_arr = [0u8; 32];
        block_hash_arr.copy_from_slice(&block.block_hash);
        self.chunk_hashes.insert(chunk.index, block_hash_arr);

        self.save_state_if_needed()?;

        if self.bitmap.is_complete() {
            Ok(BlockWriteResult::Complete)
        } else {
            let remaining = self
                .bitmap
                .total_chunks()
                .saturating_sub(self.bitmap.received_count());
            Ok(BlockWriteResult::Accepted {
                chunk_index: chunk.index,
                remaining,
            })
        }
    }

    /// Формирует запрос недостающих блоков `FileRequest` для отправителя.
    pub fn build_request(&self) -> FileRequest {
        build_file_request(&self.transfer_id, &self.plan, &self.bitmap, None)
    }

    /// Финализирует передачу: закрывает временный файл, проверяет полный BLAKE3 хэш
    /// и атомарно перемещает файл в целевую директорию `dest_path`.
    pub fn finish_and_verify(&mut self) -> Result<PathBuf, TransferError> {
        if !self.bitmap.is_complete() {
            return Err(TransferError::Incomplete {
                received_chunks: self.bitmap.received_count(),
                total_chunks: self.bitmap.total_chunks(),
            });
        }

        // Закрываем дескриптор записи
        if let Some(mut file) = self.file.take() {
            file.flush()?;
        }

        // Проверяем итоговый хэш BLAKE3 всего файла
        {
            let read_file = File::open(&self.temp_path)?;
            verify_file_hash(read_file, &self.total_hash)?;
        }

        // Создаём родительский каталог назначения, если нужно
        if let Some(parent) = self.dest_path.parent() {
            std::fs::create_dir_all(parent)?;
        }

        // Атомарное перемещение; при ошибке EXDEV (разные ФС) копируем и удаляем
        if std::fs::rename(&self.temp_path, &self.dest_path).is_err() {
            std::fs::copy(&self.temp_path, &self.dest_path)?;
            let _ = std::fs::remove_file(&self.temp_path);
        }

        // Удаляем файл состояния, если он был
        if let Some(sp) = &self.state_path {
            if sp.exists() {
                let _ = std::fs::remove_file(sp);
            }
        }

        Ok(self.dest_path.clone())
    }

    /// Отменяет передачу и удаляет временные файлы.
    pub fn cancel(&mut self) {
        self.cancelled = true;
        self.file = None;
        cleanup_files(&self.temp_path, self.state_path.as_deref());
    }

    /// Прогресс передачи: (принято байт, всего байт, доля от 0.0 до 1.0).
    pub fn progress(&self) -> (u64, u64, f32) {
        let total = self.total_size;
        let received_chunks = self.bitmap.received_count();
        let total_chunks = self.bitmap.total_chunks();

        let fraction = if total_chunks == 0 {
            1.0
        } else {
            (received_chunks as f32 / total_chunks as f32).clamp(0.0, 1.0)
        };
        let bytes = (fraction * total as f32) as u64;
        (bytes, total, fraction)
    }

    fn save_state_if_needed(&self) -> Result<(), TransferError> {
        if let Some(state_path) = &self.state_path {
            let state = ResumeState {
                transfer_id: self.transfer_id.clone(),
                file_name: self.file_name.clone(),
                total_size: self.total_size,
                total_hash: self.total_hash,
                chunk_size: self.chunk_size,
                bitmap: self.bitmap.clone(),
                chunk_hashes: self.chunk_hashes.clone(),
            };
            state.save_to_file(state_path)?;
        }
        Ok(())
    }
}

impl Drop for FileReceiver {
    fn drop(&mut self) {
        if self.cancelled {
            cleanup_files(&self.temp_path, self.state_path.as_deref());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transfer::checksum::compute_block_hash;
    use crate::transfer::chunk::DEFAULT_CHUNK_SIZE;
    use prost::bytes::Bytes;
    use std::io::Cursor;

    #[test]
    fn тест_полного_цикла_приёма_файла() {
        let dir = std::env::temp_dir().join("bw_test_recv");
        let _ = std::fs::create_dir_all(&dir);

        let temp_path = dir.join(".bw_test.part");
        let dest_path = dir.join("final_received.bin");

        let content = b"hello braidwork file engine";
        let hash = crate::transfer::checksum::compute_file_hash(Cursor::new(content)).unwrap();

        let offer = FileOffer {
            transfer_id: Bytes::from_static(b"tid_full"),
            file_name: "test.txt".to_string(),
            total_size: content.len() as u64,
            total_hash: Bytes::copy_from_slice(&hash),
            blocks: Vec::new(),
            origin: None,
        };

        let mut receiver = FileReceiver::from_offer(
            &offer,
            temp_path.clone(),
            dest_path.clone(),
            None,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        let block = FileBlock {
            offset: 0,
            length: content.len() as u32,
            data: Bytes::from_static(content),
            block_hash: Bytes::copy_from_slice(&compute_block_hash(content)),
            transfer_id: Bytes::from_static(b"tid_full"),
        };

        let res = receiver.write_block(&block).unwrap();
        assert_eq!(res, BlockWriteResult::Complete);

        let final_path = receiver.finish_and_verify().unwrap();
        assert_eq!(final_path, dest_path);
        assert!(dest_path.exists());
        assert!(!temp_path.exists());

        let read_back = std::fs::read(&dest_path).unwrap();
        assert_eq!(read_back, content);

        let _ = std::fs::remove_file(dest_path);
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn тест_отклонения_битого_блока() {
        let dir = std::env::temp_dir().join("bw_test_recv_bad");
        let _ = std::fs::create_dir_all(&dir);

        let temp_path = dir.join(".bw_bad.part");
        let dest_path = dir.join("bad.bin");

        let offer = FileOffer {
            transfer_id: Bytes::from_static(b"tid_bad"),
            file_name: "bad.txt".to_string(),
            total_size: 100,
            total_hash: Bytes::copy_from_slice(&[0u8; 32]),
            blocks: Vec::new(),
            origin: None,
        };

        let mut receiver = FileReceiver::from_offer(
            &offer,
            temp_path.clone(),
            dest_path,
            None,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        // Подсовываем блок правильного размера (100 байт), но с неправильным block_hash
        let block_data = vec![0x42u8; 100];
        let block = FileBlock {
            offset: 0,
            length: 100,
            data: Bytes::copy_from_slice(&block_data),
            block_hash: Bytes::copy_from_slice(&[0xff; 32]),
            transfer_id: Bytes::from_static(b"tid_bad"),
        };

        let err = receiver.write_block(&block).unwrap_err();
        match err {
            TransferError::CorruptedBlock { .. } => {}
            _ => panic!("ожидалась ошибка CorruptedBlock, получено {:?}", err),
        }

        receiver.cancel();
        let _ = std::fs::remove_dir_all(dir);
    }

    #[test]
    fn тест_отклонения_неполного_или_несмещённого_блока() {
        let dir = std::env::temp_dir().join("bw_test_recv_partial");
        let _ = std::fs::create_dir_all(&dir);

        let temp_path = dir.join(".bw_part.part");
        let dest_path = dir.join("part.bin");

        let total_size = 64 * 1024; // 2 чанка по 32 КБ
        let offer = FileOffer {
            transfer_id: Bytes::from_static(b"tid_partial"),
            file_name: "partial.txt".to_string(),
            total_size,
            total_hash: Bytes::copy_from_slice(&[0u8; 32]),
            blocks: Vec::new(),
            origin: None,
        };

        let mut receiver = FileReceiver::from_offer(
            &offer,
            temp_path.clone(),
            dest_path,
            None,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        // 1. Посылаем блок правильного смещения 0, но короче ожидаемого чанка (1000 байт вместо 32768)
        let short_data = vec![0x42u8; 1000];
        let short_block = FileBlock {
            offset: 0,
            length: 1000,
            data: Bytes::copy_from_slice(&short_data),
            block_hash: Bytes::copy_from_slice(&compute_block_hash(&short_data)),
            transfer_id: Bytes::from_static(b"tid_partial"),
        };

        let err = receiver.write_block(&short_block).unwrap_err();
        match err {
            TransferError::MalformedBlock(msg) => {
                assert!(msg.contains("несовпадение границ чанка"));
            }
            _ => panic!("ожидалась ошибка MalformedBlock, получено {:?}", err),
        }

        // Блок не должен был быть помечен как полученный
        assert!(!receiver.bitmap().is_received(0));

        // 2. Посылаем блок с невыровненным смещением (например, 500)
        let misaligned_block = FileBlock {
            offset: 500,
            length: DEFAULT_CHUNK_SIZE,
            data: Bytes::copy_from_slice(&vec![0x42u8; DEFAULT_CHUNK_SIZE as usize]),
            block_hash: Bytes::copy_from_slice(&compute_block_hash(&vec![
                0x42u8;
                DEFAULT_CHUNK_SIZE
                    as usize
            ])),
            transfer_id: Bytes::from_static(b"tid_partial"),
        };

        assert!(receiver.write_block(&misaligned_block).is_err());

        receiver.cancel();
        let _ = std::fs::remove_dir_all(dir);
    }
}
