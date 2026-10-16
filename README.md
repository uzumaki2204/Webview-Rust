# WebBrowser

> A fast, multi-tab Android browser powered by a **Rust** core and Android's native **WebView**.

<!-- Screenshot placeholder -->
<!-- ![App screenshot](docs/screenshot.png) -->

---

## ✨ Features

| Feature | Description |
|---|---|
| 🗂️ **Multi-tab browsing** | Open, switch, and close unlimited tabs |
| 🔲 **Chrome-style tab switcher** | Thumbnail grid overlay for quick tab management |
| 🌉 **Rust ↔ Java JS Bridge** | High-performance bidirectional communication between WebView JS and native Rust code |
| ⬇️ **Download manager** | In-app download handling via Android `DownloadManager` |
| 💾 **Session persistence** | Open tabs and history survive app restarts |
| 🌐 **Cleartext + HTTPS** | Supports both HTTP and HTTPS, including local dev servers |
| 🎨 **Material You** | Material 3 / DayNight theming with blue primary palette |
| ⚡ **Hardware accelerated** | Full GPU compositing for smooth scrolling |

---

## 🚀 Quick Start

```bash
# 1. Clone the repo
git clone https://github.com/your-org/webbrowser-rust-android.git
cd webbrowser-rust-android

# 2. Build (debug, arm64-v8a)
./build.sh          # Linux / macOS
.\build.ps1         # Windows

# 3. Install on connected device
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
```

For detailed setup instructions, see **[docs/BUILD.md](docs/BUILD.md)**.

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Android UI Layer                  │
│  MainActivity ─── TabSwitcherActivity               │
│       │                   │                         │
│  WebTabView (WebView)   RecyclerView + TabAdapter   │
│       │                                             │
│  JsBridgeInterface (@JavascriptInterface)           │
└───────────────────────┬─────────────────────────────┘
                        │ JNI
┌───────────────────────▼─────────────────────────────┐
│                  Rust Core (librust_core.so)         │
│  RustBridge ─── Tab state ─── URL processing        │
│  Session storage ─── Download logic                 │
└─────────────────────────────────────────────────────┘
```

### Key components

| Component | Language | Purpose |
|---|---|---|
| `MainActivity` | Java | Browser chrome, URL bar, navigation controls |
| `TabSwitcherActivity` | Java | Full-screen tab grid overlay |
| `WebTabView` | Java | Per-tab WebView wrapper with JS bridge |
| `TabManager` | Java | Tab lifecycle, creation, persistence |
| `RustBridge` | Java / Rust (JNI) | Native ↔ JS message bus |
| `DownloadHandler` | Java | Intercepts download requests |
| `librust_core.so` | Rust | Core logic compiled for ARM/x86 |

### Supported ABIs

| ABI | Devices |
|---|---|
| `arm64-v8a` | All modern Android phones (2016+) |
| `armeabi-v7a` | Legacy 32-bit ARM devices |
| `x86_64` | Android emulator (AVD) |

---

## 📋 Requirements

- **Rust** ≥ 1.80
- **Android NDK** r27+
- **Android SDK** API 35
- **JDK** 17+
- **cargo-ndk** ≥ 3.5

See [docs/BUILD.md](docs/BUILD.md) for full setup instructions.

---

## 🗂️ Project Structure

```
.
├── build.ps1          # Windows build script
├── build.sh           # Linux/macOS build script
├── Cargo.toml         # Rust manifest
├── src/               # Rust source
├── android/           # Android project
│   └── app/src/main/
│       ├── java/…     # Java source
│       ├── res/…      # Resources (layouts, drawables, values)
│       └── AndroidManifest.xml
└── docs/
    └── BUILD.md       # Detailed build + setup guide
```

---

## 🤝 Contributing

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/your-feature`
3. Commit your changes: `git commit -m "feat: add your feature"`
4. Push to the branch: `git push origin feature/your-feature`
5. Open a Pull Request

Please follow [Conventional Commits](https://www.conventionalcommits.org/) for commit messages.

---

## 📄 License

This project is licensed under the **MIT License** — see the [LICENSE](LICENSE) file for details.

```
MIT License

Copyright (c) 2025 WebBrowser Contributors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
