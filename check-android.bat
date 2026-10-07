@echo off
rem Braidwork: local Android native check.
rem
rem Text in this file is ASCII on purpose. cmd.exe reads .bat in the OEM
rem codepage, and Cyrillic comments break every line that contains them.
rem
rem Why this script exists: errors like UnsatisfiedLinkError were found on a
rem phone after 40 minutes of CI. A local check takes a minute and catches
rem them before pushing.
rem
rem What it does:
rem   1. builds the core for aarch64-linux-android;
rem   2. dumps exported symbols;
rem   3. verifies that every name the JVM looks for exists in the library.
rem
rem JNI rule: an underscore in a class or package name is escaped as _1.
rem That is why naua_braidwork is searched as naua_1braidwork.

setlocal

set "VCVARS=C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
set "NDK=C:\Android\Sdk\ndk\27.2.12479018"
set "BIN=%NDK%\toolchains\llvm\prebuilt\windows-x86_64\bin"
set "PROTOC_BIN=%LOCALAPPDATA%\Microsoft\WinGet\Packages\Google.Protobuf_Microsoft.Winget.Source_8wekyb3d8bbwe\bin"

call "%VCVARS%" >nul 2>&1
set "PATH=%USERPROFILE%\.cargo\bin;%PROTOC_BIN%;%PATH%"
set "CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=%BIN%\aarch64-linux-android21-clang.cmd"
set "CC_aarch64_linux_android=%BIN%\aarch64-linux-android21-clang.cmd"
set "AR_aarch64_linux_android=%BIN%\llvm-ar.exe"

pushd "%~dp0core" || exit /b 1

rem Where cargo puts artifacts. Honour CARGO_TARGET_DIR because this script
rem also has to work from a path cargo can link at: ld.lld cannot open a file
rem under a non-ASCII directory, so the build is run through a junction and the
rem target directory is kept at an ASCII path.
if defined CARGO_TARGET_DIR (
  set "TARGET_DIR=%CARGO_TARGET_DIR%"
) else (
  set "TARGET_DIR=%CD%\target"
)

rem Delete the library before building. Otherwise a failed build leaves the
rem previous one in place, the "if not exist" check passes, and the symbol dump
rem describes a library that no longer matches the sources. That is a green
rem result for a stale binary: it is how a real defect reaches a phone as
rem UnsatisfiedLinkError after forty minutes of CI.
set "SO=%TARGET_DIR%\aarch64-linux-android\debug\libbraidwork_core.so"
if exist "%SO%" del /q "%SO%"

cargo build --target aarch64-linux-android --no-default-features --features transport-lan,transport-ble,transport-usb,transport-softap || goto fail

if not exist "%SO%" (
  echo NOT FOUND: %SO%
  goto fail
)

echo.
echo === Exported symbols ===
"%BIN%\llvm-nm.exe" -D --defined-only "%SO%" | findstr /C:"Java_com_naua" /C:"fl_"
echo.

set "OK=1"
for %%M in (nativeInit nativeDeviceId nativeVerifySignature nativeKeysCreate nativeKeysLoad nativeKeysDeviceId nativeKeysFingerprint nativeTransportStart nativeTransportPoll nativeTransportPort nativeTransportStop nativeTransportFree nativeTransportAlive nativeFrameEncode) do (
  findstr /C:"Java_com_naua_1braidwork_app_BraidworkCore_%%M" "%SO%" >nul || (
    echo FAIL: no symbol for %%M
    set "OK=0"
  )
)

for %%F in (fl_init fl_device_id fl_verify_signature fl_abi_version fl_free fl_keys_blob_len fl_keys_create fl_keys_load fl_keys_device_id fl_keys_fingerprint fl_session_create fl_session_step fl_session_ready fl_session_finish fl_session_seal fl_session_open fl_session_free fl_hello_build fl_hello_verify fl_frame_encode fl_transport_lan_start fl_transport_poll fl_transport_connect fl_transport_send fl_transport_conn_close fl_transport_port fl_transport_stop fl_transport_free fl_transport_event_size) do (
  findstr /C:"%%F" "%SO%" >nul || (
    echo FAIL: no C ABI symbol %%F
    set "OK=0"
  )
)

rem The header is hand-written and only Rust gets compiled, so a typo in a
rem prototype or a missing declaration would survive the whole build and only
rem surface on a phone as UnsatisfiedLinkError. Both lists are compared here
rem and again in CI.
"%BIN%\llvm-nm.exe" -D --defined-only "%SO%" | findstr /R /C:" T fl_" > "%TEMP%\bw_in_lib.txt"
findstr /R /C:"fl_[a-z0-9_]*[ ]*(" "include\braidwork_core.h" > "%TEMP%\bw_in_header.txt"

for /f "tokens=1" %%L in ('"%BIN%\llvm-nm.exe" -D --defined-only "%SO%" ^| findstr /R /C:" T fl_"') do (
  echo %%L>> "%TEMP%\bw_names.txt"
)
if exist "%TEMP%\bw_names.txt" del "%TEMP%\bw_names.txt"

for /f "tokens=3" %%L in ('"%BIN%\llvm-nm.exe" -D --defined-only "%SO%" ^| findstr /R /C:" T fl_"') do (
  findstr /C:"%%L" "include\braidwork_core.h" >nul || (
    echo FAIL: %%L is exported but not declared in braidwork_core.h
    set "OK=0"
  )
)

if "%OK%"=="0" (
  echo.
  echo RESULT: symbols do not match. The app will fail with UnsatisfiedLinkError.
  popd
  exit /b 1
)

echo RESULT: all symbols present and declared in the header.
popd
exit /b 0

:fail
echo BUILD FAILED
popd
exit /b 1