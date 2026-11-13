//! C ABI для управления передачей файлов через Rust Transfer Engine (`FileSender` и `FileReceiver`).

use crate::guard;
use crate::registry::Registry;
use crate::transfer::{BlockWriteResult, FileReceiver, FileSender, TransferError};
use crate::{
    FlHandle, FlStatus, FL_ERR_CRYPTO, FL_ERR_INTERNAL, FL_ERR_INVALID_ARG, FL_ERR_STORAGE, FL_OK,
};
use prost::Message;
use std::path::PathBuf;
use std::sync::OnceLock;

static SENDERS: OnceLock<Registry<FileSender>> = OnceLock::new();
static RECEIVERS: OnceLock<Registry<FileReceiver>> = OnceLock::new();

fn senders() -> &'static Registry<FileSender> {
    SENDERS.get_or_init(Registry::new)
}

fn receivers() -> &'static Registry<FileReceiver> {
    RECEIVERS.get_or_init(Registry::new)
}

fn status_from_transfer_err(err: &TransferError) -> FlStatus {
    match err {
        TransferError::Io(_) => FL_ERR_STORAGE,
        TransferError::Decode(_) => FL_ERR_INVALID_ARG,
        TransferError::MalformedOffer(_) => FL_ERR_INVALID_ARG,
        TransferError::MalformedBlock(_) => FL_ERR_INVALID_ARG,
        TransferError::InvalidChunkSize(_) => FL_ERR_INVALID_ARG,
        TransferError::CorruptedBlock { .. } => FL_ERR_CRYPTO,
        TransferError::HashMismatch { .. } => FL_ERR_CRYPTO,
        TransferError::OutOfBounds { .. } => FL_ERR_INVALID_ARG,
        TransferError::TransferMismatch { .. } => FL_ERR_INVALID_ARG,
        TransferError::InvalidState(_) => FL_ERR_INVALID_ARG,
        TransferError::Incomplete { .. } => FL_ERR_INVALID_ARG,
        TransferError::Cancelled => FL_ERR_INTERNAL,
    }
}

unsafe fn copy_out(data: &[u8], out: *mut u8, out_len: *mut usize) -> FlStatus {
    if out_len.is_null() {
        return FL_ERR_INVALID_ARG;
    }
    let needed = data.len();
    if needed > 0 {
        if out.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        let cap = std::slice::from_raw_parts_mut(out, needed);
        cap.copy_from_slice(data);
    }
    *out_len = needed;
    FL_OK
}

/// Создаёт отправитель файла `FileSender` в ядре.
///
/// # Safety
/// Все указатели на буферы должны быть валидными.
#[no_mangle]
pub unsafe extern "C" fn fl_file_sender_create(
    transfer_id: *const u8,
    transfer_id_len: usize,
    file_name: *const u8,
    file_name_len: usize,
    total_size: u64,
    total_hash: *const u8,
    total_hash_len: usize,
    chunk_size: u32,
    out_handle: *mut u64,
) -> FlStatus {
    guard(|| {
        if transfer_id.is_null()
            || transfer_id_len == 0
            || file_name.is_null()
            || file_name_len == 0
            || total_hash.is_null()
            || total_hash_len != crate::HASH_LEN
            || out_handle.is_null()
        {
            return FL_ERR_INVALID_ARG;
        }

        let tid_slice = std::slice::from_raw_parts(transfer_id, transfer_id_len);
        let name_bytes = std::slice::from_raw_parts(file_name, file_name_len);
        let name_str = match std::str::from_utf8(name_bytes) {
            Ok(s) => s.to_string(),
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let mut hash_arr = [0u8; 32];
        hash_arr.copy_from_slice(std::slice::from_raw_parts(total_hash, 32));

        let sender = match FileSender::new(
            tid_slice.to_vec(),
            name_str,
            total_size,
            hash_arr,
            chunk_size,
        ) {
            Ok(s) => s,
            Err(e) => return status_from_transfer_err(&e),
        };

        let handle = senders().insert(sender);
        *out_handle = handle.0;
        FL_OK
    })
}

/// Запрашивает следующий чанк для передачи у `FileSender`.
///
/// `out_is_done` получает 1, если все чанки отправлены и очередь повторов пуста, иначе 0.
///
/// # Safety
/// Все выходные указатели должны быть валидными.
#[no_mangle]
pub unsafe extern "C" fn fl_file_sender_next_chunk(
    handle: u64,
    out_offset: *mut u64,
    out_length: *mut u32,
    out_chunk_idx: *mut usize,
    out_is_done: *mut u32,
) -> FlStatus {
    guard(|| {
        if out_offset.is_null()
            || out_length.is_null()
            || out_chunk_idx.is_null()
            || out_is_done.is_null()
        {
            return FL_ERR_INVALID_ARG;
        }

        let h = FlHandle(handle);
        senders()
            .with(h, |sender| {
                if let Some(info) = sender.peek_next_chunk() {
                    *out_offset = info.offset;
                    *out_length = info.length;
                    *out_chunk_idx = info.index;
                    *out_is_done = 0;
                } else {
                    *out_is_done = 1;
                }
                FL_OK
            })
            .unwrap_or(FL_ERR_INVALID_ARG)
    })
}

/// Собирает зашифрованный/хэшированный `FileBlock` кадр для указанного чанка и продвигает очередь.
///
/// # Safety
/// `data` указывает на буфер размера `data_len`. `out_data` и `out_len` следуют протоколу `copy_out`.
#[no_mangle]
pub unsafe extern "C" fn fl_file_sender_build_block(
    handle: u64,
    offset: u64,
    data: *const u8,
    data_len: usize,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_data.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }
        if data.is_null() && data_len > 0 {
            return FL_ERR_INVALID_ARG;
        }

        let data_slice = if data_len == 0 {
            &[][..]
        } else {
            std::slice::from_raw_parts(data, data_len)
        };

        let h = FlHandle(handle);
        let block_res: Option<Result<crate::pb::FileBlock, TransferError>> =
            senders().with(h, |sender| {
                let block = sender.build_block(offset, data_slice)?;
                sender.advance_next_chunk();
                Ok(block)
            });

        let block = match block_res {
            Some(Ok(b)) => b,
            Some(Err(e)) => return status_from_transfer_err(&e),
            None => return FL_ERR_INVALID_ARG,
        };

        let frame =
            match crate::frame::encode(crate::pb::MessageKind::KindFileBlock as i32, 0, &block) {
                Ok(f) => f,
                Err(_) => return crate::FL_ERR_INTERNAL,
            };

        copy_out(&frame, out_data, out_len)
    })
}

/// Передаёт полученный `FileRequest` в `FileSender` для повторной отправки потерянных блоков.
///
/// # Safety
/// `request_body` указывает на `request_body_len` байт тела сообщения.
#[no_mangle]
pub unsafe extern "C" fn fl_file_sender_handle_request(
    handle: u64,
    request_body: *const u8,
    request_body_len: usize,
    out_retransmit_count: *mut usize,
) -> FlStatus {
    guard(|| {
        if request_body.is_null() || out_retransmit_count.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let bytes = std::slice::from_raw_parts(request_body, request_body_len);
        let req = match crate::pb::FileRequest::decode(bytes) {
            Ok(r) => r,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let h = FlHandle(handle);
        let res = senders().with(h, |sender| sender.handle_request(&req));

        match res {
            Some(Ok(count)) => {
                *out_retransmit_count = count;
                FL_OK
            }
            Some(Err(e)) => status_from_transfer_err(&e),
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Освобождает объект `FileSender`.
///
/// # Safety
/// `handle` должен указывать на ранее созданный отправитель.
#[no_mangle]
pub unsafe extern "C" fn fl_file_sender_free(handle: u64) -> FlStatus {
    guard(|| {
        let h = FlHandle(handle);
        if senders().remove(h) {
            FL_OK
        } else {
            FL_ERR_INVALID_ARG
        }
    })
}

/// Создаёт получатель файла `FileReceiver` на основе тела `FileOffer`.
/// Поддерживает автоматический Resume, если передан `state_path` и на диске есть сохранённое состояние.
///
/// # Safety
/// Все указатели на пути и тела должны быть валидными строками UTF-8.
#[no_mangle]
pub unsafe extern "C" fn fl_file_receiver_create(
    offer_body: *const u8,
    offer_body_len: usize,
    temp_path: *const u8,
    temp_path_len: usize,
    dest_path: *const u8,
    dest_path_len: usize,
    state_path: *const u8,
    state_path_len: usize,
    chunk_size: u32,
    out_handle: *mut u64,
) -> FlStatus {
    guard(|| {
        if offer_body.is_null()
            || temp_path.is_null()
            || temp_path_len == 0
            || dest_path.is_null()
            || dest_path_len == 0
            || out_handle.is_null()
        {
            return FL_ERR_INVALID_ARG;
        }

        let bytes = std::slice::from_raw_parts(offer_body, offer_body_len);
        let offer = match crate::pb::FileOffer::decode(bytes) {
            Ok(o) => o,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let temp_str =
            match std::str::from_utf8(std::slice::from_raw_parts(temp_path, temp_path_len)) {
                Ok(s) => s,
                Err(_) => return FL_ERR_INVALID_ARG,
            };
        let dest_str =
            match std::str::from_utf8(std::slice::from_raw_parts(dest_path, dest_path_len)) {
                Ok(s) => s,
                Err(_) => return FL_ERR_INVALID_ARG,
            };

        let state_opt = if !state_path.is_null() && state_path_len > 0 {
            match std::str::from_utf8(std::slice::from_raw_parts(state_path, state_path_len)) {
                Ok(s) => Some(PathBuf::from(s)),
                Err(_) => return FL_ERR_INVALID_ARG,
            }
        } else {
            None
        };

        let receiver = match FileReceiver::from_offer(
            &offer,
            PathBuf::from(temp_str),
            PathBuf::from(dest_str),
            state_opt,
            chunk_size,
        ) {
            Ok(r) => r,
            Err(e) => return status_from_transfer_err(&e),
        };

        let handle = receivers().insert(receiver);
        *out_handle = handle.0;
        FL_OK
    })
}

/// Принимает, валидирует по BLAKE3 и записывает блок файла `FileBlock`.
///
/// `out_result` получает: 0 = Accepted, 1 = Duplicate, 2 = Complete.
///
/// # Safety
/// `block_body` указывает на `block_body_len` байт тела `FileBlock`.
#[no_mangle]
pub unsafe extern "C" fn fl_file_receiver_write_block(
    handle: u64,
    block_body: *const u8,
    block_body_len: usize,
    out_chunk_idx: *mut usize,
    out_result: *mut u32,
) -> FlStatus {
    guard(|| {
        if block_body.is_null() || out_chunk_idx.is_null() || out_result.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let bytes = std::slice::from_raw_parts(block_body, block_body_len);
        let block = match crate::pb::FileBlock::decode(bytes) {
            Ok(b) => b,
            Err(_) => return FL_ERR_INVALID_ARG,
        };

        let h = FlHandle(handle);
        let res = receivers().with(h, |receiver| {
            let chunk = receiver.plan().chunk_for_offset(block.offset);
            let write_res = receiver.write_block(&block)?;
            Ok((chunk.map(|c| c.index).unwrap_or(0), write_res))
        });

        match res {
            Some(Ok((idx, write_res))) => {
                *out_chunk_idx = idx;
                *out_result = match write_res {
                    BlockWriteResult::Accepted { .. } => 0,
                    BlockWriteResult::Duplicate { .. } => 1,
                    BlockWriteResult::Complete => 2,
                };
                FL_OK
            }
            Some(Err(e)) => status_from_transfer_err(&e),
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Формирует кадр `FileRequest` со списком недостающих смещений блоков для докачки.
///
/// # Safety
/// `out_data` и `out_len` удовлетворяют протоколу `copy_out`.
#[no_mangle]
pub unsafe extern "C" fn fl_file_receiver_build_request(
    handle: u64,
    out_data: *mut u8,
    out_len: *mut usize,
) -> FlStatus {
    guard(|| {
        if out_data.is_null() || out_len.is_null() {
            return FL_ERR_INVALID_ARG;
        }

        let h = FlHandle(handle);
        let req = receivers()
            .with(h, |receiver| receiver.build_request())
            .ok_or(FL_ERR_INVALID_ARG);

        let req = match req {
            Ok(r) => r,
            Err(code) => return code,
        };

        let frame =
            match crate::frame::encode(crate::pb::MessageKind::KindFileRequest as i32, 0, &req) {
                Ok(f) => f,
                Err(_) => return crate::FL_ERR_INTERNAL,
            };

        copy_out(&frame, out_data, out_len)
    })
}

/// Финализирует приём: проверяет полный BLAKE3 хэш файла и атомарно перемещает его на постоянное место.
///
/// # Safety
/// `handle` должен указывать на активного получателя.
#[no_mangle]
pub unsafe extern "C" fn fl_file_receiver_finish(handle: u64) -> FlStatus {
    guard(|| {
        let h = FlHandle(handle);
        let res = receivers().with(h, |receiver| receiver.finish_and_verify());

        match res {
            Some(Ok(_)) => FL_OK,
            Some(Err(e)) => status_from_transfer_err(&e),
            None => FL_ERR_INVALID_ARG,
        }
    })
}

/// Освобождает объект `FileReceiver` (при отмене удаляет временные файлы).
///
/// # Safety
/// `handle` должен указывать на ранее созданный получатель.
#[no_mangle]
pub unsafe extern "C" fn fl_file_receiver_free(handle: u64) -> FlStatus {
    guard(|| {
        let h = FlHandle(handle);
        if receivers().remove(h) {
            FL_OK
        } else {
            FL_ERR_INVALID_ARG
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::pb::MessageKind;
    use crate::transfer::chunk::DEFAULT_CHUNK_SIZE;

    #[test]
    fn c_abi_file_sender_and_receiver_flow() {
        let tid = b"abi_tid_test";
        let file_name = b"abi_file.bin";
        let content = vec![0x55u8; 64 * 1024]; // 2 блока по 32 КБ
        let total_hash = *blake3::hash(&content).as_bytes();

        let mut sender_h = 0u64;
        let s_code = unsafe {
            fl_file_sender_create(
                tid.as_ptr(),
                tid.len(),
                file_name.as_ptr(),
                file_name.len(),
                content.len() as u64,
                total_hash.as_ptr(),
                total_hash.len(),
                DEFAULT_CHUNK_SIZE,
                &mut sender_h as *mut u64,
            )
        };
        assert_eq!(s_code, FL_OK);

        // Формируем предложение файла через fl_file_offer_build
        let mut offer_frame = vec![0u8; 4096];
        let mut offer_frame_len = 0usize;
        assert_eq!(
            unsafe {
                crate::fl_file_offer_build(
                    tid.as_ptr(),
                    tid.len(),
                    file_name.as_ptr(),
                    file_name.len(),
                    content.len() as u64,
                    total_hash.as_ptr(),
                    total_hash.len(),
                    offer_frame.as_mut_ptr(),
                    &mut offer_frame_len as *mut usize,
                )
            },
            FL_OK
        );
        offer_frame.truncate(offer_frame_len);

        let (hdr, offer_body, _) = crate::frame::split(&offer_frame).unwrap();
        assert_eq!(hdr.kind, MessageKind::KindFileOffer as i32);

        let dir = std::env::temp_dir().join("bw_abi_transfer_test");
        let _ = std::fs::create_dir_all(&dir);
        let temp_path = dir.join(".bw_abi.part");
        let dest_path = dir.join("abi_done.bin");

        let temp_str = temp_path.to_str().unwrap().as_bytes();
        let dest_str = dest_path.to_str().unwrap().as_bytes();

        let mut receiver_h = 0u64;
        let r_code = unsafe {
            fl_file_receiver_create(
                offer_body.as_ptr(),
                offer_body.len(),
                temp_str.as_ptr(),
                temp_str.len(),
                dest_str.as_ptr(),
                dest_str.len(),
                std::ptr::null(),
                0,
                DEFAULT_CHUNK_SIZE,
                &mut receiver_h as *mut u64,
            )
        };
        assert_eq!(r_code, FL_OK);

        // Передаём блоки
        loop {
            let mut offset = 0u64;
            let mut length = 0u32;
            let mut chunk_idx = 0usize;
            let mut is_done = 0u32;

            assert_eq!(
                unsafe {
                    fl_file_sender_next_chunk(
                        sender_h,
                        &mut offset,
                        &mut length,
                        &mut chunk_idx,
                        &mut is_done,
                    )
                },
                FL_OK
            );

            if is_done == 1 {
                break;
            }

            let slice = &content[offset as usize..(offset + length as u64) as usize];
            let mut block_frame = vec![0u8; DEFAULT_CHUNK_SIZE as usize + 1024];
            let mut block_frame_len = 0usize;

            assert_eq!(
                unsafe {
                    fl_file_sender_build_block(
                        sender_h,
                        offset,
                        slice.as_ptr(),
                        slice.len(),
                        block_frame.as_mut_ptr(),
                        &mut block_frame_len as *mut usize,
                    )
                },
                FL_OK
            );
            block_frame.truncate(block_frame_len);

            let (b_hdr, block_body, _) = crate::frame::split(&block_frame).unwrap();
            assert_eq!(b_hdr.kind, MessageKind::KindFileBlock as i32);

            let mut out_idx = 0usize;
            let mut out_res = 0u32;
            assert_eq!(
                unsafe {
                    fl_file_receiver_write_block(
                        receiver_h,
                        block_body.as_ptr(),
                        block_body.len(),
                        &mut out_idx,
                        &mut out_res,
                    )
                },
                FL_OK
            );
            assert_eq!(out_idx, chunk_idx);
        }

        // Завершаем и сверяем хэш
        assert_eq!(unsafe { fl_file_receiver_finish(receiver_h) }, FL_OK);
        assert!(dest_path.exists());
        assert_eq!(std::fs::read(&dest_path).unwrap(), content);

        assert_eq!(unsafe { fl_file_sender_free(sender_h) }, FL_OK);
        assert_eq!(unsafe { fl_file_receiver_free(receiver_h) }, FL_OK);

        let _ = std::fs::remove_file(dest_path);
        let _ = std::fs::remove_dir_all(dir);
    }
}
