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

cargo build --target aarch64-linux-android --no-default-features --features transport-lan,transport-ble,transport-usb,transport-softap || goto fail

set "SO=target\aarch64-linux-android\debug\libbraidwork_core.so"
if not exist "%SO%" (
  echo NOT FOUND: %SO%
  goto fail
)

echo.
echo === Exported symbols ===
"%BIN%\llvm-nm.exe" -D --defined-only "%SO%" | findstr /C:"Java_com_naua" /C:"fl_"
echo.

set "OK=1"
for %%M in (nativeInit nativeDeviceId nativeVerifySignature nativeKeysCreate nativeKeysLoad nativeKeysDeviceId) do (
  findstr /C:"Java_com_naua_1braidwork_app_BraidworkCore_%%M" "%SO%" >nul || (
    echo FAIL: no symbol for %%M
    set "OK=0"
  )
)

for %%F in (fl_init fl_device_id fl_verify_signature fl_abi_version fl_keys_blob_len fl_keys_create fl_keys_load fl_keys_device_id) do (
  findstr /C:"%%F" "%SO%" >nul || (
    echo FAIL: no C ABI symbol %%F
    set "OK=0"
  )
)

if "%OK%"=="0" (
  echo.
  echo RESULT: symbols do not match. The app will fail with UnsatisfiedLinkError.
  popd
  exit /b 1
)

echo RESULT: all symbols present, library is ready to be packaged.
popd
exit /b 0

:fail
echo BUILD FAILED
popd
exit /b 1