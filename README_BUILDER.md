# TermuxMod — APK Builder (Engine Kotlin Native)

Modul **`:builder-core`** memindahkan seluruh logic `build.sh` (Android Native Build
Toolchain) ke kode **Kotlin/Java** yang berjalan di dalam aplikasi — bukan script bash.

Dokumen ini menjelaskan: struktur module baru, pemetaan build.sh → Kotlin, cara
build via GitHub Actions, dan catatan arsitektur.

---

## 1. Struktur Module Baru

```
termux-mod-builder/
├── app/                          # UI + service (Android)
│   ├── src/main/java/com/termux/app/
│   │   ├── TermuxApplication.java          # + BuilderModule.init()
│   │   ├── builder/
│   │   │   ├── BuilderMainActivity.kt      # Activity dashboard native
│   │   │   ├── BuildDashboardFragment.kt   # Fragment UI (progress, log, tombol)
│   │   │   ├── BuildForegroundService.kt   # FGS: build background + notification
│   │   │   └── BuilderViewModel.kt         # LiveData bridge UI ↔ service
│   └── src/main/AndroidManifest.xml        # + activity, service, permissions
│
├── builder-core/                 # ENGINE KOTLIN MURNI (library Android)
│   └── src/main/kotlin/com/termux/builder/
│       ├── BuilderModule.kt                 # init state dirs (dari Application)
│       ├── model/
│       │   ├── Models.kt                    # BuildConfig, BuildResult, BuildProgress,
│       │   │                                #   BuildMode/BuildType, HardwareProfile,
│       │   │                                #   ProjectScanResult, CommandResult, BuilderPaths
│       ├── exec/
│       │   └── ProcessExecutor.kt           # eksekusi AppShell (Runner.APP_SHELL)
│       ├── scan/
│       │   └── ProjectScanner.kt            # scan project Android & native
│       ├── toolchain/
│       │   ├── HardwareDetector.kt          # RAM → profile JVM/workers/ninja
│       │   └── ToolchainManager.kt          # apt, SDK, dummy build-tools/cmake, NDK, wrapper
│       ├── patch/
│       │   └── GradleProjectPatcher.kt      # sanitize Java 17, inject SDK/NDK, AGP→Gradle
│       ├── sync/
│       │   └── WorkspaceSync.kt             # rsync presisi + fallback copy
│       ├── log/
│       │   └── LogStreamParser.kt           # parse output Gradle/CMake/Ninja
│       ├── backup/
│       │   └── BackupManager.kt             # backup/rollback + export/import environment
│       ├── orchestrator/
│       │   ├── BuildOrchestrator.kt         # state machine build APK
│       │   └── NativeBuildEngine.kt         # build native-only (CMake/Ninja/ndk-build)
│       └── repo/
│           └── BuildRepository.kt           # SharedPreferences last project + result
│
├── termux-shared/  termux-shared engine (AppShell, ExecutionCommand, TermuxShellEnvironment)
├── terminal-emulator/  terminal-view/
├── settings.gradle      # + ':builder-core'
├── build.gradle         # + classpath kotlin-gradle-plugin:2.2.20
└── gradle/wrapper/      # Gradle 9.2.1 (fork asli)
```

**Versi kunci:** AGP 8.13.2 · Gradle 9.2.1 · Kotlin Gradle Plugin **2.2.20** ·
compileSdk 36 · minSdk 21 · Java 17 toolchain (bytecode library 1.8 agar konsumsi
dari module Java 1.8 aman).

---

## 2. Pemetaan build.sh → Kotlin

| Fungsi build.sh                 | Class Kotlin                            |
|---------------------------------|-----------------------------------------|
| `detect_device_hardware()`      | `HardwareDetector`                      |
| `collect_android_projects()`    | `ProjectScanner` (Android)              |
| `collect_native_projects()`     | `ProjectScanner` (Native)               |
| `auto_setup()` (apt/SDK/NDK)    | `ToolchainManager.setupToolchain()`     |
| `setup_dummy_build_tools()`     | `ToolchainManager.setupDummyBuildTools()` |
| `setup_dummy_cmake()`           | `ToolchainManager.setupDummyCmake()`    |
| `download_platform_sdk()`       | `ToolchainManager.downloadPlatformSdk()`|
| `install_ndk_*()`               | `ToolchainManager.installNdk()`         |
| `setup_wrapper()`               | `ToolchainManager.ensureWrapperTemplate()` |
| `sync_project_*` (rsync)        | `WorkspaceSync.sync()`                  |
| `clean_toolchains_python()`     | `GradleProjectPatcher.sanitizeJava17()` |
| `inject_sdk_and_ndk()`          | `GradleProjectPatcher.injectSdkAndNdk()`|
| `build_project()` (gradlew)     | `BuildOrchestrator.buildApk()`          |
| `build_native_project()`        | `NativeBuildEngine.buildNative()`       |
| `export_backup()` / `import_backup()` | `BackupManager.export/importEnvironmentBackup()` |
| `save_last_project` / `get_last_project` | `BuildRepository`             |
| `parse_log` / error detection   | `LogStreamParser`                       |

Seluruh eksekusi shell memakai **AppShell** (`Runner.APP_SHELL`) dari
`termux-shared` via `ProcessExecutor` — tidak ada `ProcessBuilder`/`Runtime.exec`
langsung di kode builder.

---

## 3. State Machine Build (BuildOrchestrator)

```
SCANNING ──> TOOLCHAIN_SETUP ──> SYNCING ──> PATCHING ──> BUILDING ──> COPYING
   │             │                  │            │            │            │
   └─────────────┴──────────────────┴────────────┴────────────┴────────────┴──> SUCCESS
                                                                    │
                                                                    └──> FAILED / CANCELLED
```

1. **SCANNING** — `ProjectScanner.scan()` deteksi project Android & native di path.
2. **HARDWARE** — `HardwareDetector.detect()` → profile JVM/workers/ninja.
3. **TOOLCHAIN_SETUP** — `ToolchainManager.setupToolchain()` (apt, SDK layout,
   dummy build-tools/cmake, platform SDK sesuai compileSdk, NDK r25c, wrapper).
4. **SYNCING** — `WorkspaceSync.sync()` rsync presisi ke `~/workspace/<project>`.
5. **PATCHING** — `GradleProjectPatcher` backup → sanitize Java 17 → inject
   compileSdk/ndkVersion → mapping AGP→Gradle; `local.properties` +
   `gradle.properties` overrides.
6. **BUILDING** — `./gradlew assembleDebug|assembleRelease --no-daemon --console=plain`
   via AppShell; progress & error diparse `LogStreamParser`.
7. **COPYING** — temukan APK → salin ke `/sdcard/BuildOutputs/<project>-<type>.apk`
   + kembali ke `app/build/outputs/apk/...`.

Cancellation: `ProcessExecutor.cancel()` → `AppShell.killIfExecuting()` (SIGKILL),
rollback file patch via `BackupManager.rollbackAll()`.

---

## 4. Cara Build via GitHub Actions

Workflow `.github/workflows/build.yml` **tidak berubah** — module `:builder-core`
ter-compile otomatis karena `:app` bergantung padanya.

### Debug (default)
- **Trigger:** push/PR ke `master`, atau manual dengan `build_type: debug`.
- Hasil: artifact `termux-app_<version>_apt-android-7-github-debug_arm64-v8a.apk` + sha256sums.

### Release
- **Trigger:** publish GitHub Release, atau manual dengan `build_type: release`.
- Hasil: APK release + sha256sums, auto-attach ke release (via `hub release edit`).

### Cara pakai
1. Push fork ke GitHub (repo baru).
2. Buka **Actions** → **Build APK** → **Run workflow** (pilih `debug`).
3. Unduh artifact dari halaman run.

> Catatan: workflow memakai `vars.SIGNING_*` hanya jika di-set di repository
> Settings → Variables; tanpa itu build release tetap berjalan unsigned (debug).

---

## 5. Catatan Arsitektur & Batasan

- **`:builder-core` adalah library Android** (bukan aplikasi) agar bisa memakai
  `Context` untuk AppShell & resources; tidak ada UI di dalamnya (murni engine).
- **Bytecode 1.8** untuk kompatibilitas konsumsi dari module Java lama (`:app`).
- **Semua proses eksternal** (apt/gradle/cmake/ninja/rsync) tetap dieksekusi via
  AppShell; yang "dipindahkan ke Kotlin" adalah *orchestration & logic* build.sh,
  bukan implementasi ulang toolchain.
- **NDK r25c** diunduh dari `Lzhiyong/termux-ndk` (aarch64) bila belum ada.
- **Build sangat lama di perangkat** — gunakan GitHub Actions untuk build resmi;
  engine on-device ditujukan untuk iterasi cepat/offline.

---

## 6. Verifikasi Lokal (Opsional)

Tanpa menjalankan `gradlew build` (lama), Anda bisa memeriksa konsistensi:

```bash
# Struktur module baru
ls builder-core/src/main/kotlin/com/termux/builder/

# Dependency app → builder-core
grep builder-core app/build.gradle settings.gradle

# Plugin Kotlin di root
grep kotlin-gradle-plugin build.gradle
```
