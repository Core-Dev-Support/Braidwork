//! Сохранение состояния и возобновление передачи (Resume).
//!
//! Защищает передачу от обрывов сети и перезапуска приложения:
//! - Сохраняет метаданные файла, параметры нарезки, битовую карту и криптографические хэши блоков.
//! - Проверяет целостность временного файла (`.bw_<tid>.part`) перед возобновлением:
//!   каждый ранее подтверждённый блок перепроверяется по BLAKE3 прямо с диска.
//! - Повреждённые или усечённые блоки автоматически сбрасываются и запрашиваются повторно.

use crate::transfer::ack::ChunkBitmap;
use crate::transfer::chunk::ChunkPlan;
use crate::transfer::errors::TransferError;
use std::fs::{File, OpenOptions};
use std::io::{Read, Seek, SeekFrom, Write};
use std::path::Path;

const RESUME_MAGIC: &[u8; 8] = b"BWRESUME";
const RESUME_VERSION: u32 = 2;

/// Метаданные и сохранённое состояние незавершённой передачи.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResumeState {
    pub transfer_id: Vec<u8>,
    pub file_name: String,
    pub total_size: u64,
    pub total_hash: [u8; 32],
    pub chunk_size: u32,
    pub bitmap: ChunkBitmap,
    pub chunk_hashes: Vec<Option<[u8; 32]>>,
}

impl ResumeState {
    pub fn new(
        transfer_id: Vec<u8>,
        file_name: String,
        total_size: u64,
        total_hash: [u8; 32],
        chunk_size: u32,
        total_chunks: usize,
    ) -> Self {
        Self {
            transfer_id,
            file_name,
            total_size,
            total_hash,
            chunk_size,
            bitmap: ChunkBitmap::new(total_chunks),
            chunk_hashes: vec![None; total_chunks],
        }
    }

    /// Записывает факт успешного приёма блока и его хэш.
    pub fn record_chunk(&mut self, chunk_idx: usize, hash: [u8; 32]) {
        if chunk_idx < self.bitmap.total_chunks() {
            self.bitmap.mark_received(chunk_idx);
            if chunk_idx >= self.chunk_hashes.len() {
                self.chunk_hashes.resize(self.bitmap.total_chunks(), None);
            }
            self.chunk_hashes[chunk_idx] = Some(hash);
        }
    }

    /// Сериализует состояние в байтовый буфер.
    pub fn to_bytes(&self) -> Vec<u8> {
        let mut buf = Vec::new();
        buf.extend_from_slice(RESUME_MAGIC);
        buf.extend_from_slice(&RESUME_VERSION.to_le_bytes());

        // transfer_id
        buf.extend_from_slice(&(self.transfer_id.len() as u16).to_le_bytes());
        buf.extend_from_slice(&self.transfer_id);

        // total_size, total_hash, chunk_size
        buf.extend_from_slice(&self.total_size.to_le_bytes());
        buf.extend_from_slice(&self.total_hash);
        buf.extend_from_slice(&self.chunk_size.to_le_bytes());

        // file_name
        let name_bytes = self.file_name.as_bytes();
        buf.extend_from_slice(&(name_bytes.len() as u16).to_le_bytes());
        buf.extend_from_slice(name_bytes);

        // bitmap
        let total_chunks = self.bitmap.total_chunks();
        buf.extend_from_slice(&(total_chunks as u64).to_le_bytes());
        let bmp_bytes = self.bitmap.as_bytes();
        buf.extend_from_slice(&(bmp_bytes.len() as u64).to_le_bytes());
        buf.extend_from_slice(bmp_bytes);

        // chunk_hashes: сохраняем хэши для всех принятых блоков
        for i in 0..total_chunks {
            if self.bitmap.is_received(i) {
                let h = self.chunk_hashes.get(i).and_then(|x| *x).unwrap_or([0u8; 32]);
                buf.extend_from_slice(&h);
            }
        }

        buf
    }

    /// Десериализует состояние из байтов.
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, TransferError> {
        let mut cursor = std::io::Cursor::new(bytes);

        let mut magic = [0u8; 8];
        cursor.read_exact(&mut magic)?;
        if &magic != RESUME_MAGIC {
            return Err(TransferError::InvalidState(
                "неверная сигнатура файла состояния (magic mismatch)".to_string(),
            ));
        }

        let mut ver_bytes = [0u8; 4];
        cursor.read_exact(&mut ver_bytes)?;
        let version = u32::from_le_bytes(ver_bytes);
        if version != 1 && version != RESUME_VERSION {
            return Err(TransferError::InvalidState(format!(
                "неподдерживаемая версия файла состояния: {}",
                version
            )));
        }

        let mut tid_len_bytes = [0u8; 2];
        cursor.read_exact(&mut tid_len_bytes)?;
        let tid_len = u16::from_le_bytes(tid_len_bytes) as usize;
        let mut transfer_id = vec![0u8; tid_len];
        cursor.read_exact(&mut transfer_id)?;

        let mut size_bytes = [0u8; 8];
        cursor.read_exact(&mut size_bytes)?;
        let total_size = u64::from_le_bytes(size_bytes);

        let mut total_hash = [0u8; 32];
        cursor.read_exact(&mut total_hash)?;

        let mut chunk_size_bytes = [0u8; 4];
        cursor.read_exact(&mut chunk_size_bytes)?;
        let chunk_size = u32::from_le_bytes(chunk_size_bytes);

        let mut name_len_bytes = [0u8; 2];
        cursor.read_exact(&mut name_len_bytes)?;
        let name_len = u16::from_le_bytes(name_len_bytes) as usize;
        let mut name_bytes = vec![0u8; name_len];
        cursor.read_exact(&mut name_bytes)?;
        let file_name = String::from_utf8(name_bytes).map_err(|_| {
            TransferError::InvalidState("имя файла в состоянии не является UTF-8".to_string())
        })?;

        let mut total_chunks_bytes = [0u8; 8];
        cursor.read_exact(&mut total_chunks_bytes)?;
        let total_chunks = u64::from_le_bytes(total_chunks_bytes) as usize;

        let mut bmp_len_bytes = [0u8; 8];
        cursor.read_exact(&mut bmp_len_bytes)?;
        let bmp_len = u64::from_le_bytes(bmp_len_bytes) as usize;
        let mut bmp_bytes = vec![0u8; bmp_len];
        cursor.read_exact(&mut bmp_bytes)?;

        let bitmap = ChunkBitmap::from_bytes(total_chunks, &bmp_bytes)?;
        let mut chunk_hashes = vec![None; total_chunks];

        if version >= 2 {
            for i in 0..total_chunks {
                if bitmap.is_received(i) {
                    let mut h = [0u8; 32];
                    cursor.read_exact(&mut h)?;
                    chunk_hashes[i] = Some(h);
                }
            }
        }

        Ok(Self {
            transfer_id,
            file_name,
            total_size,
            total_hash,
            chunk_size,
            bitmap,
            chunk_hashes,
        })
    }

    /// Сохраняет состояние передачи в файл на диске.
    pub fn save_to_file(&self, path: &Path) -> Result<(), TransferError> {
        let bytes = self.to_bytes();
        let mut file = OpenOptions::new()
            .create(true)
            .write(true)
            .truncate(true)
            .open(path)?;
        file.write_all(&bytes)?;
        file.flush()?;
        Ok(())
    }

    /// Загружает состояние передачи из файла на диске.
    pub fn load_from_file(path: &Path) -> Result<Self, TransferError> {
        let mut file = File::open(path)?;
        let mut bytes = Vec::new();
        file.read_to_end(&mut bytes)?;
        Self::from_bytes(&bytes)
    }

    /// Проверяет соответствие временного `.part` файла сохранённому состоянию.
    pub fn validate_part_file(&self, part_path: &Path) -> Result<(), TransferError> {
        if !part_path.exists() {
            return Err(TransferError::InvalidState(format!(
                "временный файл {:?} не найден",
                part_path
            )));
        }

        let meta = std::fs::metadata(part_path)?;
        let file_len = meta.len();

        if file_len > self.total_size {
            return Err(TransferError::OutOfBounds {
                offset: file_len,
                length: 0,
                total_size: self.total_size,
            });
        }

        Ok(())
    }

    /// Криптографическая сверка: проверяет BLAKE3 хэш каждого принятого чанка в файле `.part`.
    /// Если чанк повреждён, усечён или не читается, он сбрасывается из битовой карты
    /// для последующей чистой докачки.
    pub fn reconcile_and_verify_part_file<R: Read + Seek>(
        &mut self,
        reader: &mut R,
    ) -> Result<usize, TransferError> {
        let plan = ChunkPlan::new(self.total_size, self.chunk_size)?;
        let mut verified = 0;

        for idx in 0..self.bitmap.total_chunks() {
            if !self.bitmap.is_received(idx) {
                continue;
            }

            let info = match plan.chunk_at(idx) {
                Some(i) => i,
                None => {
                    self.bitmap.clear_received(idx);
                    if idx < self.chunk_hashes.len() {
                        self.chunk_hashes[idx] = None;
                    }
                    continue;
                }
            };

            // Проверяем позиционирование
            if reader.seek(SeekFrom::Start(info.offset)).is_err() {
                self.bitmap.clear_received(idx);
                if idx < self.chunk_hashes.len() {
                    self.chunk_hashes[idx] = None;
                }
                continue;
            }

            let mut buf = vec![0u8; info.length as usize];
            if reader.read_exact(&mut buf).is_err() {
                self.bitmap.clear_received(idx);
                if idx < self.chunk_hashes.len() {
                    self.chunk_hashes[idx] = None;
                }
                continue;
            }

            // Если сохранён хэш блока, проверяем его!
            if let Some(expected_hash) = self.chunk_hashes.get(idx).and_then(|h| *h) {
                let actual_hash = crate::transfer::checksum::compute_block_hash(&buf);
                if actual_hash != expected_hash {
                    // Блок на диске повреждён! Сбрасываем его
                    self.bitmap.clear_received(idx);
                    self.chunk_hashes[idx] = None;
                    continue;
                }
            }

            verified += 1;
        }

        Ok(verified)
    }
}

/// Удаляет временные файлы при успешном завершении или отмене.
pub fn cleanup_files(part_path: &Path, state_path: Option<&Path>) {
    if part_path.exists() {
        let _ = std::fs::remove_file(part_path);
    }
    if let Some(sp) = state_path {
        if sp.exists() {
            let _ = std::fs::remove_file(sp);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn тест_сериализации_и_восстановления_состояния() {
        let mut state = ResumeState::new(
            b"transfer_42".to_vec(),
            "photo.jpg".to_string(),
            100_000,
            [0x5a; 32],
            32 * 1024,
            4,
        );
        state.record_chunk(0, [0x11; 32]);
        state.record_chunk(2, [0x22; 32]);

        let bytes = state.to_bytes();
        let restored = ResumeState::from_bytes(&bytes).unwrap();

        assert_eq!(state.transfer_id, restored.transfer_id);
        assert_eq!(state.file_name, restored.file_name);
        assert_eq!(state.total_size, restored.total_size);
        assert_eq!(state.total_hash, restored.total_hash);
        assert_eq!(state.chunk_size, restored.chunk_size);
        assert_eq!(state.bitmap, restored.bitmap);
        assert_eq!(restored.bitmap.received_count(), 2);
        assert_eq!(restored.chunk_hashes[0], Some([0x11; 32]));
        assert_eq!(restored.chunk_hashes[1], None);
        assert_eq!(restored.chunk_hashes[2], Some([0x22; 32]));
    }

    #[test]
    fn тест_сверки_повреждённого_файла_при_возобновлении() {
        let chunk_size = 32 * 1024;
        let mut state = ResumeState::new(
            b"tid_reconcile".to_vec(),
            "data.bin".to_string(),
            64 * 1024,
            [0xaa; 32],
            chunk_size,
            2,
        );

        let chunk0_data = vec![0x11u8; chunk_size as usize];
        let chunk1_data = vec![0x22u8; chunk_size as usize];

        let hash0 = crate::transfer::checksum::compute_block_hash(&chunk0_data);
        let hash1 = crate::transfer::checksum::compute_block_hash(&chunk1_data);

        state.record_chunk(0, hash0);
        state.record_chunk(1, hash1);

        // Создаём образ диска: блок 0 целый, блок 1 повреждён (заменен байт)
        let mut disk_data = Vec::new();
        disk_data.extend_from_slice(&chunk0_data);
        let mut corrupted_chunk1 = chunk1_data.clone();
        corrupted_chunk1[500] = 0x99;
        disk_data.extend_from_slice(&corrupted_chunk1);

        let mut cursor = Cursor::new(disk_data);
        let verified = state.reconcile_and_verify_part_file(&mut cursor).unwrap();

        // Должен подтвердиться только блок 0, а блок 1 должен быть сброшен
        assert_eq!(verified, 1);
        assert!(state.bitmap.is_received(0));
        assert!(!state.bitmap.is_received(1));
        assert_eq!(state.bitmap.received_count(), 1);
        assert_eq!(state.bitmap.missing_indices(), vec![1]);
    }
}
