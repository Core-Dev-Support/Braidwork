//! Разбиение файла на чанки и вычисление смещений.

use crate::transfer::errors::TransferError;

/// Размер блока по умолчанию — 32 КиБ.
/// Укладывается в предел кадра Noise (65535 байт) вместе с накладными расходами protobuf и шифрования.
pub const DEFAULT_CHUNK_SIZE: u32 = 32 * 1024;

/// Максимальный поддерживаемый размер одного блока — 64 КиБ.
pub const MAX_CHUNK_SIZE: u32 = 64 * 1024;

/// Минимальный допустимый размер блока — 1 КиБ.
pub const MIN_CHUNK_SIZE: u32 = 1024;

/// Максимальный поддерживаемый размер входящего файла для предотвращения исчерпания памяти и ресурсов (128 ГиБ).
pub const MAX_FILE_SIZE: u64 = 128 * 1024 * 1024 * 1024;

/// Максимальное число чанков для защиты от исчерпания памяти (2 миллиона).
/// При 32 КиБ на чанк это 64 ГиБ, при 64 КиБ — 128 ГиБ.
pub const MAX_CHUNKS: usize = 2_000_000;

/// Описание отдельного чанка.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ChunkInfo {
    pub index: usize,
    pub offset: u64,
    pub length: u32,
}

/// План нарезки файла на блоки.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChunkPlan {
    total_size: u64,
    chunk_size: u32,
    total_chunks: usize,
}

impl ChunkPlan {
    pub fn new(total_size: u64, chunk_size: u32) -> Result<Self, TransferError> {
        if !(MIN_CHUNK_SIZE..=MAX_CHUNK_SIZE).contains(&chunk_size) {
            return Err(TransferError::InvalidChunkSize(chunk_size));
        }

        if total_size > MAX_FILE_SIZE {
            return Err(TransferError::MalformedOffer(format!(
                "file size exceeds maximum allowed 1 TiB: {}",
                total_size
            )));
        }

        let total_chunks = if total_size == 0 {
            0
        } else {
            // Безопасное деление с округлением вверх без риска переполнения u64
            let cs = chunk_size as u64;
            let div = total_size / cs;
            let rem = total_size % cs;
            let chunks = if rem > 0 {
                div.checked_add(1).ok_or_else(|| {
                    TransferError::MalformedOffer(
                        "переполнение при вычислении числа чанков".to_string(),
                    )
                })?
            } else {
                div
            };

            if chunks > MAX_CHUNKS as u64 {
                return Err(TransferError::MalformedOffer(format!(
                    "число чанков ({}) превышает лимит безопасности ({})",
                    chunks, MAX_CHUNKS
                )));
            }
            chunks as usize
        };

        Ok(Self {
            total_size,
            chunk_size,
            total_chunks,
        })
    }

    #[inline]
    pub fn total_size(&self) -> u64 {
        self.total_size
    }

    #[inline]
    pub fn chunk_size(&self) -> u32 {
        self.chunk_size
    }

    #[inline]
    pub fn total_chunks(&self) -> usize {
        self.total_chunks
    }

    /// Получает информацию о чанке по его порядковому номеру.
    pub fn chunk_at(&self, index: usize) -> Option<ChunkInfo> {
        if index >= self.total_chunks {
            return None;
        }

        let offset = index as u64 * self.chunk_size as u64;
        let remaining = self.total_size.saturating_sub(offset);
        let length = std::cmp::min(remaining, self.chunk_size as u64) as u32;

        Some(ChunkInfo {
            index,
            offset,
            length,
        })
    }

    /// Определяет чанк по байтовому смещению в файле.
    pub fn chunk_for_offset(&self, offset: u64) -> Option<ChunkInfo> {
        if offset >= self.total_size {
            return None;
        }
        let index = (offset / self.chunk_size as u64) as usize;
        self.chunk_at(index)
    }

    /// Проверяет, что блок с заданным смещением и длиной не выходит за границы файла.
    pub fn validate_bounds(&self, offset: u64, length: usize) -> Result<(), TransferError> {
        if length > self.chunk_size as usize {
            return Err(TransferError::MalformedBlock(format!(
                "длина блока {} превышает размер чанка {}",
                length, self.chunk_size
            )));
        }

        if offset.checked_add(length as u64).is_none() || offset + length as u64 > self.total_size {
            return Err(TransferError::OutOfBounds {
                offset,
                length,
                total_size: self.total_size,
            });
        }

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn тест_нарезки_пустого_файла() {
        let plan = ChunkPlan::new(0, DEFAULT_CHUNK_SIZE).unwrap();
        assert_eq!(plan.total_chunks(), 0);
        assert!(plan.chunk_at(0).is_none());
    }

    #[test]
    fn тест_нарезки_точного_кратного_размера() {
        let size = 64 * 1024;
        let plan = ChunkPlan::new(size, 32 * 1024).unwrap();
        assert_eq!(plan.total_chunks(), 2);

        let c0 = plan.chunk_at(0).unwrap();
        assert_eq!(c0.index, 0);
        assert_eq!(c0.offset, 0);
        assert_eq!(c0.length, 32 * 1024);

        let c1 = plan.chunk_at(1).unwrap();
        assert_eq!(c1.index, 1);
        assert_eq!(c1.offset, 32 * 1024);
        assert_eq!(c1.length, 32 * 1024);

        assert!(plan.chunk_at(2).is_none());
    }

    #[test]
    fn тест_нарезки_некратного_размера() {
        let size = 70 * 1024; // 32k + 32k + 6k
        let plan = ChunkPlan::new(size, 32 * 1024).unwrap();
        assert_eq!(plan.total_chunks(), 3);

        let c2 = plan.chunk_at(2).unwrap();
        assert_eq!(c2.index, 2);
        assert_eq!(c2.offset, 64 * 1024);
        assert_eq!(c2.length, 6 * 1024);
    }

    #[test]
    fn тест_недопустимого_размера_чанка() {
        assert!(ChunkPlan::new(100, 500).is_err());
        assert!(ChunkPlan::new(100, 128 * 1024).is_err());
    }
}
