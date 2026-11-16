# Builds the libretro cores for Android and drops them into core-host's jniLibs:
#   libmgba_libretro.so      GBA  (third_party/mgba)
#   libmelondsds_libretro.so NDS  (third_party/melonds-ds; fetches its own deps with git)
# Usage: powershell -File scripts/build-core.ps1 [-Abis arm64-v8a,x86_64] [-Cores mgba,melonds] [-Force]
param(
    [string[]]$Abis = @("arm64-v8a", "x86_64"),
    [string[]]$Cores = @("mgba", "melonds"),
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
$strip = Join-Path $ndk "toolchains\llvm\prebuilt\windows-x86_64\bin\llvm-strip.exe"

$coreDefs = @{
    mgba = @{
        Src = "third_party\mgba"; Target = "mgba_libretro"; Pattern = "mgba_libretro*.so"; Out = "libmgba_libretro.so"
        Args = @(
            "-DBUILD_LIBRETRO=ON", "-DSKIP_LIBRARY=ON",
            "-DBUILD_QT=OFF", "-DBUILD_SDL=OFF", "-DBUILD_GL=OFF", "-DBUILD_GLES2=OFF", "-DBUILD_GLES3=OFF",
            "-DUSE_EPOXY=OFF", "-DUSE_FFMPEG=OFF", "-DUSE_ZLIB=OFF", "-DUSE_MINIZIP=OFF", "-DUSE_PNG=OFF",
            "-DUSE_LIBZIP=OFF", "-DUSE_SQLITE3=OFF", "-DUSE_ELF=OFF", "-DUSE_LUA=OFF", "-DUSE_JSON_C=OFF",
            "-DUSE_FREETYPE=OFF", "-DUSE_LZMA=OFF", "-DUSE_DISCORD_RPC=OFF", "-DUSE_EDITLINE=OFF",
            "-DENABLE_SCRIPTING=OFF", "-DENABLE_DEBUGGERS=OFF", "-DENABLE_GDB_STUB=OFF",
            "-DM_CORE_GB=OFF", "-DM_CORE_GBA=ON", "-DBUILD_LTO=ON")
    }
    melonds = @{
        # Software renderer (melonDS has no OpenGL ES renderer); JIT on (arm64 and x86_64).
        Src = "third_party\melonds-ds"; Target = "melondsds_libretro"; Pattern = "melondsds_libretro*.so"; Out = "libmelondsds_libretro.so"
        Args = @("-DENABLE_OPENGL=OFF", "-DENABLE_JIT=ON", "-DBUILD_TESTING=OFF")
    }
}

foreach ($p in @($ndk, $cmake, $ninja, $strip)) {
    if (-not (Test-Path $p)) { throw "Missing: $p (install NDK $ndkVersion / CMake $cmakeVersion)" }
}

foreach ($coreName in $Cores) {
    $def = $coreDefs[$coreName]
    if (-not $def) { throw "Unknown core '$coreName'" }
    $src = Join-Path $root $def.Src
    if (-not (Test-Path (Join-Path $src "CMakeLists.txt"))) { throw "Missing $src (run 'git submodule update --init')" }

    foreach ($abi in $Abis) {
        $outDir = Join-Path $root "core-host\src\main\jniLibs\$abi"
        $out = Join-Path $outDir $def.Out
        if ((Test-Path $out) -and -not $Force) { Write-Host "[$coreName/$abi] already built"; continue }

        $build = Join-Path $root "build-core\$coreName-$abi"
        New-Item -ItemType Directory -Force $build | Out-Null
        Write-Host "[$coreName/$abi] configuring"
        & $cmake -S $src -B $build -G Ninja `
            "-DCMAKE_MAKE_PROGRAM=$ninja" `
            "-DCMAKE_TOOLCHAIN_FILE=$ndk\build\cmake\android.toolchain.cmake" `
            "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-26 `
            -DCMAKE_BUILD_TYPE=Release @($def.Args)
        if ($LASTEXITCODE -ne 0) { throw "cmake configure failed for $coreName/$abi" }

        Write-Host "[$coreName/$abi] building"
        & $cmake --build $build --target $def.Target
        if ($LASTEXITCODE -ne 0) { throw "build failed for $coreName/$abi" }

        $built = Get-ChildItem $build -Recurse -Filter $def.Pattern | Select-Object -First 1
        if (-not $built) { throw "$($def.Pattern) not found in $build" }
        New-Item -ItemType Directory -Force $outDir | Out-Null
        # Android only extracts/loads native libs named lib*.so, so rename; strip debug info.
        & $strip --strip-unneeded -o $out $built.FullName
        if ($LASTEXITCODE -ne 0) { throw "strip failed for $coreName/$abi" }
        Write-Host "[$coreName/$abi] -> $out ($([math]::Round((Get-Item $out).Length / 1MB, 1)) MB)"
    }
}
