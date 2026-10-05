@echo off
rem Braidwork: build and test the core on Windows.
rem
rem Text in this file is ASCII on purpose. cmd.exe reads .bat in the OEM
rem codepage, and Cyrillic comments break every line that contains them:
rem the parser loses byte offsets and silently misreads the rest of the file.
rem check-android.bat was written this way from the start and never failed;
rem this file had Cyrillic and did.

rem A wrapper is needed because rustc finds the MSVC linker through variables
rem that vcvars64.bat sets. Without it, cargo fails with
rem "linker `link.exe` not found".

setlocal

set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"

if not exist "%VCVARS%" (
  echo MISSING vcvars64.bat:
  echo   %VCVARS%
  echo Install Visual Studio Build Tools with the C++ component.
  exit /b 1
)

rem The protocol needs protoc, and protoc cannot handle non-ASCII paths.
rem Its location is set explicitly for the same reason.
set "PROTOC_BIN=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.Protobuf_Microsoft.Winget.Source_8wekyb3d8bbwe\bin"

call "%VCVARS%" >nul 2>&1
if errorlevel 1 (
  echo Could not prepare the MSVC environment.
  exit /b 1
)

set "PATH=%USERPROFILE%\.cargo\bin;%PROTOC_BIN%;%PATH%"

pushd "%~dp0core" || exit /b 1

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

set "RC=%ERRORLEVEL%"
popd
exit /b %RC%
