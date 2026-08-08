#!/data/data/com.termux/files/usr/bin/bash

# ============================================================================
# builder_core.sh — Inti logic APK Builder (v5)
# ----------------------------------------------------------------------------
# Arsitektur v5: UI 100% Kotlin, sh HANYA untuk logic berat.
# Script ini adalah MODE NON-INTERAKTIF dari build_ref.sh (build.sh v12.0)
# yang sudah terbukti berhasil di device user. App memanggil script ini lewat
# RUN_COMMAND intent dengan runner terminal-session sehingga output tampil di
# terminal Termux asli, dan TermuxActivity otomatis finish() saat session
# terakhir selesai (balik ke app).
#
# Pemakaian:
#   builder_core.sh build <project_path> [debug|release|clean]
#   builder_core.sh import <backup.zip>
#   builder_core.sh export [dest_dir]
#   builder_core.sh setup
#   builder_core.sh native <project_path>
#
# Semua fungsi (build_project, import_backup, export_backup, auto_setup,
# build_native_project, dst) DIAMBIL PERSIS dari build_ref.sh — TIDAK ditulis
# ulang di sini. Perintah interaktif (read -rp) dilewati lewat stdin preset.
# ============================================================================

# --- ENVIRONMENT & PATH CONFIGURATION (identik build.sh) ---
HOME_DIR="/data/data/com.termux/files/home"
SDK_DIR="$HOME_DIR/android-sdk"
NDK_VER_DEFAULT="25.2.9519653"
NDK_DIR="$SDK_DIR/ndk/$NDK_VER_DEFAULT"
WRAPPER_DIR="$SDK_DIR/wrapper-template"
WORKSPACE="$HOME_DIR/workspace"
LOG_FILE="/sdcard/build-error.log"
OUTPUT_DIR="/sdcard/BuildOutputs"
: "${PREFIX:=/data/data/com.termux/files/usr}"
APP_STATE_DIR="$HOME_DIR/.termux-apk-builder"
LAST_PROJECT_FILE="$APP_STATE_DIR/last_project.txt"
LAUNCHER_NAME="apkbuilder"

# --- COLOR & STYLING DEFINITIONS ---
RED='\033[0;31m';   GREEN='\033[0;32m'; YELLOW='\033[1;33m'
BLUE='\033[0;34m';  CYAN='\033[0;36m';  BOLD='\033[1m'
DIM='\033[2m';      RESET='\033[0m'

# ============================================================================
# DYNAMIC SYSTEM HARDWARE DETECTION (identik build.sh)
# ============================================================================
detect_device_hardware() {
    local mem_total_kb mem_total_mb
    mem_total_kb=$(grep MemTotal /proc/meminfo 2>/dev/null | awk '{print $2}')
    [ -z "$mem_total_kb" ] && mem_total_kb=4000000
    mem_total_mb=$(( mem_total_kb / 1024 ))

    if [ "$mem_total_mb" -le 3500 ]; then
        DYNAMIC_JVM_MX="640m"
        DYNAMIC_WORKERS=1
        DYNAMIC_NINJA_JOBS=1
        RAM_PROFILE="3GB Low-Memory Profile"
    elif [ "$mem_total_mb" -le 5200 ]; then
        DYNAMIC_JVM_MX="896m"
        DYNAMIC_WORKERS=2
        DYNAMIC_NINJA_JOBS=2
        RAM_PROFILE="4GB Balanced Profile"
    else
        DYNAMIC_JVM_MX="1280m"
        DYNAMIC_WORKERS=3
        DYNAMIC_NINJA_JOBS=3
        RAM_PROFILE="6GB+ High-Perf Profile"
    fi

    GRADLE_OPTS="-Xmx$DYNAMIC_JVM_MX -XX:MaxMetaspaceSize=384m -XX:+UseG1GC"
}

detect_device_hardware

# ============================================================================
# UI HELPERS (identik build.sh — tanpa banner/clear agar terminal tetap bersih)
# ============================================================================
stage() { echo -e "\n${CYAN}${BOLD}► $1${RESET}"; }
info()  { echo -e "  ${BLUE}i${RESET}  $1"; }
ok()    { echo -e "  ${GREEN}✓${RESET}  $1"; }
warn()  { echo -e "  ${YELLOW}!${RESET}  $1"; }
err()   { echo -e "  ${RED}x${RESET}  $1"; }

ensure_directories() {
    mkdir -p "$APP_STATE_DIR" "$OUTPUT_DIR" "$OUTPUT_DIR/Native" "$WORKSPACE" 2>/dev/null || true
}

get_last_project() {
    [ -f "$LAST_PROJECT_FILE" ] || return 1
    local last_project
    last_project=$(head -n1 "$LAST_PROJECT_FILE" 2>/dev/null)
    [ -n "$last_project" ] && [ -d "$last_project" ] || return 1
    printf '%s\n' "$last_project"
}

save_last_project() {
    local project_path="$1"
    [ -n "$project_path" ] || return 0
    ensure_directories
    printf '%s\n' "$project_path" > "$LAST_PROJECT_FILE"
}

# ============================================================================
# LIVE ANIMATED STREAMER (identik build.sh — spinner + pewarnaan)
# ============================================================================
live_animated_streamer() {
    python3 -u -c '
import sys, time, re

log_path = sys.argv[1] if len(sys.argv) > 1 else "/dev/null"
log_file = open(log_path, "w", encoding="utf-8", errors="ignore")

C_RED   = "\033[1;31m"
C_GREEN = "\033[1;32m"
C_YEL   = "\033[1;33m"
C_CYAN  = "\033[0;36m"
C_DIM   = "\033[2m"
C_RESET = "\033[0m"

spinner = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"]
spin_idx = 0
start_time = time.time()

for line in sys.stdin:
    log_file.write(line)
    log_file.flush()

    if "E aapt2" in line or "No package ID 7f found" in line:
        continue

    elapsed = int(time.time() - start_time)
    spin = spinner[spin_idx % len(spinner)]
    spin_idx += 1

    s = line.rstrip()
    if re.search(r"(?i)\b(BUILD FAILED|ERROR|error:|e:|FAILED|Exception)\b", s):
        formatted = f"{C_RED}{s}{C_RESET}"
    elif re.search(r"(?i)\b(BUILD SUCCESSFUL|UP-TO-DATE|FROM-CACHE|Built target)\b", s):
        formatted = f"{C_GREEN}{s}{C_RESET}"
    elif re.search(r"(?i)\b(WARNING|WARN|warning:|w:)\b", s):
        formatted = f"{C_YEL}{s}{C_RESET}"
    elif s.startswith("> Task") or "Building CXX" in s or "Linking CXX" in s:
        formatted = f"{C_CYAN}{s}{C_RESET}"
    else:
        formatted = f"{C_DIM}{s}{C_RESET}"

    badge = f"{C_CYAN}[{spin} {elapsed}s]{C_RESET}"
    sys.stdout.write(f"\r{badge} {formatted}\n")
    sys.stdout.flush()

log_file.close()
' "$1"
}

# ============================================================================
# PROJECT COLLECTORS (identik build.sh)
# ============================================================================
collect_android_projects() {
    local tmpfile gradle_file dir root
    tmpfile=$(mktemp)

    while IFS= read -r gradle_file; do
        case "$gradle_file" in
            */build/*|*/.gradle/*) continue ;;
        esac

        dir=$(dirname "$gradle_file")
        root="$dir"

        if [ "$(basename "$dir")" = "app" ] && { [ -f "$dir/../settings.gradle" ] || [ -f "$dir/../settings.gradle.kts" ] || [ -f "$dir/../gradlew" ]; }; then
            root=$(cd "$dir/.." 2>/dev/null && pwd -P)
        elif [ -f "$dir/settings.gradle" ] || [ -f "$dir/settings.gradle.kts" ] || [ -f "$dir/gradlew" ] || [ -f "$dir/app/build.gradle" ] || [ -f "$dir/app/build.gradle.kts" ]; then
            root=$(cd "$dir" 2>/dev/null && pwd -P)
        fi

        [ -n "$root" ] && printf '%s\n' "$root" >> "$tmpfile"
    done < <(find /sdcard -maxdepth 4 -type f \( -name "settings.gradle" -o -name "settings.gradle.kts" -o -name "build.gradle" -o -name "build.gradle.kts" \) 2>/dev/null)

    sort -u "$tmpfile"
    rm -f "$tmpfile"
}

collect_native_projects() {
    local tmpfile native_file dir root
    tmpfile=$(mktemp)

    while IFS= read -r native_file; do
        case "$native_file" in
            */build/*|*/.gradle/*|*/.cxx/*) continue ;;
        esac

        dir=$(dirname "$native_file")
        root="$dir"
        [ "$(basename "$dir")" = "jni" ] && root=$(cd "$dir/.." 2>/dev/null && pwd -P)

        [ -n "$root" ] && printf '%s\n' "$root" >> "$tmpfile"
    done < <(find /sdcard -maxdepth 4 -type f \( -name "CMakeLists.txt" -o -name "Android.mk" \) 2>/dev/null)

    sort -u "$tmpfile"
    rm -f "$tmpfile"
}

# ============================================================================
# INTERNAL TOOLCHAIN MANAGERS (identik build.sh)
# ============================================================================
fix_ndk_permissions() {
    if [ -d "$SDK_DIR/ndk" ]; then
        local actual_ndk_bin=$(find "$SDK_DIR/ndk" -maxdepth 3 -name "ndk-build" 2>/dev/null | head -n1)
        if [ -n "$actual_ndk_bin" ]; then
            local actual_ndk_dir=$(dirname "$actual_ndk_bin")
            if [ "$actual_ndk_dir" != "$NDK_DIR" ] && [ ! -d "$NDK_DIR" ]; then
                mkdir -p "$(dirname "$NDK_DIR")"
                ln -sf "$actual_ndk_dir" "$NDK_DIR" 2>/dev/null || cp -r "$actual_ndk_dir" "$NDK_DIR"
            fi
        fi
    fi

    if [ -d "$NDK_DIR" ]; then
        chmod -R +x "$NDK_DIR" 2>/dev/null || true
        mkdir -p "$NDK_DIR/prebuilt/linux-aarch64/bin"
        [ -f "$PREFIX/bin/make" ] && ln -sf "$PREFIX/bin/make" "$NDK_DIR/prebuilt/linux-aarch64/bin/make"
        [ -f "$PREFIX/bin/python3" ] && ln -sf "$PREFIX/bin/python3" "$NDK_DIR/prebuilt/linux-aarch64/bin/python3"

        if command -v termux-fix-shebang >/dev/null 2>&1; then
            termux-fix-shebang "$NDK_DIR/ndk-build" 2>/dev/null || true
            termux-fix-shebang "$NDK_DIR/build/ndk-build" 2>/dev/null || true
            find "$NDK_DIR" -type f \( -name "*.sh" -o -name "ndk-build" \) -exec termux-fix-shebang {} \; 2>/dev/null || true
        fi
    fi
}

setup_dummy_cmake() {
    local cmake_ver="${1:-3.22.1}"
    local cmake_dir="$SDK_DIR/cmake/$cmake_ver"

    mkdir -p "$cmake_dir/bin"
    [ ! -f "$PREFIX/bin/ninja" ] && pkg install ninja -y >/dev/null 2>&1 || true

    [ -f "$PREFIX/bin/cmake" ] && ln -sf "$PREFIX/bin/cmake" "$cmake_dir/bin/cmake"
    [ -f "$PREFIX/bin/ninja" ] && ln -sf "$PREFIX/bin/ninja" "$cmake_dir/bin/ninja"
    [ -f "$PREFIX/bin/ninja" ] && ln -sf "$PREFIX/bin/ninja" "$cmake_dir/bin/ninja-build"

    cat > "$cmake_dir/source.properties" << EOF
Pkg.PluginsSource=Android SDK
Pkg.Revision=$cmake_ver
Pkg.Path=cmake;$cmake_ver
EOF
}

download_platform_sdk() {
    local api_level="$1"
    local platform_dir="$SDK_DIR/platforms/android-$api_level"

    rm -rf "$SDK_DIR/platforms/android-13" "$SDK_DIR/platforms/android-14" 2>/dev/null || true

    if [ -d "$platform_dir" ] && [ -f "$platform_dir/android.jar" ] && [ -f "$platform_dir/core-for-system-modules.jar" ] && [ -f "$platform_dir/framework.aidl" ]; then
        local jar_size=$(wc -c < "$platform_dir/android.jar" 2>/dev/null || echo 0)
        local core_size=$(wc -c < "$platform_dir/core-for-system-modules.jar" 2>/dev/null || echo 0)
        if [ "$jar_size" -ne "$core_size" ]; then return 0; fi
        rm -rf "$platform_dir"
    fi

    info "Downloading Android SDK Platform $api_level..."
    local url="https://dl.google.com/android/repository/platform-${api_level}_r01.zip"
    local tmp_zip="$SDK_DIR/platform-$api_level.zip"
    local tmp_extract="$SDK_DIR/platforms/tmp_extract"
    
    rm -rf "$tmp_zip" "$tmp_extract"
    mkdir -p "$tmp_extract" "$SDK_DIR/platforms"
    
    if wget -q --show-progress -O "$tmp_zip" "$url" && [ -s "$tmp_zip" ]; then
        info "Unpacking Platform SDK android-$api_level..."
        if unzip -o -q "$tmp_zip" -d "$tmp_extract"; then
            rm -f "$tmp_zip"
            local extracted_folder=$(find "$tmp_extract" -maxdepth 1 -mindepth 1 -type d | head -n1)
            if [ -n "$extracted_folder" ] && [ -f "$extracted_folder/android.jar" ]; then
                rm -rf "$platform_dir"
                mv "$extracted_folder" "$platform_dir"
                rm -rf "$tmp_extract"
                
                echo "Pkg.Revision=1" > "$platform_dir/source.properties"
                echo "AndroidVersion.ApiLevel=$api_level" >> "$platform_dir/source.properties"
                if [ ! -f "$platform_dir/framework.aidl" ]; then
                    wget -q -O "$platform_dir/framework.aidl" "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-$api_level/framework.aidl" 2>/dev/null || true
                fi
                ok "Platform API $api_level installed."
                return 0
            fi
        fi
    fi
    rm -rf "$tmp_extract" "$tmp_zip"

    warn "Official download failed. Fetching fallback AOSP platform..."
    mkdir -p "$platform_dir"
    if wget -q --show-progress -O "$platform_dir/android.jar" "https://github.com/Reginer/aosp-android-jar/raw/main/android-$api_level/android.jar"; then
        wget -q -O "$platform_dir/framework.aidl" "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-$api_level/framework.aidl" 2>/dev/null || true

        if [ ! -s "$platform_dir/framework.aidl" ]; then
            cat > "$platform_dir/framework.aidl" << 'EOF'
interface java.lang.CharSequence;
interface java.lang.String;
parcelable android.accounts.Account;
parcelable android.app.PendingIntent;
parcelable android.content.ComponentName;
parcelable android.content.Intent;
parcelable android.content.IntentFilter;
parcelable android.graphics.Bitmap;
parcelable android.graphics.Rect;
parcelable android.net.Uri;
parcelable android.os.Bundle;
parcelable android.os.ParcelFileDescriptor;
parcelable android.os.ParcelUuid;
parcelable android.os.PersistableBundle;
parcelable android.view.KeyEvent;
parcelable android.view.MotionEvent;
EOF
        fi

        echo "ro.build.version.sdk=$api_level" > "$platform_dir/build.prop"
        echo "Pkg.Revision=1" > "$platform_dir/source.properties"
        echo "AndroidVersion.ApiLevel=$api_level" >> "$platform_dir/source.properties"
        ok "Platform API $api_level ready (Fallback)."
        return 0
    else
        err "Failed to download Platform SDK $api_level."
        return 1
    fi
}

setup_dummy_build_tools() {
    local bt_ver="$1"
    local bt_dir="$SDK_DIR/build-tools/$bt_ver"

    mkdir -p "$bt_dir/lib" "$bt_dir/renderscript/include" "$bt_dir/renderscript/clang-include"

    for tool in aapt aapt2 d8 zipalign apksigner; do
        [ -f "$PREFIX/bin/$tool" ] && ln -sf "$PREFIX/bin/$tool" "$bt_dir/$tool"
    done
    ln -sf "$PREFIX/bin/d8" "$bt_dir/dx" 2>/dev/null || true

    if [ -f "$PREFIX/bin/aidl" ]; then
        ln -sf "$PREFIX/bin/aidl" "$bt_dir/aidl"
    elif [ ! -s "$bt_dir/aidl" ]; then
        cat > "$bt_dir/aidl" << 'PYEOF'
#!/usr/bin/env python3
import sys, os, re
args = sys.argv[1:]
out_dir, input_files = None, []
i = 0
while i < len(args):
    arg = args[i]
    if arg.startswith('-o'):
        out_dir = arg[2:] if len(arg) > 2 else (args[i+1] if i+1 < len(args) else None)
        if len(arg) <= 2: i += 1
    elif arg.endswith('.aidl'): input_files.append(arg)
    i += 1
if out_dir:
    for aidl_file in input_files:
        if not os.path.exists(aidl_file): continue
        try:
            with open(aidl_file, 'r', encoding='utf-8', errors='ignore') as f: content = f.read()
            pkg_m = re.search(r'package\s+([\w.]+)\s*;', content)
            pkg = pkg_m.group(1) if pkg_m else ''
            iface_m = re.search(r'(interface|parcelable)\s+(\w+)', content)
            iface = iface_m.group(2) if iface_m else None
            if iface:
                tdir = os.path.join(out_dir, *pkg.split('.')) if pkg else out_dir
                os.makedirs(tdir, exist_ok=True)
                tjava = os.path.join(tdir, f"{iface}.java")
                jcode = f"package {pkg};\npublic interface {iface} extends android.os.IInterface {{\n    public static abstract class Stub extends android.os.Binder implements {pkg}.{iface} {{\n        private static final java.lang.String DESCRIPTOR = \"{pkg}.{iface}\";\n        public Stub() {{ this.attachInterface(this, DESCRIPTOR); }}\n        public static {pkg}.{iface} asInterface(android.os.IBinder obj) {{\n            if (obj == null) return null;\n            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);\n            if (iin != null && iin instanceof {pkg}.{iface}) return ({pkg}.{iface}) iin;\n            return new {pkg}.{iface}.Stub.Proxy(obj);\n        }}\n        @Override public android.os.IBinder asBinder() {{ return this; }}\n        private static class Proxy implements {pkg}.{iface} {{\n            private android.os.IBinder mRemote;\n            Proxy(android.os.IBinder remote) {{ mRemote = remote; }}\n            @Override public android.os.IBinder asBinder() {{ return mRemote; }}\n        }}\n    }}\n}}\n"
                with open(tjava, 'w', encoding='utf-8') as f: f.write(jcode)
        except Exception: pass
sys.exit(0)
PYEOF
        chmod +x "$bt_dir/aidl"
    fi

    local dummy_execs=("dexdump" "split-select" "mainDexClasses" "mainDexClasses.bat" "llvm-rs-cc" "bcc_compat" "lld" "arm-linux-androideabi-ld" "i686-linux-android-ld" "mipsel-linux-android-ld" "aarch64-linux-android-ld" "x86_64-linux-android-ld")
    for dummy_exec in "${dummy_execs[@]}"; do
        if [ ! -f "$bt_dir/$dummy_exec" ]; then
            cat > "$bt_dir/$dummy_exec" << 'EOF'
#!/bin/sh
exit 0
EOF
            chmod +x "$bt_dir/$dummy_exec"
        fi
    done

    local empty_zip_base64="UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA=="
    local dummy_jars=("core-lambda-stubs.jar" "mainDexClasses.rules" "lib/apksigner.jar" "lib/d8.jar" "lib/dx.jar" "lib/aapt2.jar" "lib/shrinkscript.jar")
    for jarfile in "${dummy_jars[@]}"; do
        if [ ! -s "$bt_dir/$jarfile" ]; then
            echo "$empty_zip_base64" | base64 -d > "$bt_dir/$jarfile" 2>/dev/null || touch "$bt_dir/$jarfile"
        fi
    done

    cat > "$bt_dir/source.properties" << EOF
Pkg.PluginsSource=Android SDK
Pkg.Revision=$bt_ver
EOF
}

detect_agp_version() {
    grep -E "com.android.tools.build:gradle:[0-9.]+" "$1" | head -n1 | sed -E 's/.*:([0-9.]+).*/\1/'
}

agp_to_gradle() {
    case "$1" in
        8.4.*|8.5.*|8.6.*) echo "8.7" ;;
        8.2.*|8.3.*)       echo "8.2" ;;
        8.0.*|8.1.*)       echo "8.0" ;;
        7.4.*)             echo "7.5" ;;
        7.3.*)             echo "7.4" ;;
        *)                 echo "8.7" ;;
    esac
}

_require_python() {
    command -v python3 >/dev/null 2>&1 || { warn "Python3 runtime missing"; return 1; }
}

clean_toolchains_python() {
    local target_file="$1"
    _require_python || return 1
    python3 - "$target_file" <<'PYEOF'
import re, sys
path = sys.argv[1]
try:
    with open(path, 'r', encoding='utf-8', errors='ignore') as f: src = f.read()
    src = re.sub(r'JavaVersion\.VERSION_2[0-9]', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'JavaVersion\.VERSION_19', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'JavaVersion\.VERSION_18', 'JavaVersion.VERSION_17', src)
    src = re.sub(r'sourceCompatibility\s*=?\s*[\'"]?2[0-9][\'"]?', 'sourceCompatibility = JavaVersion.VERSION_17', src)
    src = re.sub(r'targetCompatibility\s*=?\s*[\'"]?2[0-9][\'"]?', 'targetCompatibility = JavaVersion.VERSION_17', src)
    src = re.sub(r'jvmTarget\s*=\s*[\'"]2[0-9][\'"]', 'jvmTarget = "17"', src)

    while True:
        match = re.search(r'(?i)(\bjavaCompiler\s*=\s*javaToolchains[^{]*\{)', src)
        if not match:
            match = re.search(r'(?i)(\bjavaCompiler\s*=\s*[^\n]+)', src)
            if not match: break
        start = match.start()
        depth, end = 0, -1
        for i in range(match.end() - 1, len(src)):
            if src[i] == '{': depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0: end = i + 1; break
        if end != -1: src = src[:start] + '/* javaCompiler disabled */' + src[end:]
        else:
            line_end = src.find('\n', start)
            if line_end != -1: src = src[:start] + '/* javaCompiler disabled */' + src[line_end:]
            else: break

    src = re.sub(r'(?i)(\bjvmToolchain\s*\([^)]*\))', r'/* \1 */', src)
    src = re.sub(r'(?i)(\bjvmToolchain\s*=.*)', r'/* \1 */', src)

    while True:
        match = re.search(r'(?i)(\btoolchain\s*\{)', src)
        if not match: break
        start = match.start()
        depth, end = 0, -1
        for i in range(match.end() - 1, len(src)):
            if src[i] == '{': depth += 1
            elif src[i] == '}':
                depth -= 1
                if depth == 0: end = i + 1; break
        if end != -1: src = src[:start] + '/* toolchain disabled */' + src[end:]
        else: break

    with open(path, 'w', encoding='utf-8') as f: f.write(src)
except Exception: pass
PYEOF
}

inject_sdk_and_ndk() {
    local gradle_file="$1" sdk_ver="$2" ndk_ver="$3"
    _require_python || return 1
    cp "$gradle_file" "$gradle_file.bak" 2>/dev/null || true
    python3 - "$gradle_file" "$sdk_ver" "$ndk_ver" <<'PYEOF'
import re, sys
path, sdk_ver, ndk_ver = sys.argv[1], sys.argv[2], sys.argv[3]
is_kts = path.endswith('.kts')
with open(path, 'r', encoding='utf-8', errors='ignore') as f: src = f.read()

if is_kts:
    if re.search(r'compileSdk\s*=', src): src = re.sub(r'compileSdk\s*=\s*[0-9]+', f'compileSdk = {sdk_ver}', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    compileSdk = ' + sdk_ver, src, count=1)
else:
    if re.search(r'compileSdk\s+[0-9]+', src): src = re.sub(r'compileSdk\s+[0-9]+', f'compileSdk {sdk_ver}', src)
    elif re.search(r'compileSdkVersion\s+[0-9]+', src): src = re.sub(r'compileSdkVersion\s+[0-9]+', f'compileSdkVersion {sdk_ver}', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    compileSdk ' + sdk_ver, src, count=1)

if is_kts:
    if re.search(r'ndkVersion\s*=', src): src = re.sub(r'ndkVersion\s*=\s*[\'"][^\'"]+[\'"]', f'ndkVersion = "{ndk_ver}"', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    ndkVersion = "' + ndk_ver + '"', src, count=1)
else:
    if re.search(r'ndkVersion\s+[\'"]', src): src = re.sub(r'ndkVersion\s+[\'"][^\'"]+[\'"]', f'ndkVersion "{ndk_ver}"', src)
    else: src = re.sub(r'(android\s*\{)', r'\1\n    ndkVersion "' + ndk_ver + '"', src, count=1)

with open(path, 'w', encoding='utf-8') as f: f.write(src)
PYEOF
}

ensure_wrapper_template() {
    mkdir -p "$WRAPPER_DIR/gradle/wrapper"
    if [ ! -f "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" ] || [ $(wc -c < "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || echo 0) -lt 10000 ]; then
        cd "$WRAPPER_DIR"
        echo "rootProject.name='wrapper-template'" > settings.gradle
        wget -q -O "$WRAPPER_DIR/gradle/wrapper/gradle-wrapper.jar" "https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar" 2>/dev/null || true
        gradle wrapper --gradle-version 8.7 --no-daemon -q 2>/dev/null || true
        cd ~
    fi
}

# ============================================================================
# BACKUP & SETUP MODULES (identik build.sh — tanpa prompt read)
# ============================================================================
export_backup() {
    stage "Exporting Complete Build Environment"
    pkg install p7zip zip rsync -y >/dev/null 2>&1 || { err "Failed to install compression packages."; return 1; }
    if [ ! -d "$SDK_DIR" ]; then err "SDK toolchain not found. Run auto-setup first."; return 1; fi

    STAGE="$HOME_DIR/.backup-temp"
    rm -rf "$STAGE"; mkdir -p "$STAGE/pkg-cache"
    
    info "Archiving SDK platforms & build tools..."
    rsync -a --exclude='ndk/' "$SDK_DIR/" "$STAGE/android-sdk/"
    
    info "Archiving Gradle cache & wrapper templates..."
    [ -d "$HOME_DIR/.gradle" ] && rsync -a "$HOME_DIR/.gradle/" "$STAGE/.gradle/"
    [ -d "$WRAPPER_DIR" ] && rsync -a "$WRAPPER_DIR/" "$STAGE/wrapper-template/"
    
    info "Caching Termux system packages (.deb)..."
    [ -d "$SDK_DIR/pkg-cache" ] && cp "$SDK_DIR/pkg-cache/"*.deb "$STAGE/pkg-cache/" 2>/dev/null || true
    [ -d "$PREFIX/var/cache/apt/archives" ] && cp "$PREFIX/var/cache/apt/archives/"*.deb "$STAGE/pkg-cache/" 2>/dev/null || true
    
    ensure_directories
    local zip_dest="${1:-$OUTPUT_DIR}"
    mkdir -p "$zip_dest"
    ZIPNAME="$zip_dest/builder-backup-complete-$(date +%Y%m%d-%H%M).zip"
    info "Compressing backup target to: $ZIPNAME (Please wait)..."
    if (cd "$STAGE" && zip -q -r "$ZIPNAME" .); then
        local size
        size=$(du -h "$ZIPNAME" 2>/dev/null | cut -f1)
        rm -rf "$STAGE"
        ok "Environment exported successfully: $ZIPNAME ($size)"
        echo "BACKUP_ZIP=$ZIPNAME"
        return 0
    else
        err "Failed to create backup ZIP archive."
        rm -rf "$STAGE"
        return 1
    fi
}

import_backup() {
    stage "Importing Build Environment Backup"
    pkg install p7zip unzip rsync -y >/dev/null 2>&1 || { err "Failed to install extraction packages."; return 1; }
    
    ensure_directories
    local BACKUP_FILE="${1:-}"
    if [ -z "$BACKUP_FILE" ]; then
        BACKUP_FILE=$(ls -t "$OUTPUT_DIR"/builder-backup-complete-*.zip /sdcard/builder-backup-complete-*.zip 2>/dev/null | head -n1)
    fi
    if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then err "No backup archive found at: ${1:-$OUTPUT_DIR or /sdcard/}"; return 1; fi

    local size
    size=$(du -h "$BACKUP_FILE" 2>/dev/null | cut -f1)
    info "Found Backup Target: $(basename "$BACKUP_FILE") ($size)"

    TEMP_RESTORE="$HOME_DIR/.restore-temp"
    rm -rf "$TEMP_RESTORE"; mkdir -p "$TEMP_RESTORE"
    
    info "Unpacking backup archive ($size)... Please wait..."
    if unzip -q -o "$BACKUP_FILE" -d "$TEMP_RESTORE/"; then
        ok "Backup archive successfully extracted."
    else
        err "Failed to extract backup archive. File may be corrupted or disk full."
        rm -rf "$TEMP_RESTORE"
        return 1
    fi

    if [ -d "$TEMP_RESTORE/pkg-cache" ] && [ -n "$(ls -A "$TEMP_RESTORE/pkg-cache/"*.deb 2>/dev/null)" ]; then
        info "Installing offline system dependencies (.deb)..."
        dpkg -i --force-depends "$TEMP_RESTORE/pkg-cache/"*.deb || warn "Some packages encountered non-critical status."
        mkdir -p "$SDK_DIR/pkg-cache"
        cp "$TEMP_RESTORE/pkg-cache/"*.deb "$SDK_DIR/pkg-cache/" 2>/dev/null || true
    fi

    info "Restoring SDK platforms & Gradle cache..."
    [ -d "$TEMP_RESTORE/android-sdk" ] && rsync -a "$TEMP_RESTORE/android-sdk/" "$SDK_DIR/"
    [ -d "$TEMP_RESTORE/.gradle" ] && rsync -a "$TEMP_RESTORE/.gradle/" "$HOME_DIR/.gradle/"
    [ -d "$TEMP_RESTORE/wrapper-template" ] && rsync -a "$TEMP_RESTORE/wrapper-template/" "$WRAPPER_DIR/"
    
    mkdir -p "$SDK_DIR/ndk"
    local ndk_archive=$(find "$TEMP_RESTORE" "$SDK_DIR/ndk" /sdcard -maxdepth 2 \( -iname "android-ndk-*.7z" -o -iname "android-ndk-*.zip" \) 2>/dev/null | head -n1)
    if [ -n "$ndk_archive" ] && [ -f "$ndk_archive" ]; then
        info "Extracting Native Development Kit (NDK)..."
        mkdir -p "$SDK_DIR/ndk/tmp_ndk"
        if [[ "$ndk_archive" == *.7z ]]; then 7z x -o"$SDK_DIR/ndk/tmp_ndk" "$ndk_archive" -y >/dev/null
        else unzip -q "$ndk_archive" -d "$SDK_DIR/ndk/tmp_ndk"; fi
        local extracted_ndk_dir=$(find "$SDK_DIR/ndk/tmp_ndk" -maxdepth 2 -name "ndk-build" -exec dirname {} \; 2>/dev/null | head -n1)
        if [ -n "$extracted_ndk_dir" ]; then
            rm -rf "$NDK_DIR" 2>/dev/null
            mv "$extracted_ndk_dir" "$NDK_DIR"
            rm -rf "$SDK_DIR/ndk/tmp_ndk"
        fi
    fi
    rm -rf "$TEMP_RESTORE"
    fix_ndk_permissions
    ensure_wrapper_template
    ok "Environment restoration complete."
    return 0
}

# ============================================================================
# AUTO SETUP (identik build.sh — idempotent dpkg + resume marker)
# ============================================================================
SETUP_STATE_FILE="$APP_STATE_DIR/setup_state.txt"

setup_progress_state() {
    mkdir -p "$APP_STATE_DIR"
    printf '%s\n' "$1" > "$SETUP_STATE_FILE"
}

dpkg_package_installed() {
    local pkg="$1"
    dpkg -l "$pkg" 2>/dev/null | grep -q "^ii" || dpkg -s "$pkg" 2>/dev/null | grep -q "Status: install ok installed"
}

auto_setup() {
    stage "Initializing Runtime Toolchain Environment"
    detect_device_hardware
    info "Active Hardware Profile: $RAM_PROFILE (Heap: $DYNAMIC_JVM_MX)"

    if [ ! -d "$HOME_DIR/storage" ] || [ ! -w "/sdcard" ]; then yes | termux-setup-storage 2>/dev/null || true; sleep 1; fi
    mkdir -p "$SDK_DIR/pkg-cache"

    # ---- FIX v4: setup IDEMPOTENT + RESUME ----
    # Sebelum apt-get, cek paket yang SUDAH terpasang (dpkg -l). Hanya install
    # yang kurang. Jika semua sudah ada, apt-get install TIDAK dijalankan ulang
    # (sebelumnya: cancel -> toolchain dianggap belum lengkap -> apt-get install
    # dijalankan ulang -> exit 100 tanpa output).
    REQUIRED_PKGS="openjdk-17 python gradle android-tools rsync aapt aapt2 apksigner d8 aidl cmake ninja make wget curl git zip unzip perl p7zip clang"

    local missing_pkgs=""
    local pkg
    for pkg in $REQUIRED_PKGS; do
        if dpkg_package_installed "$pkg"; then
            ok "  sudah terpasang: $pkg"
        else
            missing_pkgs="$missing_pkgs $pkg"
        fi
    done

    if [ -n "$missing_pkgs" ]; then
        info "Paket yang belum terpasang:$missing_pkgs"
        info "Updating APT repositories and fetching core binaries..."
        apt-get update -y || true
        apt-get install -y -o Dir::Cache::archives="$SDK_DIR/pkg-cache" $missing_pkgs || {
            err "Failed to install system packages via APT."
            return 1
        }
    else
        ok "Semua paket sistem sudah terpasang \u2014 apt-get install dilewati."
    fi
    
    info "Building directory layouts & dummy targets..."
    mkdir -p "$SDK_DIR/platforms" "$SDK_DIR/build-tools" "$SDK_DIR/licenses" "$SDK_DIR/cmake"
    setup_dummy_build_tools "33.0.1"
    setup_dummy_build_tools "34.0.0"
    setup_dummy_cmake "3.22.1"
    setup_dummy_cmake "3.18.1"
    
    download_platform_sdk 34
    
    echo "24333f8a637bced5e17096433f01641e5f692d6e" > "$SDK_DIR/licenses/android-sdk-license"
    mkdir -p ~/.gradle
    cat > ~/.gradle/gradle.properties << EOF
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=$GRADLE_OPTS
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.daemon.performance.disable-logging=true
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$PREFIX/lib/jvm/java-17-openjdk
org.gradle.native=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true
android.builder.sdkDownload=false
org.gradle.workers.max=$DYNAMIC_WORKERS
EOF

    if [ ! -d "$NDK_DIR" ]; then
        info "Downloading Android Native Development Kit (NDK r25c)..."
        local NDK_URL="https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r25c-aarch64.zip"
        if wget -q --show-progress -O "$SDK_DIR/ndk.zip" "$NDK_URL"; then
            info "Extracting NDK archive..."
            mkdir -p "$SDK_DIR/ndk/tmp"
            if unzip -q "$SDK_DIR/ndk.zip" -d "$SDK_DIR/ndk/tmp"; then
                local extracted_ndk
                extracted_ndk=$(ls "$SDK_DIR/ndk/tmp" | head -n1)
                mkdir -p "$SDK_DIR/ndk/"
                mv "$SDK_DIR/ndk/tmp/$extracted_ndk" "$NDK_DIR"
                rm -rf "$SDK_DIR/ndk/tmp" "$SDK_DIR/ndk.zip"
                ok "NDK successfully installed to $NDK_DIR"
            else err "Failed to extract NDK zip."; fi
        else err "Failed to download NDK binary."; fi
    fi

    fix_ndk_permissions
    ensure_wrapper_template
    ensure_directories
    setup_progress_state "complete"
    ok "Toolchain setup complete."
    return 0
}

# ============================================================================
# NATIVE ONLY COMPILATION ENGINE (identik build.sh — non-interaktif)
# ============================================================================
build_native_project() {
    local src_path="${1:-}"
    if [ -z "$src_path" ]; then
        err "Usage: builder_core.sh native <project_path>"
        return 1
    fi
    termux-wake-lock 2>/dev/null || true
    if [ ! -d "$NDK_DIR" ]; then auto_setup || return 1; fi

    detect_device_hardware
    fix_ndk_permissions
    ensure_directories

    [ -d "$src_path" ] || { err "Target path '$src_path' not found."; return 1; }

    PROJECT_NAME=$(basename "$src_path")
    START_TIME=$(date +%s)

    echo -e "${BOLD}  Target Native: ${CYAN}${PROJECT_NAME}${RESET} | Profile: ${GREEN}${RAM_PROFILE}${RESET}"
    echo -e "${DIM}  ────────────────────────────────────────────────────── ${RESET}"

    stage "Synchronizing Workspace Targets (Precision Sync)"
    TARGET_DIR="$WORKSPACE/Native_$PROJECT_NAME"
    mkdir -p "$TARGET_DIR"
    
    rsync -a --delete \
      --exclude='build/' \
      --exclude='build_native/' \
      --exclude='libs/' \
      --exclude='obj/' \
      --exclude='.cxx/' \
      "$src_path/" "$TARGET_DIR/"
    ok "Workspace synchronized at: $TARGET_DIR"

    cd "$TARGET_DIR" || return 1

    TMP_LOG="$HOME_DIR/temp_native_build.log"
    rm -f "$TMP_LOG" "$LOG_FILE"
    BUILD_STATUS=1

    stage "Executing Native Toolchain Compilation"

    if [ -f "CMakeLists.txt" ]; then
        info "Build System Detected: CMake + Ninja"
        mkdir -p build_native && cd build_native
        
        cmake -G Ninja \
          -DCMAKE_TOOLCHAIN_FILE="$NDK_DIR/build/cmake/android.toolchain.cmake" \
          -DANDROID_ABI="arm64-v8a" \
          -DANDROID_PLATFORM="android-24" \
          -DCMAKE_BUILD_TYPE="Release" \
          .. 2>&1 | live_animated_streamer "$TMP_LOG"
        
        ninja -j"$DYNAMIC_NINJA_JOBS" 2>&1 | live_animated_streamer "$TMP_LOG"
        BUILD_STATUS=${PIPESTATUS[0]}
        cd "$TARGET_DIR" || return 1

    elif [ -f "Android.mk" ] || [ -f "jni/Android.mk" ]; then
        info "Build System Detected: NDK-Build (Android.mk)"
        
        "$NDK_DIR/ndk-build" NDK_PROJECT_PATH=. NDK_OUT=build NDK_LIBS_OUT=libs -j"$DYNAMIC_NINJA_JOBS" 2>&1 | live_animated_streamer "$TMP_LOG"
        BUILD_STATUS=${PIPESTATUS[0]}
    else
        err "No CMakeLists.txt or Android.mk found in $src_path"
        return 1
    fi

    END_TIME=$(date +%s)
    ELAPSED=$(( END_TIME - START_TIME ))

    NATIVE_OUT_DIR="$OUTPUT_DIR/Native/$PROJECT_NAME"
    mkdir -p "$NATIVE_OUT_DIR"
    
    NATIVE_FILES=$(find "$TARGET_DIR" -type f \( -name "*.so" -o -perm -111 \) ! -name "*.sh" ! -name "*.py" 2>/dev/null)

    if [ $BUILD_STATUS -eq 0 ] && [ -n "$NATIVE_FILES" ]; then
        find "$TARGET_DIR" -type f \( -name "*.so" -o -perm -111 \) ! -name "*.sh" ! -name "*.py" -exec cp {} "$NATIVE_OUT_DIR/" \; 2>/dev/null || true

        local out_count
        out_count=$(ls -A "$NATIVE_OUT_DIR" | wc -l)

        echo -e "${GREEN}${BOLD}"
        echo "  ╔══════════════════════════════════════════════════════════╗"
        echo "  ║  NATIVE BUILD SUCCESSFUL                         ║"
        printf "  ║  Time Elapsed : %-33s║\n" "${ELAPSED}s"
        printf "  ║  Binaries/Libs: %-33s║\n" "$out_count file(s) compiled"
        printf "  ║  Output Path  : %-33s║\n" "BuildOutputs/Native/$PROJECT_NAME/"
        echo "  ╚══════════════════════════════════════════════════════════╝"
        echo -e "${RESET}"
        return 0
    else
        cp "$TMP_LOG" "$LOG_FILE" 2>/dev/null
        echo -e "${RED}${BOLD}"
        echo "  ╔══════════════════════════════════════════════════════════╗"
        echo "  ║  NATIVE BUILD FAILED                             ║"
        printf "  ║  Time Elapsed : %-33s║\n" "${ELAPSED}s"
        echo "  ╚══════════════════════════════════════════════════════════╝"
        echo -e "${RESET}"
        echo -e "  ${YELLOW}Error Summary: ${RESET}"
        grep -E -i -A 2 "error:|exception|failure|FAILED" "$TMP_LOG" 2>/dev/null | head -n 10 || echo "  Refer to log output."
        echo -e "  Log file saved to: /sdcard/build-error.log"
        return 1
    fi
}

# ============================================================================
# CORE APK COMPILATION ENGINE (identik build.sh — non-interaktif)
# ============================================================================
build_project() {
    local build_type="${1:-debug}"
    local src_path="${2:-}"
    local clean_mode="false"
    [ "$build_type" = "clean" ] && { clean_mode="true"; build_type="debug"; }

    termux-wake-lock 2>/dev/null || true
    if [ ! -d "$SDK_DIR" ]; then auto_setup || return 1; fi

    detect_device_hardware
    fix_ndk_permissions
    ensure_wrapper_template
    ensure_directories

    export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
    export PATH="$PREFIX/bin:$JAVA_HOME/bin:$PATH"
    [ ! -f "$PREFIX/bin/ninja" ] && pkg install ninja -y >/dev/null 2>&1 || true

    if [ ! -w "/sdcard" ]; then yes | termux-setup-storage 2>/dev/null || true; sleep 1; fi

    if [ -z "$src_path" ]; then
        src_path="$(get_last_project 2>/dev/null || true)"
        if [ -z "$src_path" ]; then
            err "No project path given and no recent project saved."
            return 1
        fi
    fi

    [ -d "$src_path" ] || { err "Target path '$src_path' not found."; sleep 2; return 1; }

    save_last_project "$src_path"
    PROJECT_NAME=$(basename "$src_path")
    START_TIME=$(date +%s)

    echo -e "${BOLD}  Target APK: ${CYAN}${PROJECT_NAME}${RESET} (${build_type}) | Profile: ${GREEN}${RAM_PROFILE}${RESET}"
    echo -e "${DIM}  ────────────────────────────────────────────────────── ${RESET}"

    stage "Synchronizing Workspace Targets (Precision Sync)"
    TARGET_DIR="$WORKSPACE/$PROJECT_NAME"

    if [ "$clean_mode" = "true" ]; then
        info "Clean Build requested. Purging build workspace..."
        rm -rf "$TARGET_DIR"
    fi

    mkdir -p "$TARGET_DIR"
    rsync -a --delete \
      --exclude='build/' \
      --exclude='app/build/' \
      --exclude='.gradle/' \
      --exclude='.cxx/' \
      --exclude='.idea/' \
      "$src_path/" "$TARGET_DIR/"
    ok "Workspace synchronized at: $TARGET_DIR"

    cd "$TARGET_DIR" || return 1

    stage "Verifying SDK Platform & Build-Tools Dependencies"
    COMPILE_SDK=$(grep -rohE "(compileSdk|compileSdkVersion)\s*=?\s*[0-9]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9]+')
    [ -z "$COMPILE_SDK" ] && COMPILE_SDK=34
    download_platform_sdk "$COMPILE_SDK"

    BT_VER=$(grep -rohE "buildToolsVersion\s*=?\s*[\"'][0-9.]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9.]+')
    setup_dummy_build_tools "33.0.1"
    setup_dummy_build_tools "34.0.0"
    [ -n "$BT_VER" ] && setup_dummy_build_tools "$BT_VER"

    CMAKE_REQ_VER=$(grep -rohE "version\s*=?\s*[\"'][0-9.]+" . --include="*.gradle" --include="*.gradle.kts" | head -n1 | grep -oE '[0-9.]+')
    setup_dummy_cmake "3.22.1"
    setup_dummy_cmake "3.18.1"
    [ -n "$CMAKE_REQ_VER" ] && setup_dummy_cmake "$CMAKE_REQ_VER"

    mkdir -p gradle/wrapper
    cp "$WRAPPER_DIR/gradlew" . 2>/dev/null || true
    cp -r "$WRAPPER_DIR/gradle/"* gradle/ 2>/dev/null || true

    ROOT_GRADLE=$(find . -maxdepth 2 \( -name "build.gradle" -o -name "build.gradle.kts" \) ! -path "*/app/*" | head -n1)
    if [ -n "$ROOT_GRADLE" ]; then
        AGP_VER=$(detect_agp_version "$ROOT_GRADLE")
        [ -n "$AGP_VER" ] && GRADLE_VER=$(agp_to_gradle "$AGP_VER") || GRADLE_VER="8.7"
    else GRADLE_VER="8.7"; fi

    sed -i "s/gradle-[0-9.]*-all.zip/gradle-$GRADLE_VER-all.zip/g" gradle/wrapper/gradle-wrapper.properties 2>/dev/null || true
    sed -i 's/\r$//' gradlew 2>/dev/null || true
    sed -i 's/DEFAULT_JVM_OPTS=.*/DEFAULT_JVM_OPTS="-Xmx64m -Xms64m"/' gradlew 2>/dev/null || true
    chmod +x gradlew 2>/dev/null || true

    INSTALLED_NDK_VER=$(basename "$NDK_DIR" 2>/dev/null || echo "25.2.9519653")
    
    while IFS= read -r f_gradle; do clean_toolchains_python "$f_gradle"; done < <(find . -type f \( -name "*.gradle" -o -name "*.gradle.kts" -o -name "settings.gradle*" \) ! -path "*/build/*" 2>/dev/null)
    while IFS= read -r g_file; do inject_sdk_and_ndk "$g_file" "$COMPILE_SDK" "$INSTALLED_NDK_VER"; done < <(find . -maxdepth 3 \( -name "build.gradle" -o -name "build.gradle.kts" \) ! -path "*/build/*" 2>/dev/null)

    cat >> gradle.properties << EOF
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=$PREFIX/lib/jvm/java-17-openjdk
org.gradle.native=false
systemProp.org.gradle.native=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true
org.gradle.caching=true
org.gradle.daemon.performance.disable-logging=true
android.aapt2FromMavenOverride=$PREFIX/bin/aapt2
org.gradle.workers.max=$DYNAMIC_WORKERS
org.gradle.parallel=false
org.gradle.jvmargs=$GRADLE_OPTS
EOF

    local active_cmake_ver="${CMAKE_REQ_VER:-3.22.1}"
    cat > local.properties << EOF
sdk.dir=$SDK_DIR
cmake.dir=$SDK_DIR/cmake/$active_cmake_ver
EOF

    stage "Executing Gradle Build Target (Live Spinner Output)"

    TMP_LOG="$HOME_DIR/temp_build.log"
    rm -f "$TMP_LOG" "$LOG_FILE"

    FLAGS="-Dorg.gradle.native=false \
           -Dorg.gradle.java.installations.auto-detect=false \
           -Dorg.gradle.java.installations.auto-download=false \
           -Pandroid.injected.build.abi=arm64-v8a \
           -Pandroid.ninja.jobs=$DYNAMIC_NINJA_JOBS \
           --no-daemon \
           --no-parallel \
           --console=plain \
           --build-cache"

    if [ "$clean_mode" = "true" ]; then
        FLAGS="$FLAGS --rerun-tasks"
    fi

    if [ "$build_type" = "release" ]; then
        ./gradlew assembleRelease $FLAGS 2>&1 | live_animated_streamer "$TMP_LOG"
        BUILD_STATUS=${PIPESTATUS[0]}
    else
        ./gradlew assembleDebug $FLAGS 2>&1 | live_animated_streamer "$TMP_LOG"
        BUILD_STATUS=${PIPESTATUS[0]}
    fi

    END_TIME=$(date +%s)
    ELAPSED=$(( END_TIME - START_TIME ))

    APK_FILE=$(find "$TARGET_DIR" -type f -name "*.apk" ! -name "*-unsigned.apk" 2>/dev/null | head -n1)

    if [ $BUILD_STATUS -eq 0 ] && [ -n "$APK_FILE" ]; then
        local apk_size
        apk_size=$(du -sh "$APK_FILE" 2>/dev/null | cut -f1)
        [ -z "$apk_size" ] && apk_size="Unknown"

        OUT_NAME="${PROJECT_NAME}-${build_type}.apk"
        OUT_TARGET_FILE="$OUTPUT_DIR/$OUT_NAME"
        
        cp "$APK_FILE" "$OUT_TARGET_FILE" 2>/dev/null || true
        
        DEST_PROJ_DIR="$src_path/app/build/outputs/apk/debug"
        mkdir -p "$DEST_PROJ_DIR" 2>/dev/null || true
        cp "$APK_FILE" "$DEST_PROJ_DIR/$OUT_NAME" 2>/dev/null || true

        echo -e "${GREEN}${BOLD}"
        echo "  ╔══════════════════════════════════════════════════════════╗"
        echo "  ║  BUILD SUCCESSFUL                                ║"
        printf "  ║  Time Elapsed : %-33s║\n" "${ELAPSED}s"
        printf "  ║  Artifact Size: %-33s║\n" "$apk_size"
        printf "  ║  Output Path  : %-33s║\n" "BuildOutputs/$OUT_NAME"
        echo "  ╚══════════════════════════════════════════════════════════╝"
        echo -e "${RESET}"
        return 0
    else
        cp "$TMP_LOG" "$LOG_FILE" 2>/dev/null
        echo -e "${RED}${BOLD}"
        echo "  ╔══════════════════════════════════════════════════════════╗"
        echo "  ║  BUILD FAILED                                    ║"
        printf "  ║  Time Elapsed : %-33s║\n" "${ELAPSED}s"
        echo "  ╚══════════════════════════════════════════════════════════╝"
        echo -e "${RESET}"

        echo -e "  ${YELLOW}Error Summary: ${RESET}"
        grep -E -i -A 2 "error:|exception|failure|FAILED" "$TMP_LOG" 2>/dev/null | head -n 10 || echo "  Refer to log output."
        echo -e "  Log file saved to: /sdcard/build-error.log"
        return 1
    fi
}

# ============================================================================
# MAIN ENTRYPOINT — MODE NON-INTERAKTIF (dipanggil app via RUN_COMMAND)
# ============================================================================
cmd="${1:-}"
shift || true

case "$cmd" in
    build)
        # builder_core.sh build <project> [debug|release|clean]
        project="${1:-}"
        mode="${2:-debug}"
        [ -z "$project" ] && { err "Usage: builder_core.sh build <project_path> [debug|release|clean]"; exit 1; }
        build_project "$mode" "$project"
        exit $?
        ;;
    import)
        # builder_core.sh import <backup.zip>
        backup_zip="${1:-}"
        import_backup "$backup_zip"
        exit $?
        ;;
    export)
        # builder_core.sh export [dest_dir]
        dest="${1:-$OUTPUT_DIR}"
        export_backup "$dest"
        exit $?
        ;;
    setup)
        auto_setup
        exit $?
        ;;
    native)
        build_native_project "${1:-}"
        exit $?
        ;;
    *)
        echo "Usage: builder_core.sh {build <project> [debug|release|clean] | import <zip> | export [dir] | setup | native <project>}"
        exit 1
        ;;
esac
