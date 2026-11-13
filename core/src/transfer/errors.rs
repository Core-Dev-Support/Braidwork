//! Ошибки файлового движка передачи.

use thiserror::Error;

#[derive(Error, Debug)]
pub enum TransferError {
    #[error("ошибка ввода-вывода: {0}")]
    Io(#[from] std::io::Error),

    #[error("ошибка декодирования protobuf: {0}")]
    Decode(#[from] prost::DecodeError),

    #[error("некорректное предложение файла (offer): {0}")]
    MalformedOffer(String),

    #[error("некорректный блок файла: {0}")]
    MalformedBlock(String),

    #[error("некорректный размер чанка: {0}")]
    InvalidChunkSize(u32),

    #[error("повреждённый блок по смещению {offset}: ожидался хэш {expected:02x?}, получен {actual:02x?}")]
    CorruptedBlock {
        offset: u64,
        expected: [u8; 32],
        actual: [u8; 32],
    },

    #[error("хэш собранного файла не совпадает: ожидался {expected:02x?}, получен {actual:02x?}")]
    HashMismatch {
        expected: [u8; 32],
        actual: [u8; 32],
    },

    #[error(
        "блок выходит за границы файла: offset={offset}, len={length}, total_size={total_size}"
    )]
    OutOfBounds {
        offset: u64,
        length: usize,
        total_size: u64,
    },

    #[error(
        "несовпадение идентификатора передачи: ожидался {expected:02x?}, получен {actual:02x?}"
    )]
    TransferMismatch { expected: Vec<u8>, actual: Vec<u8> },

    #[error("недопустимое состояние передачи: {0}")]
    InvalidState(String),

    #[error("передача не завершена: получено {received_chunks} из {total_chunks} блоков")]
    Incomplete {
        received_chunks: usize,
        total_chunks: usize,
    },

    #[error("передача была отменена")]
    Cancelled,
}
