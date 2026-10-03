//! Генерация Rust-типов из схемы протокола.
//!
//! Схема — единственный источник истины. Клиенты генерируют из неё свой код
//! на конкретную ревизию ядра; собственной копии схемы у клиентов нет (D-001).

use std::path::{Path, PathBuf};

/// Директория с чисто латинским именем во временной папке.
///
/// Зачем: `protoc` на Windows не умеет не-ASCII пути. Если проект лежит в
/// папке с кириллицей, он получает путь в своей однобайтовой кодировке,
/// не находит файл и падает с сообщением «directory does not exist».
/// Проверено на Windows: не работает ни с ANSI, ни с текущей кодовой страницей.
///
/// Поэтому схема копируется в промежуточную директорию, `protoc` работает
/// там, а результат возвращается в OUT_DIR. Копия нужна только на время сборки.
fn ascii_staging_dir() -> Result<PathBuf, Box<dyn std::error::Error>> {
    let stamp = format!(
        "bw_proto_{}_{}",
        std::process::id(),
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0)
    );

    let dir = std::env::temp_dir().join(stamp);
    std::fs::create_dir_all(&dir)?;
    Ok(dir)
}

/// Копирует дерево схемы в директорию с латинским путём.
///
/// Копируется всё дерево, а не один файл: клиенты смогут добавить свои
/// импортируемые `.proto`, и они тоже должны попасть в сборку.
fn stage_schema(proto_root: &Path, staging: &Path) -> Result<(), Box<dyn std::error::Error>> {
    for entry in std::fs::read_dir(proto_root)? {
        let entry = entry?;
        let target = staging.join(entry.file_name());
        if entry.file_type()?.is_dir() {
            std::fs::create_dir_all(&target)?;
            stage_schema(&entry.path(), &target)?;
        } else if entry.path().extension().is_some_and(|e| e == "proto") {
            std::fs::copy(entry.path(), &target)?;
        }
    }
    Ok(())
}

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let root = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR")?);
    let proto_root = root.join("proto");

    // Каждая .proto подслеживается: правка любой из них пересобирает типы.
    for entry in walk_protos(&proto_root)? {
        println!("cargo:rerun-if-changed={}", entry.display());
    }
    println!("cargo:rerun-if-changed=build.rs");

    if !proto_root.exists() {
        // Падаем явно: без этого ошибка проявилась бы позже как необъяснимый
        // сбой компиляции на отсутствующих типах.
        return Err(format!("нет каталога схемы: {}", proto_root.display()).into());
    }

    let staging = ascii_staging_dir()?;
    stage_schema(&proto_root, &staging)?;

    let mut config = prost_build::Config::new();

    // Производные трейты не нужны: клиенты получают байты через FFI, а serde
    // означал бы зависимость ради единственного потребителя, который её не просил.
    config.bytes(["."]);

    let mut files: Vec<PathBuf> = walk_protos(&staging)?.into_iter().collect();
    files.sort();

    let result = config.compile_protos(&files, std::slice::from_ref(&staging));

    // Промежуточная копия не нужна после сборки. Убираем даже при ошибке:
    // иначе в %TEMP% копится мусор на каждом неудачном билде.
    let _ = std::fs::remove_dir_all(&staging);

    result?;

    Ok(())
}

/// Собирает список всех `.proto` в дереве.
fn walk_protos(dir: &Path) -> Result<Vec<PathBuf>, Box<dyn std::error::Error>> {
    let mut out = Vec::new();
    if !dir.exists() {
        return Ok(out);
    }
    for entry in std::fs::read_dir(dir)? {
        let entry = entry?;
        let path = entry.path();
        if entry.file_type()?.is_dir() {
            out.extend(walk_protos(&path)?);
        } else if path.extension().is_some_and(|e| e == "proto") {
            out.push(path);
        }
    }
    Ok(out)
}
