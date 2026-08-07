# 📦 Daftar Dependency APK Builder (Untuk Backup Zip Offline)

> Dokumen ini menjelaskan **semua komponen** yang dibutuhkan builder untuk build APK,
> **URL resmi** masing-masing, dan **cara memasukkannya ke backup zip** agar
> builder TIDAK mendownload ulang (setup offline penuh).

**Perilaku v2 (setelah perbaikan):** builder sekarang MENGENALI komponen yang sudah
ada & valid, lalu **skip download**. Kunci validasi:

| Komponen | Lokasi di device | Cara deteksi "sudah ada" |
|---|---|---|
| Platform SDK | `~/android-sdk/platforms/android-<api>/` | ada `android.jar` > 1 MB |
| NDK | `~/android-sdk/ndk/<version>/` | ada `ndk-build` + `source.properties` |
| Build-tools | `~/android-sdk/build-tools/<version>/` | ada `source.properties` + `aapt2` |
| CMake | `~/android-sdk/cmake/<version>/` | ada `source.properties` + `bin/cmake` |
| Gradle dist | `~/.gradle/wrapper/dists/gradle-<ver>-bin/<hash>/` | ada `gradle-<ver>-bin.zip` |
| Wrapper jar | `~/android-sdk/wrapper-template/gradle/wrapper/` | ada `gradle-wrapper.jar` > 10 KB |
| Paket APT | `~/android-sdk/pkg-cache/*.deb` | diinstall ulang saat import |

---

## 1️⃣ Platform Android SDK (WAJIB)

Builder butuh `android.jar` sesuai `compileSdkVersion` project (default 34, project ini 36).

| API | File ZIP resmi Google | URL resmi |
|---|---|---|
| **36** | `platform-36_r01.zip` | `https://dl.google.com/android/repository/platform-36_r01.zip` |
| **35** | `platform-35_r01.zip` | `https://dl.google.com/android/repository/platform-35_r01.zip` |
| **34** ⚠️ | `platform-34-ext7_r03.zip` | `https://dl.google.com/android/repository/platform-34-ext7_r03.zip` |
| **33** | `platform-33_r01.zip` | `https://dl.google.com/android/repository/platform-33_r01.zip` |
| **32** | `platform-32_r01.zip` | `https://dl.google.com/android/repository/platform-32_r01.zip` |
| **31** | `platform-31_r01.zip` | `https://dl.google.com/android/repository/platform-31_r01.zip` |

> ⚠️ **PENTING (Bug 404 yang diperbaiki):** Google TIDAK punya `platform-34_r01.zip` /
> `platform-34_r02.zip` / `platform-34_r04.zip`. Semua URL itu **404**. API 34 hanya
> tersedia sebagai **`platform-34-ext7_r03.zip`** (revision 3). Builder v2 sudah
> otomatis memakai nama yang benar, atau membaca `repository2-1.xml` Google secara
> live untuk level API lain.

**Cara offline:** letakkan folder hasil ekstrak di dalam zip backup:
```
android-sdk/platforms/android-34/
├── android.jar            (WAJIB, > 1 MB)
├── data/                  (isi folder data SDK — wajib untuk build)
├── framework.aidl
├── source.properties
└── build.prop
```

## 2️⃣ Android NDK (WAJIB untuk project native / termux)

| Versi | File | URL resmi |
|---|---|---|
| **r29** (default, aarch64) | `android-ndk-r29-aarch64.7z` | `https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.7z` |
| r25c (fallback) | `android-ndk-r25c-aarch64.zip` | `https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r25c-aarch64.zip` |

> ⚠️ **Bug v1:** `DEFAULT_NDK_VERSION` = `25.2.9519653` tapi NDK yang diinstall
> versi `29.0.14206865` → AGP mencari folder `ndk/25.2.9519653` yang tidak ada →
> **mencoba download ulang / gagal**. Sekarang konsisten `29.0.14206865`.

**Cara offline:**
```
android-sdk/ndk/29.0.14206865/
├── ndk-build
├── source.properties
├── build/
├── prebuilt/linux-aarch64/
├── toolchains/
└── sysroot/
```

## 3️⃣ Build-tools

| Versi | File | URL resmi |
|---|---|---|
| 34.0.0 | `build-tools_r34-linux.zip` | `https://dl.google.com/android/repository/build-tools_r34-linux.zip` |
| 33.0.1 | `build-tools_r33.0.1-linux.zip` | `https://dl.google.com/android/repository/build-tools_r33.0.1-linux.zip` |

**Cara offline:**
```
android-sdk/build-tools/34.0.0/
├── aapt2, aapt, aidl, d8, zipalign, apksigner, dx
├── lib/
└── source.properties
```

## 4️⃣ CMake

| Versi | File | URL resmi |
|---|---|---|
| 3.22.1 | `cmake-3.22.1-linux.zip` | `https://dl.google.com/android/repository/cmake-3.22.1-linux.zip` |
| 3.18.1 | `cmake-3.18.1-linux.zip` | `https://dl.google.com/android/repository/cmake-3.18.1-linux.zip` |

**Cara offline:**
```
android-sdk/cmake/3.22.1/
├── bin/cmake, bin/ninja
└── source.properties
```

## 5️⃣ Gradle distribution (AGAR TIDAK DOWNLOAD ULANG)

| AGP | Gradle minimum |
|---|---|
| 8.13.x | **8.13** |
| 8.12.x | 8.13 |
| 8.11.x | 8.13 |
| 9.0.x | 9.1.0 |

**URL distribusi:**
```
https://services.gradle.org/distributions/gradle-8.13-bin.zip
https://services.gradle.org/distributions/gradle-9.2.1-bin.zip
```

**Cara offline (yang benar — gradle wrapper MENCARI di sini):**
```
.gradle/wrapper/dists/gradle-8.13-bin/<hash-acak>/gradle-8.13-bin.zip
.gradle/wrapper/dists/gradle-9.2.1-bin/<hash-acak>/gradle-9.2.1-bin.zip
```
> Hash folder acak dibuat Gradle sendiri. Cara termudah: **jalankan sekali build dengan
> internet**, biarkan Gradle selesai download, lalu EXPORT backup dari menu app —
> folder dists akan ikut tersalin otomatis. Import berikutnya tidak download lagi.

## 6️⃣ JDK (OpenJDK 17)

Diinstall otomatis via `apt-get install openjdk-17` saat setup (bukan bagian zip).
Untuk offline penuh, paket `.deb` bisa ditaruh di `pkg-cache/`:
```
pkg-cache/openjdk-17-jdk-headless_*.deb
pkg-cache/openjdk-17-jre-headless_*.deb
```

## 7️⃣ Paket APT lain (dari setup auto)

```
openjdk-17 python gradle android-tools rsync aapt aapt2 apksigner d8 aidl
cmake ninja make wget curl git zip unzip perl p7zip clang
```
Untuk offline: taruh `.deb` yang sama di `pkg-cache/` — import akan `dpkg -i` semuanya.

---

## 🎒 Cara membuat backup zip yang BENAR (v2)

Ada 2 cara:

### Cara A — Via menu app (disarankan)
1. Jalankan **setup toolchain** sekali dengan internet (atau import backup pertama).
2. Buka menu **Export Backup** di app → membuat
   `/sdcard/BuildOutputs/builder-backup-complete-YYYYMMDD-HHMM.zip`
   yang berisi: `android-sdk/` (termasuk NDK ✅), `.gradle/wrapper/dists/`,
   `wrapper-template/`, `pkg-cache/`.

### Cara B — Manual (struktur zip yang dikenali)
Buat zip dengan folder-folder ini di akar zip:
```
android-sdk/
  platforms/android-34/...
  platforms/android-36/...
  ndk/29.0.14206865/...
  build-tools/34.0.0/...
  build-tools/33.0.1/...
  cmake/3.22.1/...
  licenses/android-sdk-license
.gradle/
  wrapper/dists/gradle-8.13-bin/<hash>/gradle-8.13-bin.zip
  wrapper/dists/gradle-9.2.1-bin/<hash>/gradle-9.2.1-bin.zip
wrapper-template/
  gradle/wrapper/gradle-wrapper.jar
pkg-cache/
  *.deb
```

> **Ukuran perkiraan:** platform ~60 MB, NDK ~400 MB (diekstrak ~1.5 GB),
> gradle ~130 MB, build-tools ~100 MB. Zip total ~700 MB–1 GB.

---

## ✅ Verifikasi setelah import

Setelah import, cek dari Termux shell:
```bash
ls ~/android-sdk/platforms/android-34/android.jar     # harus ada, > 1 MB
ls ~/android-sdk/ndk/29.0.14206865/ndk-build          # harus ada
ls ~/.gradle/wrapper/dists/gradle-8.13-bin/*/*.zip    # gradle zip
```
Jika semua ada, build berikutnya **tidak mendownload apa pun** untuk komponen tersebut.
