//! Сохранение состояния и возобновление передачи (Resume).
//!
//! Защищает передачу от обрывов сети и перезапуска приложения:
//! - Сохраняет метаданные файла, параметры нарезки и битовую карту подтверждённых блоков.
//! - Проверяет целостность временного файла (`.bw_<tid>.part`) перед возобновлением.
//! - Не позволяет повреждённому или усечённому файлу сойти за готовый.

use crate::transfer::ack::ChunkBitmap;
use crate::transfer::chunk::ChunkPlan;
use crate::transfer::errors::TransferError;
use std::fs::{File, OpenOptions};
use std::io::{Read, Write};
use std::path::Path;

const RESUME_MAGIC: &[u8; 8] = b"BWRESUME";
const RESUME_VERSION: u32 = 1;

/// Метаданные и сохранённое состояние незавершённой передачи.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResumeState {
    pub transfer_id: Vec<u8>,
    pub file_name: String,
    pub total_size: u64,
    pub total_hash: [u8; 32],
    pub chunk_size: u32,
    pub bitmap: ChunkBitmap,
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
        buf.extend_from_slice(&(self.bitmap.total_chunks() as u64).to_le_bytes());
        let bmp_bytes = self.bitmap.as_bytes();
        buf.extend_from_slice(&(bmp_bytes.len() as u64).to_le_bytes());
        buf.extend_from_slice(bmp_bytes);

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
        if version != RESUME_VERSION {
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

        Ok(Self {
            transfer_id,
            file_name,
            total_size,
            total_hash,
            chunk_size,
            bitmap,
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

        // Проверяем, что размер файла не меньше последнего принятого чанка
        let plan = ChunkPlan::new(self.total_size, self.chunk_size)?;
        for idx in 0..self.bitmap.total_chunks() {
            if self.bitmap.is_received(idx) {
                if let Some(info) = plan.chunk_at(idx) {
                    let needed_len = info.offset + info.length as u64;
                    if file_len < needed_len {
                        return Err(TransferError::InvalidState(format!(
                            "размер файла {} меньше ожидаемого смещения чанка {}",
                            file_len, needed_len
                        )));
                    }
                }
            }
        }

        Ok(())
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
        state.bitmap.mark_received(0);
        state.bitmap.mark_received(2);

        let bytes = state.to_bytes();
        let restored = ResumeState::from_bytes(&bytes).unwrap();

        assert_eq!(state.transfer_id, restored.transfer_id);
        assert_eq!(state.file_name, restored.file_name);
        assert_eq!(state.total_size, restored.total_size);
        assert_eq!(state.total_hash, restored.total_hash);
        assert_eq!(state.chunk_size, restored.chunk_size);
        assert_eq!(state.bitmap, restored.bitmap);
        assert_eq!(restored.bitmap.received_count(), 2);
    }
}
