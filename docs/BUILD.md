# Hướng dẫn Build / Build Guide

> **WebBrowser** — Rust-powered multi-tab Android WebView browser  
> Bilingual guide: Tiếng Việt 🇻🇳 | English 🇬🇧

---

## 1. Yêu cầu hệ thống / System Requirements

### Hệ điều hành / Operating System

| Hệ điều hành / OS | Phiên bản / Version |
|---|---|
| Windows | 10 (build 19041+) hoặc Windows 11 |
| macOS | 12 Monterey hoặc mới hơn |
| Ubuntu / Debian | 20.04 LTS hoặc mới hơn |

**RAM tối thiểu / Minimum RAM:** 8 GB  
**Dung lượng ổ đĩa / Free disk space:** 20 GB (SDK + NDK + Rust toolchain + build cache)

### Công cụ cần thiết / Required Tools

| Công cụ / Tool | Phiên bản tối thiểu / Min Version | Ghi chú / Notes |
|---|---|---|
| **Rust** | 1.80 | via [rustup.rs](https://rustup.rs) |
| **Android SDK** | API 35 (Android 15) | via Android Studio hoặc cmdline-tools |
| **Android NDK** | r27 (27.x.xxxxxxx) | NDK r27+ required for LLVM 17 |
| **JDK** | 17 | OpenJDK 17 / Adoptium Temurin 17 |
| **cargo-ndk** | 3.5+ | `cargo install cargo-ndk` |
| **Android Studio** | Hedgehog 2023.1+ | Tuỳ chọn / Optional (for UI preview) |
| **ADB** | bất kỳ / any | Bundled in `platform-tools` |

> [!NOTE]
> Android Studio là tuỳ chọn — bạn chỉ cần SDK + NDK + JDK để build APK từ dòng lệnh.
> Android Studio is optional — you only need SDK + NDK + JDK to build the APK from the command line.

---

## 2. Cài đặt môi trường / Environment Setup

### 2.1 Cài Rust / Install Rust

```bash
# Linux / macOS
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source "$HOME/.cargo/env"

# Windows (PowerShell)
winget install Rustlang.Rustup
# hoặc tải từ / or download from: https://rustup.rs
```

Kiểm tra / Verify:

```bash
rustup --version   # rustup 1.27+
cargo --version    # cargo 1.80+
```

### 2.2 Cài Android SDK & NDK / Install Android SDK & NDK

**Cách 1 — Android Studio (khuyến nghị / recommended):**

1. Tải Android Studio từ / Download from: <https://developer.android.com/studio>
2. Mở **SDK Manager** → **SDK Platforms** → cài Android 15 (API 35)
3. **SDK Tools** → cài **NDK (Side by side)** phiên bản r27+
4. **SDK Tools** → cài **Android SDK Command-line Tools**

**Cách 2 — Command-line only:**

```bash
# Linux / macOS
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p ~/Android/Sdk/cmdline-tools
unzip commandlinetools-*.zip -d ~/Android/Sdk/cmdline-tools
mv ~/Android/Sdk/cmdline-tools/cmdline-tools ~/Android/Sdk/cmdline-tools/latest

~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager \
    "platforms;android-35" \
    "ndk;27.2.12479018" \
    "platform-tools" \
    "build-tools;35.0.0"
```

### 2.3 Thiết lập biến môi trường / Set Environment Variables

**Linux / macOS** — thêm vào `~/.bashrc` hoặc `~/.zshrc`:

```bash
export ANDROID_HOME="$HOME/Android/Sdk"                            # Linux
# export ANDROID_HOME="$HOME/Library/Android/sdk"                 # macOS
export ANDROID_NDK_HOME="$ANDROID_HOME/ndk/27.2.12479018"
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin"
```

**Windows** — thêm vào System Environment Variables:

```powershell
[System.Environment]::SetEnvironmentVariable("ANDROID_HOME",
    "$env:LOCALAPPDATA\Android\Sdk", "User")
[System.Environment]::SetEnvironmentVariable("ANDROID_NDK_HOME",
    "$env:LOCALAPPDATA\Android\Sdk\ndk\27.2.12479018", "User")
```

### 2.4 Cài cargo-ndk / Install cargo-ndk

```bash
cargo install cargo-ndk
cargo ndk --version   # cargo-ndk 3.5+
```

### 2.5 Thêm Rust Android targets / Add Rust Android Targets

```bash
rustup target add aarch64-linux-android      # ARM64 (most modern phones)
rustup target add armv7-linux-androideabi    # ARM 32-bit (older devices)
rustup target add x86_64-linux-android       # x86_64 emulator
```

---

## 3. Build APK

### 3.1 Build nhanh (Debug) / Quick Debug Build

```powershell
# Windows
.\build.ps1
```

```bash
# Linux / macOS
chmod +x build.sh
./build.sh
```

Build debug chỉ compile cho **arm64-v8a** để nhanh hơn.  
Debug build only compiles for **arm64-v8a** for speed.

### 3.2 Build Release

```powershell
# Windows — tất cả ABIs (arm64, armv7, x86_64)
.\build.ps1 -Release

# Build cho ABI cụ thể / specific ABI
.\build.ps1 -Release -Target arm64-v8a,x86_64
```

```bash
# Linux / macOS
./build.sh --release
./build.sh --release --target arm64-v8a,x86_64
```

### 3.3 Dọn dẹp và build lại / Clean + Rebuild

```powershell
.\build.ps1 -Clean -Release   # Windows
```

```bash
./build.sh --clean --release  # Linux / macOS
```

### 3.4 Build thủ công từng bước / Manual Step-by-Step

```bash
# Bước 1: Build Rust core / Step 1: Build Rust core
cargo ndk \
    -t armeabi-v7a \
    -t arm64-v8a \
    -t x86_64 \
    -o android/app/src/main/jniLibs \
    build --release

# Bước 2: Build APK / Step 2: Build APK
cd android
./gradlew assembleRelease   # or assembleDebug
```

### 3.5 Vị trí APK đầu ra / APK Output Location

| Build type | Path |
|---|---|
| Debug | `android/app/build/outputs/apk/debug/app-debug.apk` |
| Release | `android/app/build/outputs/apk/release/app-release.apk` |

---

## 4. Cài APK lên thiết bị / Install APK on Device

### 4.1 Via ADB (khuyến nghị / recommended)

```bash
# Bật USB debugging trên điện thoại rồi kết nối USB
# Enable USB debugging on your phone then connect USB

adb devices                           # Kiểm tra kết nối / Check connection
adb install -r app-debug.apk         # Cài / Install (debug)
adb install -r app-release.apk       # Cài / Install (release)
```

Cờ `-r` cho phép cài đè lên phiên bản cũ / The `-r` flag allows overwriting an existing installation.

### 4.2 Cài thủ công (File transfer) / Manual Installation

1. Copy APK vào điện thoại qua USB / cable hoặc cloud
2. Trên điện thoại, dùng file manager mở file `.apk`
3. Nếu được hỏi, cho phép "Install from unknown sources"

### 4.3 Bật "Unknown Sources" / Enable Unknown Sources

- **Android 8+:** Settings → Apps → Special App Access → Install Unknown Apps → chọn file manager → Allow
- **Android 7 trở xuống:** Settings → Security → Unknown Sources → bật

> [!WARNING]
> Chỉ cài APK từ nguồn bạn tin tưởng.  
> Only install APKs from sources you trust.

---

## 5. Troubleshooting

### NDK không tìm thấy / NDK Not Found

```
error: linker `aarch64-linux-android-ld` not found
```

**Giải pháp / Fix:**

```bash
# Kiểm tra / Check
echo $ANDROID_NDK_HOME

# Đặt lại / Reset
export ANDROID_NDK_HOME="$(ls -d ~/Android/Sdk/ndk/* | sort -rV | head -1)"
```

### Lỗi linker / Linker Errors

```
error: linking with `cc` failed
```

**Giải pháp / Fix:** Đảm bảo NDK version ≥ r27.  
Install NDK r27+ via SDK Manager.

### WebView crash khi khởi động / WebView Crashes on Launch

- Kiểm tra `minSdk` ≥ 26 trong `app/build.gradle`
- Xoá dữ liệu ứng dụng / Clear app data: Settings → Apps → WebBrowser → Clear Data
- Xem logcat / Check logcat:

```bash
adb logcat -s chromium WebViewFactory | head -50
```

### Không load được HTTP / HTTP Not Loading

Đảm bảo `android:usesCleartextTraffic="true"` có trong `AndroidManifest.xml`  
Make sure `android:usesCleartextTraffic="true"` is set in `AndroidManifest.xml`

### Đọc logcat / Reading Logcat

```bash
# Lọc theo tags quan trọng / Filter by important tags
adb logcat -s rust_core WebBrowser chromium | tee build.log

# Chỉ xem lỗi / Errors only
adb logcat *:E

# Lọc theo PID của app
adb shell pidof com.example.webviewbrowser   # Lấy PID
adb logcat --pid=<PID>
```

### Debug JS Bridge

```bash
# Mở Chrome DevTools cho WebView
# 1. Bật USB debugging trên thiết bị
# 2. Mở Chrome trên máy tính, vào:
#    chrome://inspect/#devices
# 3. Click "inspect" dưới WebView của app

# Hoặc xem JS console qua logcat:
adb logcat -s chromium | grep -i "console"
```

### Lỗi Gradle / Gradle Errors

```bash
# Xóa Gradle cache
rm -rf ~/.gradle/caches
rm -rf android/.gradle
./gradlew --refresh-dependencies assembleDebug
```

---

## 6. Cấu trúc dự án / Project Structure

```
WebBrowser/
├── build.ps1                   # Windows build script
├── build.sh                    # Linux/macOS build script
├── Cargo.toml                  # Rust workspace manifest
├── src/                        # Rust source code
│   └── lib.rs                  # JNI entry point (RustBridge)
├── android/                    # Android project root
│   ├── build.gradle            # Root Gradle build file
│   ├── settings.gradle         # Module declarations
│   ├── gradle.properties       # Gradle tunables
│   ├── local.properties        # SDK path (git-ignored)
│   └── app/
│       ├── build.gradle        # App module Gradle config
│       ├── proguard-rules.pro  # R8/ProGuard keep rules
│       └── src/main/
│           ├── AndroidManifest.xml
│           ├── java/com/example/webviewbrowser/
│           │   ├── MainActivity.java        # Browser entry point
│           │   ├── TabSwitcherActivity.java # Tab grid overlay
│           │   ├── WebTabView.java          # Per-tab WebView wrapper
│           │   ├── TabManager.java          # Tab lifecycle
│           │   ├── RustBridge.java          # JNI bridge to Rust
│           │   └── DownloadHandler.java     # DownloadManager wrapper
│           ├── jniLibs/                    # Compiled .so files (generated)
│           │   ├── arm64-v8a/
│           │   ├── armeabi-v7a/
│           │   └── x86_64/
│           └── res/
│               ├── drawable/               # Vector icons + shape drawables
│               ├── layout/                 # XML layout files
│               │   ├── activity_main.xml
│               │   ├── activity_tab_switcher.xml
│               │   └── item_tab_card.xml
│               ├── values/
│               │   ├── colors.xml
│               │   ├── strings.xml
│               │   └── themes.xml
│               └── mipmap-*/              # App launcher icons
└── docs/
    └── BUILD.md                # This file
```

> [!TIP]
> Thư mục `jniLibs/` được tạo tự động khi chạy build script.  
> The `jniLibs/` directory is auto-generated when running the build script — do not commit it to git.

---

*Cập nhật lần cuối / Last updated: 2025*
