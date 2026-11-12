//! Контрольные суммы и проверка целостности.
//!
//! Разделяет проверку каждого блока (чанка) и итоговую проверку BLAKE3
//! всего файла. Проверка блока гарантирует, что в файл не запишется повреждённый
//! сетевой пакет, а итоговый хэш проверяет целостность всего файла после сборки.

use crate::transfer::errors::TransferError;
use std::io::Read;

/// Вычисляет хэш BLAKE3 для отдельного блока данных.
pub fn compute_block_hash(data: &[u8]) -> [u8; 32] {
    *blake3::hash(data).as_bytes()
}

/// Проверяет хэш принятого блока.
pub fn verify_block_hash(data: &[u8], offset: u64, expected: &[u8]) -> Result<(), TransferError> {
    if expected.len() != 32 {
        return Err(TransferError::MalformedBlock(format!(
            "длина хэша блока {} вместо 32 байт",
            expected.len()
        )));
    }

    let actual = compute_block_hash(data);
    if actual != expected {
        let mut exp_arr = [0u8; 32];
        exp_arr.copy_from_slice(expected);
        return Err(TransferError::CorruptedBlock {
            offset,
            expected: exp_arr,
            actual,
        });
    }

    Ok(())
}

/// Вычисляет поточный хэш BLAKE3 для всего файла через `Read`.
pub fn compute_file_hash<R: Read>(mut reader: R) -> Result<[u8; 32], std::io::Error> {
    let mut hasher = blake3::Hasher::new();
    let mut buf = [0u8; 64 * 1024];

    loop {
        let n = reader.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }

    Ok(*hasher.finalize().as_bytes())
}

/// Проверяет итоговый хэш BLAKE3 собранного файла.
pub fn verify_file_hash<R: Read>(reader: R, expected: &[u8]) -> Result<(), TransferError> {
    if expected.len() != 32 {
        return Err(TransferError::MalformedOffer(format!(
            "длина общего хэша {} вместо 32 байт",
            expected.len()
        )));
    }

    let actual = compute_file_hash(reader)?;
    if actual != expected {
        let mut exp_arr = [0u8; 32];
        exp_arr.copy_from_slice(expected);
        return Err(TransferError::HashMismatch {
            expected: exp_arr,
            actual,
        });
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn тест_хэша_блока() {
        let data = b"hello braidwork block";
        let hash = compute_block_hash(data);
        assert!(verify_block_hash(data, 0, &hash).is_ok());

        let corrupted_data = b"hello braidwork c1ock";
        assert!(verify_block_hash(corrupted_data, 0, &hash).is_err());
    }

    #[test]
    fn тест_хэша_файла() {
        let file_content = vec![0x42u8; 150_000];
        let hash = compute_file_hash(Cursor::new(&file_content)).unwrap();

        assert!(verify_file_hash(Cursor::new(&file_content), &hash).is_ok());

        let mut bad_content = file_content.clone();
        bad_content[1000] = 0x43;
        assert!(verify_file_hash(Cursor::new(&bad_content), &hash).is_err());
    }
}
