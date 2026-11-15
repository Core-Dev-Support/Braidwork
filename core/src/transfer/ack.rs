//! Подтверждения доставки (ACK) и битовая карта принятых чанков.

use crate::pb::FileRequest;
use crate::transfer::chunk::ChunkPlan;
use crate::transfer::errors::TransferError;
use prost::bytes::Bytes;

/// Битовая карта для учёта подтверждённых чанков передачи.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChunkBitmap {
    total_chunks: usize,
    received_count: usize,
    bits: Vec<u8>,
}

impl ChunkBitmap {
    /// Создаёт новую битовую карту для заданного количества блоков.
    pub fn new(total_chunks: usize) -> Self {
        let byte_count = total_chunks.div_ceil(8);
        Self {
            total_chunks,
            received_count: 0,
            bits: vec![0u8; byte_count],
        }
    }

    /// Восстанавливает битовую карту из сохранённого среза байтов.
    pub fn from_bytes(total_chunks: usize, bytes: &[u8]) -> Result<Self, TransferError> {
        let expected_bytes = total_chunks.div_ceil(8);
        if bytes.len() != expected_bytes {
            return Err(TransferError::MalformedBlock(format!(
                "некорректный размер карты чанков: ожидалось {} байт, получено {}",
                expected_bytes,
                bytes.len()
            )));
        }

        let mut received = 0;
        for i in 0..total_chunks {
            let byte_idx = i / 8;
            let bit_idx = i % 8;
            if (bytes[byte_idx] & (1 << bit_idx)) != 0 {
                received += 1;
            }
        }

        Ok(Self {
            total_chunks,
            received_count: received,
            bits: bytes.to_vec(),
        })
    }

    /// Экспортирует байты карты для сохранения на диск (resume).
    #[inline]
    pub fn as_bytes(&self) -> &[u8] {
        &self.bits
    }

    #[inline]
    pub fn total_chunks(&self) -> usize {
        self.total_chunks
    }

    #[inline]
    pub fn received_count(&self) -> usize {
        self.received_count
    }

    #[inline]
    pub fn is_complete(&self) -> bool {
        self.received_count == self.total_chunks
    }

    /// Отмечает чанк как принятый.
    /// Возвращает `true`, если чанк был новым, или `false`, если это дубликат.
    pub fn mark_received(&mut self, chunk_idx: usize) -> bool {
        if chunk_idx >= self.total_chunks {
            return false;
        }

        let byte_idx = chunk_idx / 8;
        let bit_idx = chunk_idx % 8;
        let mask = 1 << bit_idx;

        if (self.bits[byte_idx] & mask) == 0 {
            self.bits[byte_idx] |= mask;
            self.received_count += 1;
            true
        } else {
            false
        }
    }

    /// Сбрасывает отметку о получении чанка (например, если при проверке возобновления блок на диске оказался повреждён).
    pub fn clear_received(&mut self, chunk_idx: usize) -> bool {
        if chunk_idx >= self.total_chunks {
            return false;
        }

        let byte_idx = chunk_idx / 8;
        let bit_idx = chunk_idx % 8;
        let mask = 1 << bit_idx;

        if (self.bits[byte_idx] & mask) != 0 {
            self.bits[byte_idx] &= !mask;
            self.received_count = self.received_count.saturating_sub(1);
            true
        } else {
            false
        }
    }

    /// Проверяет, был ли принят чанк с указанным индексом.
    pub fn is_received(&self, chunk_idx: usize) -> bool {
        if chunk_idx >= self.total_chunks {
            return false;
        }
        let byte_idx = chunk_idx / 8;
        let bit_idx = chunk_idx % 8;
        (self.bits[byte_idx] & (1 << bit_idx)) != 0
    }

    /// Возвращает список индексов всех непринятых блоков.
    pub fn missing_indices(&self) -> Vec<usize> {
        let mut missing = Vec::with_capacity(self.total_chunks.saturating_sub(self.received_count));
        for i in 0..self.total_chunks {
            if !self.is_received(i) {
                missing.push(i);
            }
        }
        missing
    }
}

/// Максимальное число смещений недостающих блоков в одном кадре FileRequest (защита от лимита Noise 64 КиБ).
pub const MAX_REQUEST_OFFSETS_PER_FRAME: usize = 1024;

/// Формирует порцию запроса недостающих блоков `FileRequest` для докачки, сканируя битовую карту
/// начиная с абсолютного индекса чанка `start_chunk_idx`.
///
/// Работает за строго линейное время O(K) от числа просмотренных чанков без промежуточных аллокаций
/// полного вектора индексов и без рассинхронизации при поступлении параллельных блоков.
///
/// Возвращает `(FileRequest, next_chunk_idx, is_last)`.
pub fn build_file_request_batch(
    transfer_id: &[u8],
    plan: &ChunkPlan,
    bitmap: &ChunkBitmap,
    start_chunk_idx: usize,
    max_count: usize,
    partial_hash: Option<[u8; 32]>,
) -> (FileRequest, usize, bool) {
    let total_chunks = plan.total_chunks();
    if total_chunks == 0 || bitmap.is_complete() {
        return (build_completion_ack(transfer_id), total_chunks, true);
    }

    if start_chunk_idx >= total_chunks {
        return (build_completion_ack(transfer_id), total_chunks, true);
    }

    let limit = if max_count == 0 || max_count > MAX_REQUEST_OFFSETS_PER_FRAME {
        MAX_REQUEST_OFFSETS_PER_FRAME
    } else {
        max_count
    };

    let mut missing_offsets = Vec::with_capacity(limit);
    let mut curr_chunk_idx = start_chunk_idx;

    while curr_chunk_idx < total_chunks && missing_offsets.len() < limit {
        if !bitmap.is_received(curr_chunk_idx) {
            if let Some(info) = plan.chunk_at(curr_chunk_idx) {
                missing_offsets.push(info.offset);
            }
        }
        curr_chunk_idx += 1;
    }

    // Проверяем, есть ли ещё не полученные блоки в оставшейся части файла
    let mut has_more_missing = false;
    for idx in curr_chunk_idx..total_chunks {
        if !bitmap.is_received(idx) {
            has_more_missing = true;
            break;
        }
    }

    let is_last = !has_more_missing;
    let next_chunk_idx = curr_chunk_idx;

    let req = FileRequest {
        transfer_id: Bytes::copy_from_slice(transfer_id),
        missing_offsets,
        partial_hash: match partial_hash {
            Some(h) => Bytes::copy_from_slice(&h),
            None => Bytes::new(),
        },
    };

    (req, next_chunk_idx, is_last)
}

/// Формирует запрос недостающих блоков `FileRequest` для докачки (ограничен лимитом одного кадра).
pub fn build_file_request(
    transfer_id: &[u8],
    plan: &ChunkPlan,
    bitmap: &ChunkBitmap,
    partial_hash: Option<[u8; 32]>,
) -> FileRequest {
    build_file_request_batch(
        transfer_id,
        plan,
        bitmap,
        0,
        MAX_REQUEST_OFFSETS_PER_FRAME,
        partial_hash,
    )
    .0
}

/// Проверяет, является ли FileRequest положительным подтверждением (ACK) полного завершения приёма файла.
/// Если missing_offsets пуст — получатель подтверждает, что все блоки приняты и проверены.
#[inline]
pub fn is_transfer_complete_ack(req: &FileRequest) -> bool {
    req.missing_offsets.is_empty()
}

/// Формирует положительное подтверждение (ACK) успешного завершения приёма файла.
pub fn build_completion_ack(transfer_id: &[u8]) -> FileRequest {
    FileRequest {
        transfer_id: Bytes::copy_from_slice(transfer_id),
        missing_offsets: Vec::new(),
        partial_hash: Bytes::new(),
    }
}

/// Разбирает `FileRequest` и возвращает список индексов недостающих чанков.
pub fn parse_missing_chunks(
    req: &FileRequest,
    plan: &ChunkPlan,
) -> Result<Vec<usize>, TransferError> {
    let mut indices = Vec::with_capacity(req.missing_offsets.len());

    for &offset in &req.missing_offsets {
        let chunk = plan.chunk_for_offset(offset).ok_or_else(|| {
            TransferError::MalformedBlock(format!(
                "запрошено несуществующее смещение {} при размере файла {}",
                offset,
                plan.total_size()
            ))
        })?;

        // Проверяем точное совпадение смещения начала чанка
        if chunk.offset != offset {
            return Err(TransferError::MalformedBlock(format!(
                "смещение {} не выровнено по границе чанка (ожидалось {})",
                offset, chunk.offset
            )));
        }

        indices.push(chunk.index);
    }

    Ok(indices)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::transfer::chunk::DEFAULT_CHUNK_SIZE;

    #[test]
    fn тест_битовой_карты() {
        let mut bmp = ChunkBitmap::new(10);
        assert_eq!(bmp.received_count(), 0);
        assert!(!bmp.is_complete());

        assert!(bmp.mark_received(0));
        assert!(!bmp.mark_received(0)); // дубликат
        assert!(bmp.is_received(0));
        assert!(!bmp.is_received(1));
        assert_eq!(bmp.received_count(), 1);

        for i in 1..10 {
            bmp.mark_received(i);
        }
        assert!(bmp.is_complete());
        assert_eq!(bmp.received_count(), 10);
        assert!(bmp.missing_indices().is_empty());
    }

    #[test]
    fn тест_сериализации_карты() {
        let mut bmp = ChunkBitmap::new(16);
        bmp.mark_received(0);
        bmp.mark_received(7);
        bmp.mark_received(8);
        bmp.mark_received(15);

        let bytes = bmp.as_bytes().to_vec();
        let restored = ChunkBitmap::from_bytes(16, &bytes).unwrap();
        assert_eq!(bmp, restored);
        assert_eq!(restored.received_count(), 4);
    }

    #[test]
    fn тест_формирования_и_разбора_file_request() {
        let plan = ChunkPlan::new(96 * 1024, DEFAULT_CHUNK_SIZE).unwrap(); // 3 чанка по 32кб
        let mut bmp = ChunkBitmap::new(3);
        bmp.mark_received(1); // получен только средний блок

        let tid = b"test_transfer_id";
        let req = build_file_request(tid, &plan, &bmp, None);

        assert_eq!(&req.transfer_id[..], tid);
        assert_eq!(req.missing_offsets, vec![0, 64 * 1024]);

        let missing = parse_missing_chunks(&req, &plan).unwrap();
        assert_eq!(missing, vec![0, 2]);
    }

    #[test]
    fn тест_батчирования_file_request_с_абсолютным_курсором() {
        let plan = ChunkPlan::new(10 * 32 * 1024, DEFAULT_CHUNK_SIZE).unwrap(); // 10 чанков 0..9
        let mut bmp = ChunkBitmap::new(10);
        // Получены 2, 3, 4
        bmp.mark_received(2);
        bmp.mark_received(3);
        bmp.mark_received(4);

        let tid = b"batch_test";

        // Порция 1: max_count = 2, начиная с 0
        let (req1, next1, is_last1) = build_file_request_batch(tid, &plan, &bmp, 0, 2, None);
        assert!(!is_last1);
        assert_eq!(next1, 2); // проверены 0 (missing), 1 (missing), лимит 2 достигнут
        assert_eq!(req1.missing_offsets, vec![0, 32 * 1024]);

        // Порция 2: max_count = 2, начиная с 2
        let (req2, next2, is_last2) = build_file_request_batch(tid, &plan, &bmp, next1, 2, None);
        assert!(!is_last2);
        assert_eq!(next2, 7); // 2, 3, 4 пропущены (received); 5, 6 добавлены (missing), лимит 2 достигнут
        assert_eq!(req2.missing_offsets, vec![5 * 32 * 1024, 6 * 32 * 1024]);

        // Симулируем параллельное поступление блоков 0 и 1:
        bmp.mark_received(0);
        bmp.mark_received(1);

        // Порция 3: max_count = 10, начиная с 7 (абсолютный курсор защищает от пропусков!)
        let (req3, next3, is_last3) = build_file_request_batch(tid, &plan, &bmp, next2, 10, None);
        assert!(is_last3);
        assert_eq!(next3, 10);
        assert_eq!(
            req3.missing_offsets,
            vec![7 * 32 * 1024, 8 * 32 * 1024, 9 * 32 * 1024]
        );
    }
}
