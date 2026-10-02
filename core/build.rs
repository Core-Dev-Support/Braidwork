//! Генерация Rust-типов из схемы протокола.
//!
//! Схема — единственный источник истины. Клиенты генерируют из неё свой код
//! на конкретную ревизию ядра; собственной копии схемы у клиентов нет (D-001).

use std::path::PathBuf;

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let proto = root.join("proto/braidwork/v1");
    let proto_file = proto.join("braidwork.proto");

    println!("cargo:rerun-if-changed=proto/braidwork/v1/braidwork.proto");
    println!("cargo:rerun-if-changed=build.rs");

    if !proto_file.exists() {
        // Падаем явно: без этого ошибка проявилась бы позже как необъяснимый
        // сбой компиляции на отсутствующих типах.
        return Err(format!("нет файла схемы: {}", proto_file.display()).into());
    }

    let mut config = prost_build::Config::new();

    // Производные трейты не нужны: клиенты получают байты через FFI, а serde
    // означал бы зависимость ради единственного потребителя, который её не просил.
    config.bytes(["."]);

    config.compile_protos(&[proto_file.as_path()], &[proto.as_path()])?;

    Ok(())
}