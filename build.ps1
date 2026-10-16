<#
.SYNOPSIS
    Build script for WebBrowser - Rust + Android NDK + Gradle.

.DESCRIPTION
    Compiles the Rust core library via cargo-ndk for one or more Android ABIs,
    copies the resulting .so files into the jniLibs directory, then invokes
    Gradle to assemble the APK.

.PARAMETER Release
    Build in release mode (optimized, minified). Default: debug.

.PARAMETER Target
    Comma-separated list of ABIs to build.
    Valid values: arm64-v8a, armeabi-v7a, x86_64
    Default: all three (release) or arm64-v8a only (debug).

.PARAMETER Clean
    Run cargo clean before building.

.EXAMPLE
    .\build.ps1                          # debug build, arm64-v8a only
    .\build.ps1 -Release                 # release build, all ABIs
    .\build.ps1 -Release -Target arm64-v8a,x86_64
    .\build.ps1 -Clean -Release
#>

[CmdletBinding()]
param(
    [switch]$Release,
    [string]$Target = "",
    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# --- Output helpers (Pure ASCII) ---
function Info  ([string]$msg) { Write-Host "  $msg"        -ForegroundColor Cyan    }
function Step  ([string]$msg) { Write-Host "`n>> $msg"     -ForegroundColor Blue    }
function OK    ([string]$msg) { Write-Host "  [OK] $msg"   -ForegroundColor Green   }
function Warn  ([string]$msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow  }
function Fail  ([string]$msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red; exit 1 }

# --- Banner ---
Write-Host ""
Write-Host "============================================" -ForegroundColor Blue
Write-Host "   QCBrowser - Rust + Android Build         " -ForegroundColor Blue
Write-Host "============================================" -ForegroundColor Blue
$buildMode = if ($Release) { "RELEASE" } else { "DEBUG" }
Info "Mode : $buildMode"

# --- Resolve project root ---
$ScriptDir  = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Join-Path $ScriptDir "android"

# --- Tool checks ---
Step "Checking required tools"

function Require-Command ([string]$cmd, [string]$installHint) {
    if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
        Warn "$cmd not found. $installHint"
        return $false
    }
    OK "$cmd found"
    return $true
}

$hasRustup = Require-Command "rustup" "Install from https://rustup.rs"
$hasCargo  = Require-Command "cargo"  "Install from https://rustup.rs"
$hasJava   = Require-Command "java"   "Install JDK 17+ from https://adoptium.net"

if (-not $hasRustup -or -not $hasCargo) {
    Fail "Rust toolchain is required. Visit https://rustup.rs to install."
}
if (-not $hasJava) {
    Fail "Java (JDK 17+) is required to run Gradle. Please install JDK 17 and add to PATH."
}

# Check / install cargo-ndk
Step "Checking cargo-ndk"
if (-not (Get-Command "cargo-ndk" -ErrorAction SilentlyContinue)) {
    Warn "cargo-ndk not found. Installing..."
    & cargo install cargo-ndk
    if ($LASTEXITCODE -ne 0) { Fail "Failed to install cargo-ndk." }
    OK "cargo-ndk installed"
} else {
    OK "cargo-ndk is installed"
}

# --- Rust target installation ---
Step "Adding Rust Android targets"
$rustTargets = @(
    "aarch64-linux-android",
    "armv7-linux-androideabi",
    "x86_64-linux-android"
)
foreach ($t in $rustTargets) {
    $installed = (& rustup target list --installed) | Select-String $t
    if ($installed) {
        OK "$t already installed"
    } else {
        Info "Adding $t ..."
        & rustup target add $t
        if ($LASTEXITCODE -ne 0) { Fail "Failed to add Rust target $t" }
        OK "$t added"
    }
}

# --- Android NDK check ---
Step "Checking Android NDK"
if (-not $env:ANDROID_NDK_HOME) {
    $candidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\ndk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk\ndk",
        "C:\Android\Sdk\ndk"
    )
    $found = $null
    foreach ($c in $candidates) {
        if (Test-Path $c) {
            $ndkDir = Get-ChildItem $c -Directory | Sort-Object Name -Descending | Select-Object -First 1
            if ($ndkDir) { $found = $ndkDir.FullName; break }
        }
    }
    if ($found) {
        $env:ANDROID_NDK_HOME = $found
        OK "Auto-detected ANDROID_NDK_HOME: $found"
    } else {
        Warn "ANDROID_NDK_HOME is not set and could not be auto-detected."
        Warn "Set it before running this script, e.g.:"
        Warn '$env:ANDROID_NDK_HOME = "C:\Users\<YourUser>\AppData\Local\Android\Sdk\ndk\<version>"'
        Fail "Cannot continue without Android NDK."
    }
} else {
    if (Test-Path $env:ANDROID_NDK_HOME) {
        OK "ANDROID_NDK_HOME = $($env:ANDROID_NDK_HOME)"
    } else {
        Fail "ANDROID_NDK_HOME points to a non-existent path: $($env:ANDROID_NDK_HOME)"
    }
}

# --- Optional clean ---
if ($Clean) {
    Step "Cleaning previous build artefacts"
    & cargo clean
    if ($LASTEXITCODE -ne 0) { Fail "cargo clean failed." }
    Push-Location $AndroidDir
    try {
        if (Test-Path ".\gradlew.bat") {
            & .\gradlew.bat clean
        } elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
            & gradle clean
        }
    } finally {
        Pop-Location
    }
    OK "Clean complete"
}

# --- Determine ABIs ---
$abiMap = @{
    "arm64-v8a"   = "arm64-v8a"
    "armeabi-v7a" = "armeabi-v7a"
    "x86_64"      = "x86_64"
}

if ($Target -ne "") {
    $selectedAbis = $Target -split "," | ForEach-Object { $_.Trim() }
    foreach ($a in $selectedAbis) {
        if (-not $abiMap.ContainsKey($a)) { Fail "Unknown ABI: $a. Valid: $($abiMap.Keys -join ', ')" }
    }
} elseif ($Release) {
    $selectedAbis = @("arm64-v8a", "armeabi-v7a", "x86_64")
} else {
    $selectedAbis = @("arm64-v8a")
}

Info "Building for ABIs: $($selectedAbis -join ', ')"

# --- Build Rust library ---
Step "Building Rust core library"

$jniLibsDir = Join-Path $ScriptDir "android\app\src\main\jniLibs"

$ndkTargetArgs = foreach ($abi in $selectedAbis) { "-t"; $abi }
$cargoArgs     = @("ndk") + $ndkTargetArgs + @("-o", $jniLibsDir, "build")
if ($Release) { $cargoArgs += "--release" }

Info "Running: cargo $($cargoArgs -join ' ')"
& cargo @cargoArgs
if ($LASTEXITCODE -ne 0) { Fail "Rust build failed." }
OK "Rust library built successfully"

# --- Verify .so files exist ---
Step "Verifying JNI libraries"
foreach ($abi in $selectedAbis) {
    $soDir = Join-Path $jniLibsDir $abi
    $soFiles = Get-ChildItem $soDir -Filter "*.so" -ErrorAction SilentlyContinue
    if ($soFiles) {
        foreach ($f in $soFiles) { OK "$abi / $($f.Name)" }
    } else {
        Warn "No .so found in $soDir"
    }
}

# --- Gradle build ---
Step "Running Gradle build"

$gradleTask = if ($Release) { "assembleRelease" } else { "assembleDebug" }
Info "Task: $gradleTask"

Push-Location $AndroidDir
try {
    if (Test-Path ".\gradlew.bat") {
        & .\gradlew.bat $gradleTask --stacktrace
    } elseif (Get-Command gradle -ErrorAction SilentlyContinue) {
        & gradle $gradleTask --stacktrace
    } else {
        Fail "Neither gradlew.bat nor system gradle found. Open the 'android' folder in Android Studio once to generate gradlew, or install Gradle."
    }
    if ($LASTEXITCODE -ne 0) { Fail "Gradle build failed." }
} finally {
    Pop-Location
}
OK "Gradle build succeeded"

# --- Print APK path ---
Step "Build complete!"

$apkSubDir = if ($Release) { "release" } else { "debug" }
$apkPattern = if ($Release) { "app-release*.apk" } else { "app-debug.apk" }
$apkDir = Join-Path $AndroidDir "app\build\outputs\apk\$apkSubDir"
$apk    = Get-ChildItem $apkDir -Filter $apkPattern -ErrorAction SilentlyContinue | Select-Object -First 1

if ($apk) {
    Write-Host ""
    Write-Host "  APK : $($apk.FullName)" -ForegroundColor Green
    Write-Host "  Size: $([math]::Round($apk.Length / 1MB, 2)) MB" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Install with:" -ForegroundColor Cyan
    Write-Host "    adb install -r `"$($apk.FullName)`"" -ForegroundColor White
} else {
    Warn "APK not found in $apkDir - check build output above."
}

Write-Host ""
