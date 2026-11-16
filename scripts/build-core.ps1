# Builds mGBA's libretro core for Android and drops it into core-host's jniLibs.
# Usage: powershell -File scripts/build-core.ps1 [-Abis arm64-v8a,x86_64] [-Force]
param(
    [string[]]$Abis = @("arm64-v8a", "x86_64"),
    [switch]$Force
)
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA\Android\Sdk" }
$ndkVersion = "27.2.12479018"
$cmakeVersion = "3.22.1"
$ndk = Join-Path $sdk "ndk\$ndkVersion"
$cmake = Join-Path $sdk "cmake\$cmakeVersion\bin\cmake.exe"
$ninja = Join-Path $sdk "cmake\$cmakeVersion\bin\ninja.exe"
$src = Join-Path $root "third_party\mgba"

foreach ($p in @($ndk, $cmake, $ninja, (Join-Path $src "CMakeLists.txt"))) {
    if (-not (Test-Path $p)) { throw "Missing: $p (install NDK $ndkVersion / CMake $cmakeVersion, and run 'git submodule update --init')" }
}

foreach ($abi in $Abis) {
    $outDir = Join-Path $root "core-host\src\main\jniLibs\$abi"
    $out = Join-Path $outDir "libmgba_libretro.so"
    if ((Test-Path $out) -and -not $Force) { Write-Host "[$abi] core already built"; continue }

    $build = Join-Path $root "build-core\$abi"
    New-Item -ItemType Directory -Force $build | Out-Null
    Write-Host "[$abi] configuring mGBA libretro core"
    & $cmake -S $src -B $build -G Ninja `
        "-DCMAKE_MAKE_PROGRAM=$ninja" `
        "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
        "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-26 `
        -DCMAKE_BUILD_TYPE=Release `
        -DBUILD_LIBRETRO=ON -DSKIP_LIBRARY=ON `
        -DBUILD_QT=OFF -DBUILD_SDL=OFF -DBUILD_GL=OFF -DBUILD_GLES2=OFF -DBUILD_GLES3=OFF `
        -DUSE_EPOXY=OFF -DUSE_FFMPEG=OFF -DUSE_ZLIB=OFF -DUSE_MINIZIP=OFF -DUSE_PNG=OFF `
        -DUSE_LIBZIP=OFF -DUSE_SQLITE3=OFF -DUSE_ELF=OFF -DUSE_LUA=OFF -DUSE_JSON_C=OFF `
        -DUSE_FREETYPE=OFF -DUSE_LZMA=OFF -DUSE_DISCORD_RPC=OFF -DUSE_EDITLINE=OFF `
        -DENABLE_SCRIPTING=OFF -DENABLE_DEBUGGERS=OFF -DENABLE_GDB_STUB=OFF `
        -DM_CORE_GB=OFF -DM_CORE_GBA=ON -DBUILD_LTO=ON
    if ($LASTEXITCODE -ne 0) { throw "cmake configure failed for $abi" }

    Write-Host "[$abi] building"
    & $cmake --build $build --target mgba_libretro
    if ($LASTEXITCODE -ne 0) { throw "build failed for $abi" }

    $built = Get-ChildItem $build -Recurse -Filter "mgba_libretro*.so" | Select-Object -First 1
    if (-not $built) { throw "mgba_libretro.so not found in $build" }
    New-Item -ItemType Directory -Force $outDir | Out-Null
    # Android only extracts/loads native libs named lib*.so, so rename.
    Copy-Item $built.FullName $out -Force
    Write-Host "[$abi] -> $out"
}
