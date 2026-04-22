# Self-elevate execution policy for this session only
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  KILOProxy - Building hev-socks5-tunnel .so   " -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# ── 1. Locate NDK ─────────────────────────────────────────────────────────────
$ndkRoot = "C:\Users\Ratul\AppData\Local\Android\Sdk\ndk\27.1.12297006"

if (-not (Test-Path $ndkRoot)) {
    Write-Host "Hardcoded NDK path not found, scanning..." -ForegroundColor Yellow
    $scanBases = @(
        "$env:LOCALAPPDATA\Android\Sdk\ndk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk\ndk"
    )
    foreach ($base in $scanBases) {
        if (Test-Path $base) {
            $found = Get-ChildItem $base -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($found) { $ndkRoot = $found.FullName; break }
        }
    }
}

if (-not (Test-Path $ndkRoot)) {
    Write-Host "ERROR: NDK not found at: $ndkRoot" -ForegroundColor Red
    Write-Host "Install via: Android Studio -> SDK Manager -> SDK Tools -> NDK (Side by side)" -ForegroundColor Yellow
    Read-Host "Press Enter to exit"; exit 1
}

$ndkBuild = Join-Path $ndkRoot "ndk-build.cmd"
Write-Host "NDK: $ndkRoot" -ForegroundColor Green
Write-Host "ndk-build: $ndkBuild" -ForegroundColor Green

if (-not (Test-Path $ndkBuild)) {
    Write-Host "ERROR: ndk-build.cmd not found in NDK folder!" -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}

# ── 2. Find Git ───────────────────────────────────────────────────────────────
$git = $null
foreach ($candidate in @("git", "C:\Program Files\Git\cmd\git.exe", "C:\Program Files (x86)\Git\cmd\git.exe")) {
    try {
        $null = & $candidate --version 2>&1
        if ($LASTEXITCODE -eq 0) { $git = $candidate; break }
    } catch {}
}
if (-not $git) {
    Write-Host "ERROR: Git not found. Install from https://git-scm.com/download/win" -ForegroundColor Red
    Read-Host "Press Enter to exit"; exit 1
}
Write-Host "Git: $git" -ForegroundColor Green

# ── 3. Clone hev-socks5-tunnel (no symlink flag — let Git stub them) ──────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$buildDir  = Join-Path $scriptDir "_hev_build"
$jniDir    = Join-Path $buildDir "jni"

Write-Host ""
Write-Host "Preparing hev-socks5-tunnel source..." -ForegroundColor Cyan

if (Test-Path (Join-Path $jniDir "Android.mk")) {
    Write-Host "Source exists. Skipping clone (delete _hev_build\ to force fresh clone)." -ForegroundColor Yellow
} else {
    New-Item -ItemType Directory -Path $buildDir -Force | Out-Null
    Write-Host "Cloning... (this downloads ~5MB)" -ForegroundColor Cyan

    # Do NOT pass core.symlinks=true — that requires elevated privilege on Windows
    # and causes the clone to fail. We resolve symlink stubs manually below.
    & $git clone --recursive --depth=1 `
        https://github.com/heiher/hev-socks5-tunnel.git "$jniDir"

    if ($LASTEXITCODE -ne 0) {
        Write-Host "ERROR: git clone failed (exit $LASTEXITCODE)" -ForegroundColor Red
        Read-Host "Press Enter to exit"; exit 1
    }
    Write-Host "Cloned OK." -ForegroundColor Green
}

# ── 4. Write Application.mk ───────────────────────────────────────────────────
$appMkContent = @"
APP_ABI := arm64-v8a armeabi-v7a x86_64 x86
APP_PLATFORM := android-24
APP_OPTIM := release
APP_STL := none
"@
Set-Content -Path (Join-Path $jniDir "Application.mk") -Value $appMkContent -Encoding UTF8
Write-Host "Application.mk written." -ForegroundColor Green

# ── 4b. Resolve Windows symlink stubs ─────────────────────────────────────────
# When Git lacks symlink privilege it writes stub files whose entire content is
# the relative target path (e.g. "../src/lib/object/hev-object.h").
# The C compiler reads that path as source and fails. We copy the real file over
# every stub we find, recursing through the whole jni tree.
Write-Host ""
Write-Host "Resolving symlink stubs..." -ForegroundColor Cyan

$resolved = 0
$missed   = 0

Get-ChildItem -Path $jniDir -Recurse -File | ForEach-Object {
    $file = $_
    # Only inspect small files — real headers are never < 5 bytes
    if ($file.Length -gt 200) { return }

    try {
        $raw = (Get-Content $file.FullName -Raw -Encoding UTF8).Trim()
    } catch { return }

    # A symlink stub is a single token matching a relative path to a .h or .c file.
    # It has no spaces, no C keywords, and looks like  ../some/path/file.h
    if ($raw -notmatch '^\.\.[\\/][\w/\\.+-]+\.(h|c)$') { return }

    # Resolve relative to the directory containing the stub
    $dir    = $file.DirectoryName
    $target = [System.IO.Path]::GetFullPath([System.IO.Path]::Combine($dir, $raw.Replace('/', '\')))

    if (Test-Path $target) {
        Copy-Item $target $file.FullName -Force
        Write-Host "  Resolved  $($file.FullName -replace [regex]::Escape($jniDir), '')" -ForegroundColor DarkGray
        $script:resolved++
    } else {
        Write-Host "  MISS      $($file.Name)  ->  $raw  (target not found)" -ForegroundColor Yellow
        $script:missed++
    }
}

Write-Host "Symlink stubs resolved: $resolved  |  missed: $missed" -ForegroundColor Green

# ── 5. Run ndk-build ──────────────────────────────────────────────────────────
$objDir  = Join-Path $buildDir "obj"
$libsDir = Join-Path $buildDir "libs"

Write-Host ""
Write-Host "Building for arm64-v8a, armeabi-v7a, x86_64, x86..." -ForegroundColor Cyan
Write-Host "(First build takes 2-5 minutes)" -ForegroundColor Yellow
Write-Host ""

$ndkArgs = @(
    "NDK_PROJECT_PATH=$jniDir",
    "APP_BUILD_SCRIPT=$jniDir\Android.mk",
    "NDK_APPLICATION_MK=$jniDir\Application.mk",
    "NDK_OUT=$objDir",
    "NDK_LIBS_OUT=$libsDir",
    "-j4"
)

& $ndkBuild @ndkArgs
$rc = $LASTEXITCODE

if ($rc -ne 0) {
    Write-Host ""
    Write-Host "ERROR: ndk-build failed (exit code $rc)" -ForegroundColor Red
    Write-Host "Check the output above for the actual error." -ForegroundColor Yellow
    Read-Host "Press Enter to exit"; exit 1
}
Write-Host ""
Write-Host "Build succeeded!" -ForegroundColor Green

# ── 6. Copy .so files into jniLibs ────────────────────────────────────────────
Write-Host ""
Write-Host "Copying .so files..." -ForegroundColor Cyan
$jniLibsBase = Join-Path $scriptDir "app\src\main\jniLibs"
$allOk = $true

foreach ($abi in @("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
    $src  = Join-Path $libsDir "$abi\libhev-socks5-tunnel.so"
    $dest = Join-Path $jniLibsBase "$abi\libhev-socks5-tunnel.so"
    New-Item -ItemType Directory -Path (Split-Path $dest) -Force | Out-Null
    if (Test-Path $src) {
        Copy-Item $src $dest -Force
        $size = [math]::Round((Get-Item $src).Length / 1KB)
        Write-Host "  OK  $abi  ($size KB)" -ForegroundColor Green
    } else {
        Write-Host "  MISS $abi  (not found: $src)" -ForegroundColor Yellow
        $allOk = $false
    }
}

# ── Done ──────────────────────────────────────────────────────────────────────
Write-Host ""
if ($allOk) {
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  ALL DONE! .so files placed in jniLibs/        " -ForegroundColor Green
    Write-Host "  Now: Android Studio -> Build -> Make Project   " -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
} else {
    Write-Host "================================================" -ForegroundColor Yellow
    Write-Host "  Done, but some ABIs were missing.              " -ForegroundColor Yellow
    Write-Host "  App will still work on arm64 (most phones).    " -ForegroundColor Yellow
    Write-Host "================================================" -ForegroundColor Yellow
}
Write-Host ""
Read-Host "Press Enter to close"
