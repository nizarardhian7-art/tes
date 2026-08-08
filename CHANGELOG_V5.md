# CHANGELOG V5 — Fix Compile Error `builder-core → app` Dependency Cycle

**Tanggal:** 2026-08-08
**Project:** Termux Mod Builder (termux-mod-builder_v5)
**Base:** v5 (termux-mod-builder_v5) — zip v5 memiliki bug compile yang dilaporkan di GitHub Actions CI.

---

## 🐛 Akar Masalah

Build CI gagal pada `:builder-core:compileDebugKotlin` dengan **Unresolved reference**:

```
e: .../builder-core/.../backup/BackupManager.kt:4:19   Unresolved reference 'app'.
e: .../builder-core/.../backup/BackupManager.kt:38:13  Unresolved reference 'BuilderScriptLauncher'.
e: .../builder-core/.../backup/BackupManager.kt:54:13  Unresolved reference 'BuilderScriptLauncher'.
e: .../builder-core/.../backup/BackupManager.kt:55:20  Unresolved reference 'BuilderScriptLauncher'.
e: .../builder-core/.../orchestrator/BuildOrchestrator.kt:4:19  Unresolved reference 'app'.
e: .../builder-core/.../orchestrator/BuildOrchestrator.kt:49:9  Unresolved reference 'BuilderScriptLauncher'.
e: .../builder-core/.../orchestrator/BuildOrchestrator.kt:55:9  Unresolved reference 'BuilderScriptLauncher'.
e: .../builder-core/.../orchestrator/BuildOrchestrator.kt:63:9  Unresolved reference 'BuilderScriptLauncher'.
```

**Penyebab:** `BuilderScriptLauncher.kt` berada di module **`:app`** (`com.termux.app.builder`),
sementara `BackupManager.kt` dan `BuildOrchestrator.kt` (module **`:builder-core`**) meng-import-nya.
Module `:builder-core` TIDAK boleh bergantung pada `:app` (bukan dependency, dan Gradle memproses
`:builder-core` sebelum `:app` → simbol tidak ditemukan).

---

## ✅ Perbaikan

### 1. Pindahkan `BuilderScriptLauncher` ke module `:builder-core`
- **Dari:** `app/src/main/java/com/termux/app/builder/BuilderScriptLauncher.kt` (package `com.termux.app.builder`)
- **Ke:** `builder-core/src/main/kotlin/com/termux/builder/script/BuilderScriptLauncher.kt` (package `com.termux.builder.script`)
- Launcher hanya membangun intent `com.termux.service_execute` (tidak butuh UI) → aman di builder-core.
- `app/src/main/java/com/termux/app/builder/` kini hanya berisi: `BuildDashboardFragment.kt`, `BuilderMainActivity.kt`, `BuilderViewModel.kt` (UI murni).

### 2. Perbaiki import di kedua consumer builder-core
- `BackupManager.kt` line 4: `import com.termux.builder.script.BuilderScriptLauncher`
- `BuildOrchestrator.kt` line 4: `import com.termux.builder.script.BuilderScriptLauncher`

### 3. Bundle script `builder_core.sh` di builder-core (satu sumber)
- **Dari:** `app/src/main/res/raw/builder_core.sh`
- **Ke:** `builder-core/src/main/res/raw/builder_core.sh`
- Launcher membaca via `R.raw.builder_core` (namespace `com.termux.builder` — konsisten dengan package launcher).
- File script **identik** (md5 `e599ca1f890427da945a477f1eed26b2`, 45,366 bytes) — tidak ada perubahan logika build.
- Direktori `app/src/main/res/raw/` dihapus (tidak ada lagi duplikasi/ketergantungan app).

### 4. Tidak ada referensi silang tersisa
- `grep -rn "com.termux.app.builder" builder-core/src/` → **NONE (clean)**.
- Module `:app` masih meng-import `:builder-core` (arah yang benar) — lihat `app/build.gradle:155`.

---

## 🔬 Validasi

Compile-check dengan **kotlinc 2.2.20** terhadap stub (EXIT=0 untuk kedua module):

```
# builder-core (7 package: backup, exec, log, model, orchestrator, patch, repo, scan, script, sync, toolchain, BuilderModule)
kotlinc $(find builder-core/src/main/kotlin -name "*.kt") -cp out-stubs -d /tmp/v6core_fresh   → EXIT=0

# app (UI builder: BuildDashboardFragment, BuilderMainActivity, BuilderViewModel)
kotlinc app/src/main/java/com/termux/app/builder/*.kt -cp "out-stubs:/tmp/v6core_fresh" -d /tmp/v6app_fresh → EXIT=0
```

Class ter-generate:
- `com.termux.builder.script.BuilderScriptLauncher.class`
- `com.termux.builder.backup.BackupManager.class`
- `com.termux.builder.orchestrator.BuildOrchestrator.class`
- `com.termux.app.builder.BuildDashboardFragment.class` / `BuilderMainActivity.class` / `BuilderViewModel.class`

⚠️ Stub (`out-stubs/`) adalah **compile-check only**, bukan deliverable — tidak masuk ke zip project.

---

## 📦 Isi Zip v6

`termux-mod-builder_v5/` — root folder lengkap (558 file), sama seperti v5 kecuali:
- `builder-core/src/main/kotlin/com/termux/builder/script/BuilderScriptLauncher.kt` (**baru**, pindahan dari app)
- `builder-core/src/main/res/raw/builder_core.sh` (**baru**, pindahan dari app res/raw)
- `builder-core/src/main/kotlin/com/termux/builder/backup/BackupManager.kt` (import diperbaiki)
- `builder-core/src/main/kotlin/com/termux/builder/orchestrator/BuildOrchestrator.kt` (import diperbaiki)
- `app/src/main/java/com/termux/app/builder/BuilderScriptLauncher.kt` (**dihapus**)
- `app/src/main/res/raw/builder_core.sh` (**dihapus**)

**Tidak ada perubahan lain** — arsitektur v5 (UI Kotlin + `builder_core.sh` di terminal Termux) tetap.
