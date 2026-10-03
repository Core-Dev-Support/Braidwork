@echo off
rem Сборка и тесты ядра на Windows.
rem
rem Нужна отдельная обёртка, потому что Rust ищет линкер MSVC через
rem переменные, которые выставляет vcvars64.bat. Без этого `cargo` падает
rem с "linker `link.exe` not found".

setlocal

set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

if not exist "%VCVARS%" (
  echo Не найден vcvars64.bat:
  echo   %VCVARS%
  echo Установи Visual Studio Build Tools с компонентой C++.
  exit /b 1
)

rem Протокол требует protoc, и он не понимает не-ASCII пути.
rem Путь к нему тоже прописывается явно.
set "PROTOC_BIN=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.Protobuf_Microsoft.Winget.Source_8wekyb3d8bbwe\bin"

call "%VCVARS%" >nul 2>&1
if errorlevel 1 (
  echo Не удалось подготовить окружение MSVC.
  exit /b 1
)

set "PATH=%USERPROFILE%\.cargo\bin;%PROTOC_BIN%;%PATH%"

cd /d "%~dp0core"

if "%1"=="test" (
  cargo test %2 %3 %4
) else if "%1"=="clippy" (
  cargo clippy --all-targets --all-features -- -D warnings
) else if "%1"=="fmt" (
  cargo fmt --all
) else if "%1"=="fmtcheck" (
  cargo fmt --all -- --check
) else (
  cargo build %2 %3 %4
)

exit /b %ERRORLEVEL%