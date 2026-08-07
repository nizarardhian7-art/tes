# CHANGELOG — TermuxMod APK Builder v2 (2026-08-08)

Perbaikan menyeluruh pada builder sesuai keluhan user. Semua perbaikan
telah di-compile-check dengan Kotlin 2.2.20 + diuji dengan unit test.

---

## 🔴 Bug 1 — `Unresolved reference: compileSdk` di settings.gradle.kts (BUILD FAILED 5m 6s)

**Penyebab:** `GradleProjectPatcher.injectSdkAndNdk()` menyisipkan
`compileSdk = 36` / `ndkVersion = "..."` **DI LUAR blok `android { }`**
(fallback lama menambahkan baris di akhir file). Pada KTS, `compileSdk`
di luar blok android di-compile sebagai referensi variabel bebas →
`Unresolved reference: compileSdk`.

**Perbaikan:**
- Penyisipan sekarang **selalu di dalam blok `android { }`** yang sudah ada.
- Jika file tidak punya blok android (mis. `settings.gradle.kts`),
  file **dilewati** (tidak diinject) — tidak pernah menambah baris liar.
- Handle semua bentuk deklarasi Groovy & KTS:
  `compileSdk 34`, `compileSdk = 34`, `compileSdkVersion 34`,
  `compileSdkVersion = 34`, `compileSdkVersion project.properties.xxx.toInteger()`.
- `detectCompileSdk()` sekarang membaca **`gradle.properties`** juga
  (project ini menyimpan `compileSdkVersion=36` di sana).
- `Matcher.quoteReplacement` untuk mencegah error `$` di replaceAll.

## 🔴 Bug 2 — Import zip backup tapi tetap download ulang NDK/platform

**Penyebab ganda:**
1. `BackupManager.exportEnvironmentBackup()` memakai
   `rsync --exclude='ndk/'` → **NDK tidak pernah masuk zip backup**.
2. `ToolchainManager.isNdkInstalled()` hanya cek `ndk-build` → NDK rusak /
   tidak lengkap tetap dianggap "terpasang", atau sebaliknya AGP mencari versi
   berbeda (lihat Bug 3) → download ulang.
3. `.gradle/wrapper/dists/` (distribusi Gradle) tidak di-backup → Gradle
   selalu download ulang setelah import.

**Perbaikan:**
- Export menyertakan: `android-sdk/` (TERMASUK `ndk/`), `.gradle/wrapper/dists/`
  + `.gradle/caches/`, `wrapper-template/`, `pkg-cache/` (deb APT).
- Import: verifikasi struktur zip, restore rsync tanpa `--delete`
  (komponen valid yang sudah ada tetap dipertahankan), install `.deb`,
  dan log jelas apa yang direstore.
- `isNdkInstalled()` sekarang cek `ndk-build` **dan** `prebuilt/` /
  `source.properties` → NDK tidak lengkap akan di-download ulang sekali.
- `isGradleDistributionPresent(ver)` cek zip di `GRADLE_USER_HOME/wrapper/dists`
  → Gradle yang sudah ada tidak diunduh ulang.
- `ensurePlatformSdk()` skip download jika platform sudah valid.

## 🔴 Bug 3 — Versi NDK tidak konsisten (25.2.9519653 vs 29.0.14206865)

**Penyebab:** `Models.kt` menetapkan `DEFAULT_NDK_VERSION = "25.2.9519653"`,
tapi `ToolchainManager` menginstall NDK r29 (`29.0.14206865`). AGP membaca
`ndkVersion` dari project → mencari `ndk/25.2.9519653` yang tidak ada →
download ulang / gagal.

**Perbaikan:** Semua referensi diseragamkan ke **`29.0.14206865`**
(`BuilderPaths.DEFAULT_NDK_VERSION`, `gradle.properties` project,
`app/build.gradle`, `builder-core/build.gradle`).

## 🔴 Bug 4 — Download platform-34 r4 → 404, fallback AOSP

**Penyebab:** URL `platform-34_r04.zip` (dan `_r01`/`_r02`) **tidak ada** di
Google. Platform API 34 resmi bernama **`platform-34-ext7_r03.zip`**
(diverifikasi langsung ke dl.google.com, 2026-08-08).

**Perbaikan:**
- Tabel `DependencyCatalog.PLATFORM_ZIP_FALLBACK` berisi nama file BENAR
  untuk API 24–36 (semua diverifikasi valid).
- `resolvePlatformZipFileName()` query `repository2-1.xml` Google secara
  live untuk API level lain.
- Fallback AOSP tetap ada (hanya android.jar) tapi tidak lagi jadi pilihan
  utama — log jelas kapan dipakai.

## 🟡 Bug 5 — Log tidak terstruktur (naik-turun, sulit dibaca)

**Perbaikan:**
- Modul baru `BuildLog` — semua pesan lewat satu format dengan prefix
  `@@LEVEL@@` (SECTION / STEP / INFO / OK / WARN / ERROR), indentation,
  section headers `=====`, timestamp.
- `LogStreamParser` versi 2: paham prefix level, klasifikasi baris Gradle
  (`> Task`, `BUILD FAILED`, `error:`, `warning:`, `%`), dan **handle `\r`**
  progress bar (baris di-refresh, tidak menumpuk naik-turun).
- `BuildDashboardFragment`: warna per level (section oranye tebal, step biru,
  ok hijau, warn kuning, error merah), auto-scroll lebih halus.
- `BuildOrchestrator`: progress persen konsisten 0→100 sesuai 8 fase
  (SCANNING → TOOLCHAIN → SYNC → PATCH → BUILD → COPY → SUCCESS/FAILED).

## 🟡 Bug 6 — Error handling platform SDK diabaikan

**Perbaikan:** `BuildOrchestrator` sekarang **memeriksa return value**
`ensurePlatformSdk()` — jika platform tidak tersedia, build GAGAL dengan pesan
jelas (sebelumnya diabaikan → build lanjut dan gagal membingungkan di tengah).

## 🟡 Bug 7 — AGP→Gradle mapping salah

**Perbaikan:** `AGP_TO_GRADLE` memakai tabel **minimum resmi** Android
Developers (8.13.x→8.13, 9.0→9.1.0). Versi lama memakai "ekspektasi" yang
menurunkan Gradle ke versi terlalu lama.

## 🟡 Bug 8 — `updateWrapperGradleVersion` regex salah

**Perbaikan:** `Pattern.find()` (tidak tersedia di Kotlin) → `Pattern.matcher()`,
dan preserve suffix `bin`/`all` asli.

## 🟡 Bug 9 — Groovy `project.properties.compileSdkVersion.toInteger()` NPE

**Perbaikan:** Semua module `build.gradle` memakai `findProperty(...) ?: default`
yang defensif.

---

## ✅ Validasi

- 14 file Kotlin builder-core **compile sukses** dengan Kotlin 2.2.20.
- **30+ unit test** lulus:
  - Patcher: injection KTS/Groovy, skip settings.gradle.kts, sanitize Java 17,
    wrapper version, AGP→Gradle, detectCompileSdk.
  - Log: format BuildLog, klasifikasi baris Gradle/Kotlin, cleanLine `\r`.
  - Toolchain: logika skip-download (platform/NDK/gradle dists).
  - Real-world: patch file build.gradle asli project ini tanpa duplikasi.
- XML resource valid (0 error).

## 📄 Dokumen terkait

- `DEPENDENCIES.md` — daftar lengkap dependency + URL resmi + cara offline.
