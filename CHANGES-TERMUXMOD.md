# TermuxMod — Ringkasan Perubahan

Base: source resmi termux-app (upstream, tidak dimodifikasi packagenya/nama app-nya).

## 1. arm64-v8a only (app + terminal-emulator)
- `app/build.gradle`: `defaultConfig.ndk.abiFilters = ["arm64-v8a"]`, `splits.abi.include = ["arm64-v8a"]` (splits tetap `enable true` supaya APK dinamai `..._arm64-v8a.apk`, bukan `..._universal.apk`).
- `terminal-emulator/build.gradle`: **module ini punya native lib sendiri (`libtermux.so`, buat wcwidth/utf8) yang kelewat di patch awal** — sudah difix, `ndk.abiFilters` juga arm64-v8a only.
- `app/src/main/cpp/termux-bootstrap-zip.S`: cabang `#if/#elif` buat i686/x86_64/arm dihapus, cuma sisa `__aarch64__`.
- Task Gradle `downloadBootstraps` (yang auto-download `bootstrap-*.zip` dari rilis termux-packages sebelum compile): sekarang cuma download `aarch64`, gak lagi download `arm`/`i686`/`x86_64` yang gak kepake.
- **Koreksi dari respons saya sebelumnya**: saya sempat bilang kamu harus download `bootstrap-aarch64.zip` manual — itu salah, ternyata sudah otomatis lewat task Gradle ini. Gak perlu langkah manual.
- `.github/workflows/debug_build.yml` & `attach_debug_apks_to_release.yml`: validasi/upload/attach APK dipangkas dari 5 varian (universal/arm64/armeabi-v7a/x86_64/x86) jadi cuma `arm64-v8a`.

## 2. Auto setup storage
(sama seperti sebelumnya — lihat riwayat chat)

## 3. File Browser + Script Runner
(sama seperti sebelumnya — lihat riwayat chat)

## 4. APK Builder (baru, V1)
- Package baru: `app/src/main/java/com/termux/app/apkbuilder/ApkBuilderActivity.java`
- Front-end native buat script builder APK milik kamu sendiri (bukan bagian dari Termux upstream — kamu tetap harus punya script-nya sendiri, app cuma bantu manggil).
- Alurnya:
  1. Pilih file `.sh` script builder kamu (pakai `FileBrowserActivity` mode baru "pick file")
  2. Pilih folder proyek Android (pakai `FileBrowserActivity` mode baru "pick folder") — path-nya otomatis ditulis ke `~/.termux-apk-builder/last_project.txt`, sama persis file yang dipakai fitur "proyek terakhir" di script kamu
  3. Tap "Build Debug"/"Build Release" → buka terminal, jalanin script kamu apa adanya (gak dimodif)
  4. **Yang PERLU kamu lakuin manual di V1**: pas terminal kebuka, tekan "1" (Debug) atau "2" (Release) lalu Enter. Ini gak bisa diauto-tekan di V1 karena `EXTRA_STDIN` di Termux cuma jalan buat mode eksekusi headless (`APP_SHELL`), bukan sesi terminal interaktif — dicoba dan dikonfirmasi gak jalan, makanya gak dipaksain.
- `FileBrowserActivity` ditambah 2 mode baru (gak ganggu perilaku browse biasa): `PICK_MODE_FOLDER` (nampilin tombol "Pilih Folder Ini") dan `PICK_MODE_FILE` (tap file balikin path-nya, gak langsung run/buka).
- Preference baru `KEY_APK_BUILDER_SCRIPT_PATH` (di `TermuxPreferenceConstants`/`TermuxAppSharedPreferences`) — nyimpen path script biar gak perlu pilih ulang tiap buka.
- Entry point: tombol wrench di drawer (sebelah Files).
- **V2 (belum dikerjain)**: full headless — terminal gak kebuka sama sekali, log native di layar sendiri. Butuh switch ke `Runner.APP_SHELL` + custom log-streaming UI.

## 5. Redesign UI: extra-keys row & tombol menu
- **Baris tombol ekstra terminal** (`TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS`) diganti dari `ESC / — HOME ↑ END PGUP` (jarang kepake) jadi:
  - Baris 1: `TAB CTRL ALT ← ↑ ↓ →` (masih perlu buat navigasi/shortcut manual)
  - Baris 2: **Stop** (kirim Ctrl+C — hentiin proses yang lagi jalan), **Bersihkan** (Ctrl+L — bersihin layar), **Keluar** (Ctrl+D), **Tempel/PASTE** (paste clipboard 1 tap), **⌨ KEYBOARD** (toggle keyboard)
  - Ini cuma ganti default bawaan — kalau user (kamu atau siapapun install app-nya) udah punya `~/.termux/termux.properties` sendiri, gak kesentuh/gak ke-override.
  - **Copy** teks gak perlu tombol baru — itu udah jalan lewat long-press+drag (seleksi teks native Android), bukan lewat baris extra-keys.
- **Tombol menu selalu keliatan**: nambah floating button bulat (☰) di pojok kiri-atas layar terminal, klik langsung buka drawer — gak perlu lagi swipe dari tepi layar.

## Belum dikerjain (dibahas terpisah, scope-nya besar)
1. **APK Builder V2 (full native, tanpa buka terminal)**: perlu switch dari `Runner.TERMINAL_SESSION` ke `Runner.APP_SHELL` (eksekusi headless) + layar log streaming native + UI buat import NDK/dependency/backup zip (ganti menu import/export di script kamu). Ini proyek tersendiri, belum mulai.

## Yang PERLU kamu cek/lakukan sebelum build
1. ~~Download bootstrap-aarch64.zip manual~~ — TIDAK PERLU, sudah auto lewat Gradle task `downloadBootstraps`.
2. Sync Gradle / build via GitHub Actions.
3. Belum ditest di device — perlu 1x build+install manual buat verifikasi akhir.

