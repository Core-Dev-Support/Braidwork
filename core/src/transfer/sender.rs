//! Отправитель файлов (FileSender).
//!
//! Управляет процессом передачи файла:
//! - Формирует `FileOffer` с метаданными и итоговым BLAKE3 хэшем.
//! - Читает чанки из потока `Read + Seek` и упаковывает их в `FileBlock` с хэшем каждого блока.
//! - Обрабатывает `FileRequest` для повторной отправки только потерянных блоков (ACK/Resume).
//! - Контролирует границы и гарантирует независимость от платформы (C ABI/JNI/десктоп).

use crate::pb::{FileBlock, FileOffer, FileRequest};
use crate::transfer::ack::parse_missing_chunks;
use crate::transfer::checksum::{compute_block_hash, compute_file_hash};
use crate::transfer::chunk::ChunkPlan;
use crate::transfer::errors::TransferError;
use prost::bytes::Bytes;
use std::collections::VecDeque;
use std::io::{Read, Seek, SeekFrom};

/// Отправитель файлов.
pub struct FileSender {
    transfer_id: Vec<u8>,
    file_name: String,
    total_size: u64,
    total_hash: [u8; 32],
    plan: ChunkPlan,
    next_chunk_idx: usize,
    pending_retransmit: VecDeque<usize>,
    bytes_sent: u64,
    cancelled: bool,
}

impl FileSender {
    /// Создаёт отправитель с уже известными метаданными и хэшем.
    pub fn new(
        transfer_id: Vec<u8>,
        file_name: String,
        total_size: u64,
        total_hash: [u8; 32],
        chunk_size: u32,
    ) -> Result<Self, TransferError> {
        let plan = ChunkPlan::new(total_size, chunk_size)?;
        Ok(Self {
            transfer_id,
            file_name,
            total_size,
            total_hash,
            plan,
            next_chunk_idx: 0,
            pending_retransmit: VecDeque::new(),
            bytes_sent: 0,
            cancelled: false,
        })
    }

    /// Создаёт отправитель из любого `Read + Seek`, автоматически вычисляя размер и хэш BLAKE3.
    pub fn from_reader<R: Read + Seek>(
        transfer_id: Vec<u8>,
        file_name: String,
        mut reader: R,
        chunk_size: u32,
    ) -> Result<Self, TransferError> {
        let total_size = reader.seek(SeekFrom::End(0))?;
        reader.seek(SeekFrom::Start(0))?;

        let total_hash = compute_file_hash(&mut reader)?;
        reader.seek(SeekFrom::Start(0))?;

        Self::new(transfer_id, file_name, total_size, total_hash, chunk_size)
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
    pub fn plan(&self) -> &ChunkPlan {
        &self.plan
    }

    #[inline]
    pub fn is_cancelled(&self) -> bool {
        self.cancelled
    }

    pub fn cancel(&mut self) {
        self.cancelled = true;
    }

    /// Формирует protobuf-сообщение `FileOffer`.
    pub fn build_offer(&self) -> FileOffer {
        FileOffer {
            transfer_id: Bytes::copy_from_slice(&self.transfer_id),
            file_name: self.file_name.clone(),
            total_size: self.total_size,
            total_hash: Bytes::copy_from_slice(&self.total_hash),
            blocks: Vec::new(),
            origin: None,
        }
    }

    /// Считывает следующий блок из потока.
    /// Сначала отправляются запрошенные повторно блоки (retransmission), затем — новые по порядку.
    /// Возвращает `Ok(None)`, когда все блоки отправлены и нет повторных запросов.
    pub fn next_block<R: Read + Seek>(
        &mut self,
        reader: &mut R,
    ) -> Result<Option<FileBlock>, TransferError> {
        if self.cancelled {
            return Err(TransferError::Cancelled);
        }

        // Определяем индекс следующего чанка
        let chunk_idx = if let Some(retransmit_idx) = self.pending_retransmit.pop_front() {
            retransmit_idx
        } else if self.next_chunk_idx < self.plan.total_chunks() {
            let idx = self.next_chunk_idx;
            self.next_chunk_idx += 1;
            idx
        } else {
            return Ok(None);
        };

        let chunk_info = self.plan.chunk_at(chunk_idx).ok_or_else(|| {
            TransferError::InvalidState(format!("не найден чанк с индексом {}", chunk_idx))
        })?;

        reader.seek(SeekFrom::Start(chunk_info.offset))?;
        let mut buf = vec![0u8; chunk_info.length as usize];
        reader.read_exact(&mut buf)?;

        let block_hash = compute_block_hash(&buf);

        self.bytes_sent = self.bytes_sent.saturating_add(chunk_info.length as u64);

        Ok(Some(FileBlock {
            offset: chunk_info.offset,
            length: chunk_info.length,
            data: Bytes::from(buf),
            block_hash: Bytes::copy_from_slice(&block_hash),
            transfer_id: Bytes::copy_from_slice(&self.transfer_id),
        }))
    }

    /// Возвращает информацию о следующем требуемом чанке (номер, смещение, длина) без чтения из потока.
    /// Возвращает `None`, если все блоки переданы и очередь повторных запросов пуста.
    pub fn peek_next_chunk(&self) -> Option<crate::transfer::chunk::ChunkInfo> {
        if self.cancelled {
            return None;
        }
        let chunk_idx = if let Some(&retransmit_idx) = self.pending_retransmit.front() {
            retransmit_idx
        } else if self.next_chunk_idx < self.plan.total_chunks() {
            self.next_chunk_idx
        } else {
            return None;
        };
        self.plan.chunk_at(chunk_idx)
    }

    /// Продвигает очередь чанков после отправки очередного блока.
    pub fn advance_next_chunk(&mut self) -> Option<usize> {
        if let Some(retransmit_idx) = self.pending_retransmit.pop_front() {
            Some(retransmit_idx)
        } else if self.next_chunk_idx < self.plan.total_chunks() {
            let idx = self.next_chunk_idx;
            self.next_chunk_idx += 1;
            Some(idx)
        } else {
            None
        }
    }

    /// Собирает `FileBlock` из переданных байтов для указанного смещения с подсчётом BLAKE3 блока.
    pub fn build_block(&mut self, offset: u64, data: &[u8]) -> Result<FileBlock, TransferError> {
        if self.cancelled {
            return Err(TransferError::Cancelled);
        }
        self.plan.validate_bounds(offset, data.len())?;
        let block_hash = compute_block_hash(data);
        self.bytes_sent = self.bytes_sent.saturating_add(data.len() as u64);

        Ok(FileBlock {
            offset,
            length: data.len() as u32,
            data: Bytes::copy_from_slice(data),
            block_hash: Bytes::copy_from_slice(&block_hash),
            transfer_id: Bytes::copy_from_slice(&self.transfer_id),
        })
    }

    /// Обрабатывает запрос недостающих блоков `FileRequest` от получателя.
    /// Ставит в очередь на повторную отправку только указанные блоки.
    pub fn handle_request(&mut self, req: &FileRequest) -> Result<usize, TransferError> {
        if req.transfer_id != self.transfer_id.as_slice() {
            return Err(TransferError::TransferMismatch {
                expected: self.transfer_id.clone(),
                actual: req.transfer_id.to_vec(),
            });
        }

        let missing = parse_missing_chunks(req, &self.plan)?;
        let count = missing.len();

        for idx in missing {
            if !self.pending_retransmit.contains(&idx) {
                self.pending_retransmit.push_back(idx);
            }
        }

        Ok(count)
    }

    /// Завершён ли первичный прогон отправки всех блоков.
    #[inline]
    pub fn is_initial_pass_done(&self) -> bool {
        self.next_chunk_idx >= self.plan.total_chunks() && self.pending_retransmit.is_empty()
    }

    /// Прогресс передачи: (отправлено байт, всего байт, доля от 0.0 до 1.0).
    pub fn progress(&self) -> (u64, u64, f32) {
        let total = self.total_size;
        let sent = std::cmp::min(self.bytes_sent, total);
        let fraction = if total == 0 {
            1.0
        } else {
            (sent as f32 / total as f32).clamp(0.0, 1.0)
        };
        (sent, total, fraction)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transfer::chunk::DEFAULT_CHUNK_SIZE;
    use std::io::Cursor;

    #[test]
    fn тест_отправки_всех_блоков() {
        let content = vec![0x33u8; 70 * 1024]; // 32k + 32k + 6k = 3 блока
        let mut cursor = Cursor::new(content.clone());

        let mut sender = FileSender::from_reader(
            b"tid_1".to_vec(),
            "test.bin".to_string(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        assert_eq!(sender.plan().total_chunks(), 3);

        let offer = sender.build_offer();
        assert_eq!(offer.file_name, "test.bin");
        assert_eq!(offer.total_size, 70 * 1024);

        let mut blocks = Vec::new();
        while let Some(b) = sender.next_block(&mut cursor).unwrap() {
            blocks.push(b);
        }

        assert_eq!(blocks.len(), 3);
        assert_eq!(blocks[0].offset, 0);
        assert_eq!(blocks[0].data.len(), 32 * 1024);
        assert_eq!(blocks[1].offset, 32 * 1024);
        assert_eq!(blocks[1].data.len(), 32 * 1024);
        assert_eq!(blocks[2].offset, 64 * 1024);
        assert_eq!(blocks[2].data.len(), 6 * 1024);

        assert!(sender.is_initial_pass_done());
        assert!(sender.next_block(&mut cursor).unwrap().is_none());
    }

    #[test]
    fn тест_повторной_отправки_блока() {
        let content = vec![0x77u8; 64 * 1024]; // 2 блока по 32кб
        let mut cursor = Cursor::new(content);

        let mut sender = FileSender::from_reader(
            b"tid_2".to_vec(),
            "two_blocks.bin".to_string(),
            &mut cursor,
            DEFAULT_CHUNK_SIZE,
        )
        .unwrap();

        // Читаем оба
        let _b0 = sender.next_block(&mut cursor).unwrap();
        let _b1 = sender.next_block(&mut cursor).unwrap();
        assert!(sender.next_block(&mut cursor).unwrap().is_none());

        // Получатель просит блок 0
        let req = FileRequest {
            transfer_id: Bytes::from_static(b"tid_2"),
            missing_offsets: vec![0],
            partial_hash: Bytes::new(),
        };

        let retransmit_count = sender.handle_request(&req).unwrap();
        assert_eq!(retransmit_count, 1);

        // Следующий вызов отдаёт именно блок 0
        let retry_block = sender.next_block(&mut cursor).unwrap().unwrap();
        assert_eq!(retry_block.offset, 0);
        assert_eq!(retry_block.length, 32 * 1024);

        // После этого снова пусто
        assert!(sender.next_block(&mut cursor).unwrap().is_none());
    }
}
