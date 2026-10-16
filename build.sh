#!/usr/bin/env bash
# =============================================================================
# build.sh — WebBrowser: Rust + Android NDK + Gradle build script
# Compatible with: Linux, macOS
#
# Usage:
#   ./build.sh                    # debug build, arm64-v8a only
#   ./build.sh --release          # release build, all ABIs
#   ./build.sh --release --target arm64-v8a,x86_64
#   ./build.sh --clean --release
# =============================================================================

set -euo pipefail

# ─── Colour helpers ──────────────────────────────────────────────────────────
RESET="\033[0m"
BLUE="\033[34m"
CYAN="\033[36m"
GREEN="\033[32m"
YELLOW="\033[33m"
RED="\033[31m"

info()  { echo -e "  ${CYAN}$*${RESET}"; }
step()  { echo -e "\n${BLUE}► $*${RESET}"; }
ok()    { echo -e "  ${GREEN}✓ $*${RESET}"; }
warn()  { echo -e "  ${YELLOW}⚠ $*${RESET}"; }
fail()  { echo -e "  ${RED}✗ $*${RESET}"; exit 1; }

# ─── Parse arguments ─────────────────────────────────────────────────────────
RELEASE=false
CLEAN=false
TARGET_OVERRIDE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        --release|-r)   RELEASE=true ;;
        --clean|-c)     CLEAN=true ;;
        --target|-t)    TARGET_OVERRIDE="$2"; shift ;;
        --help|-h)
            head -20 "$0"
            exit 0 ;;
        *) warn "Unknown argument: $1" ;;
    esac
    shift
done

# ─── Banner ──────────────────────────────────────────────────────────────────
echo ""
echo -e "${BLUE}╔══════════════════════════════════════════╗${RESET}"
echo -e "${BLUE}║   QCBrowser — Rust + Android Build       ║${RESET}"
echo -e "${BLUE}╚══════════════════════════════════════════╝${RESET}"
BUILD_MODE=$( [ "$RELEASE" = true ] && echo "RELEASE" || echo "DEBUG" )
info "Mode : $BUILD_MODE"

# ─── Directories ─────────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$SCRIPT_DIR/android"
JNI_LIBS_DIR="$SCRIPT_DIR/android/app/src/main/jniLibs"

# ─── Tool checks ─────────────────────────────────────────────────────────────
step "Checking required tools"

require_cmd() {
    local cmd="$1"
    local hint="$2"
    if command -v "$cmd" &>/dev/null; then
        ok "$cmd found ($(command -v "$cmd"))"
    else
        warn "$cmd not found. $hint"
        return 1
    fi
}

require_cmd rustup "Install from https://rustup.rs" || fail "rustup required."
require_cmd cargo  "Install from https://rustup.rs" || fail "cargo required."
require_cmd java   "Install JDK 17+ from https://adoptium.net" || fail "JDK 17+ required."

# ─── cargo-ndk ───────────────────────────────────────────────────────────────
step "Checking cargo-ndk"
if cargo ndk --version &>/dev/null; then
    ok "cargo-ndk $(cargo ndk --version 2>&1)"
else
    warn "cargo-ndk not found. Installing..."
    cargo install cargo-ndk || fail "Failed to install cargo-ndk."
    ok "cargo-ndk installed"
fi

# ─── Rust Android targets ────────────────────────────────────────────────────
step "Adding Rust Android targets"
RUST_TARGETS=(
    "aarch64-linux-android"
    "armv7-linux-androideabi"
    "x86_64-linux-android"
)
for t in "${RUST_TARGETS[@]}"; do
    if rustup target list --installed 2>/dev/null | grep -q "^${t}$"; then
        ok "$t already installed"
    else
        info "Adding $t ..."
        rustup target add "$t" || fail "Failed to add $t"
        ok "$t added"
    fi
done

# ─── Android NDK check ───────────────────────────────────────────────────────
step "Checking Android NDK"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
    # Try common locations
    NDK_CANDIDATES=(
        "$HOME/Library/Android/sdk/ndk"              # macOS Android Studio
        "$HOME/Android/Sdk/ndk"                      # Linux Android Studio
        "/opt/android-sdk/ndk"
        "$ANDROID_HOME/ndk"
    )
    FOUND_NDK=""
    for c in "${NDK_CANDIDATES[@]}"; do
        if [ -d "$c" ]; then
            # Pick latest version directory
            LATEST=$(ls -1 "$c" 2>/dev/null | sort -rV | head -1)
            if [ -n "$LATEST" ]; then
                FOUND_NDK="$c/$LATEST"
                break
            fi
        fi
    done
    if [ -n "$FOUND_NDK" ]; then
        export ANDROID_NDK_HOME="$FOUND_NDK"
        ok "Auto-detected ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
    else
        warn "ANDROID_NDK_HOME is not set and could not be auto-detected."
        warn "Set it before running, e.g.:"
        warn '  export ANDROID_NDK_HOME="$HOME/Android/Sdk/ndk/27.2.12479018"'
        fail "Cannot continue without Android NDK."
    fi
else
    if [ -d "$ANDROID_NDK_HOME" ]; then
        ok "ANDROID_NDK_HOME = $ANDROID_NDK_HOME"
    else
        fail "ANDROID_NDK_HOME points to a non-existent path: $ANDROID_NDK_HOME"
    fi
fi

# ─── Optional clean ──────────────────────────────────────────────────────────
if [ "$CLEAN" = true ]; then
    step "Cleaning previous build artefacts"
    cargo clean || fail "cargo clean failed."
    pushd "$ANDROID_DIR" >/dev/null
    ./gradlew clean || fail "gradlew clean failed."
    popd >/dev/null
    ok "Clean complete"
fi

# ─── Determine ABIs ──────────────────────────────────────────────────────────
if [ -n "$TARGET_OVERRIDE" ]; then
    IFS=',' read -ra SELECTED_ABIS <<< "$TARGET_OVERRIDE"
elif [ "$RELEASE" = true ]; then
    SELECTED_ABIS=("arm64-v8a" "armeabi-v7a" "x86_64")
else
    # Debug: fast single-ABI build
    SELECTED_ABIS=("arm64-v8a")
fi

info "Building for ABIs: ${SELECTED_ABIS[*]}"

# ─── Build Rust library ───────────────────────────────────────────────────────
step "Building Rust core library"

CARGO_NDK_ARGS=()
for abi in "${SELECTED_ABIS[@]}"; do
    CARGO_NDK_ARGS+=("-t" "$abi")
done
CARGO_NDK_ARGS+=("-o" "$JNI_LIBS_DIR" "build")
if [ "$RELEASE" = true ]; then
    CARGO_NDK_ARGS+=("--release")
fi

info "Running: cargo ndk ${CARGO_NDK_ARGS[*]}"
cargo ndk "${CARGO_NDK_ARGS[@]}" || fail "Rust build failed."
ok "Rust library built successfully"

# ─── Verify .so files ────────────────────────────────────────────────────────
step "Verifying JNI libraries"
for abi in "${SELECTED_ABIS[@]}"; do
    SO_DIR="$JNI_LIBS_DIR/$abi"
    if ls "$SO_DIR"/*.so &>/dev/null; then
        for f in "$SO_DIR"/*.so; do
            ok "$abi / $(basename "$f")"
        done
    else
        warn "No .so found in $SO_DIR — library name may differ."
    fi
done

# ─── Gradle build ────────────────────────────────────────────────────────────
step "Running Gradle build"

GRADLE_TASK=$( [ "$RELEASE" = true ] && echo "assembleRelease" || echo "assembleDebug" )
info "Task: $GRADLE_TASK"

pushd "$ANDROID_DIR" >/dev/null
if [ -f "./gradlew" ]; then
    chmod +x ./gradlew
    ./gradlew "$GRADLE_TASK" --stacktrace || fail "Gradle build failed."
elif command -v gradle &>/dev/null; then
    gradle "$GRADLE_TASK" --stacktrace || fail "Gradle build failed."
else
    fail "Neither ./gradlew nor system 'gradle' found.\n  Open the 'android' folder in Android Studio once to generate gradlew, or install Gradle."
fi
popd >/dev/null

ok "Gradle build succeeded"

# ─── Print APK path ──────────────────────────────────────────────────────────
step "Build complete!"

APK_SUBDIR=$( [ "$RELEASE" = true ] && echo "release" || echo "debug" )
APK_PATTERN=$( [ "$RELEASE" = true ] && echo "app-release*.apk" || echo "app-debug.apk" )
APK_DIR="$ANDROID_DIR/app/build/outputs/apk/$APK_SUBDIR"

APK_FILE=$(find "$APK_DIR" -name "$APK_PATTERN" 2>/dev/null | head -1)

if [ -n "$APK_FILE" ] && [ -f "$APK_FILE" ]; then
    SIZE_MB=$(du -m "$APK_FILE" | cut -f1)
    echo ""
    echo -e "  ${GREEN}APK : $APK_FILE${RESET}"
    echo -e "  ${GREEN}Size: ${SIZE_MB} MB${RESET}"
    echo ""
    echo -e "  ${CYAN}Install with:${RESET}"
    echo -e "    adb install -r \"$APK_FILE\""
else
    warn "APK not found in $APK_DIR — check build output above."
fi

echo ""
