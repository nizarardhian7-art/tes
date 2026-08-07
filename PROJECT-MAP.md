# PROJECT-MAP.md — Peta TermuxMod (353 file)

Tujuan file ini: biar kamu gak perlu kirim semua 353 file ke AI pas mau
develop fitur. Cari task kamu di bagian "Resep per-task" di bawah, kirim
cuma file yang disebut situ.

---

## Gambaran besar: 4 module utama

```
termux/
├── app/                 (102 file) → APLIKASI-nya sendiri. UI, Activity, tombol,
│                                     drawer menu, file browser baru kamu. INI YANG
│                                     PALING SERING KAMU SENTUH.
├── terminal-emulator/   (38 file)  → "OTAK" terminal — parsing escape code,
│                                     buffer teks, native lib libtermux.so.
│                                     Jarang perlu disentuh kecuali mau ubah
│                                     cara kerja terminal itu sendiri.
├── terminal-view/       (14 file)  → Widget Android yang NAMPILIN terminal-emulator
│                                     ke layar (gesture, scroll, cursor, seleksi teks).
│                                     Jarang disentuh.
└── termux-shared/       (152 file) → Library "tukang" dipake app + termux-shared
                                       lain (Termux:API, Termux:Widget, dst).
                                       Isinya utils (file, permission, notifikasi,
                                       shell command execution). Kamu udah sentuh
                                       sedikit ini buat auto-storage-setup.
```

Yang paling gede jumlah filenya (`termux-shared`, 152 file) itu justru yang
**paling jarang** perlu kamu edit — isinya kebanyakan class utility generik
(reflection, error handling, shell environment) yang dipake tapi jarang diubah.

---

## Resep per-task — kirim file ini aja ke AI

### 🟢 Nambah/ubah fitur File Browser (paling sering)
```
app/src/main/java/com/termux/app/filebrowser/FileBrowserActivity.java
app/src/main/java/com/termux/app/filebrowser/FileBrowserAdapter.java
app/src/main/java/com/termux/app/filebrowser/FileEntry.java
app/src/main/res/layout/activity_file_browser.xml
app/src/main/res/layout/item_file_browser.xml
app/src/main/res/values/strings.xml   (bagian file_browser_*)
```

### 🟢 Nambah tombol/menu baru di layar utama Termux
```
app/src/main/java/com/termux/app/TermuxActivity.java
app/src/main/res/layout/activity_termux.xml
app/src/main/res/values/strings.xml
```

### 🟢 Bikin Activity/screen baru dari nol (UI custom Android biasa)
```
app/src/main/AndroidManifest.xml           (buat daftarin activity baru)
[contoh Activity yang udah ada buat ditiru gayanya]:
app/src/main/java/com/termux/app/filebrowser/FileBrowserActivity.java
app/src/main/res/layout/activity_file_browser.xml
```
→ Ini activity baru BEBAS mau kayak apa, gak terikat gaya terminal. Bisa
pakai RecyclerView, ViewPager, Compose (kalau mau tambah dependency), dll —
sama kayak bikin app Android biasa.

### 🟢 Ubah warna/tema/style aplikasi
```
app/src/main/res/values/themes.xml
app/src/main/res/values/styles.xml
app/src/main/res/values/colors.xml
app/src/main/res/values-night/themes.xml   (versi dark mode)
```

### 🟡 Auto-setup storage / permission handling
```
app/src/main/java/com/termux/app/TermuxActivity.java   (bagian onServiceConnected)
app/src/main/java/com/termux/app/TermuxInstaller.java  (fungsi setupStorageSymlinks)
termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxPreferenceConstants.java
termux-shared/src/main/java/com/termux/shared/termux/settings/preferences/TermuxAppSharedPreferences.java
```

### 🟡 Jalanin script / command dari UI (kayak fitur run .sh)
```
app/src/main/java/com/termux/app/TermuxService.java     (baca ACTION_SERVICE_EXECUTE)
termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java   (cari class TERMUX_SERVICE)
```

### 🔴 Ubah config build / ABI / versi SDK
```
app/build.gradle
terminal-emulator/build.gradle
gradle.properties
```

### 🔴 CI/CD (GitHub Actions build otomatis)
```
.github/workflows/build.yml
```
Satu file ini aja yang ngurusin build debug (tiap push/PR) dan build release
(pas publish GitHub Release, atau manual dispatch pilih debug/release).

### ⚫ Ubah cara kerja terminal itu sendiri (escape code, rendering) — RISIKO TINGGI
```
terminal-emulator/src/main/java/com/termux/terminal/TerminalEmulator.java
terminal-emulator/src/main/java/com/termux/terminal/TerminalBuffer.java
terminal-view/src/main/java/com/termux/view/TerminalView.java
```
Ini bagian paling kompleks & gampang bikin terminal ngebug kalau salah edit.
Hindari kecuali beneran perlu.

---

## Warna Legenda
- 🟢 **Sering** — area kerja utama buat custom UI/fitur baru
- 🟡 **Kadang** — perlu kalau fiturnya nyambung ke sistem Termux (storage, shell)
- 🔴 **Jarang** — config/infrastruktur, edit hati-hati
- ⚫ **Hindari** — inti terminal, kompleks & berisiko

---

## Tips ngirim ke AI biar hemat token
1. Jangan kirim seluruh folder `termux-shared/` — 90%-nya gak kepake buat fitur UI kamu.
2. Kalau AI butuh "lihat pola kode yang ada" (misal: gimana cara bikin Activity baru), cukup kirim **1 contoh Activity** yang mirip (`FileBrowserActivity.java` + layout-nya) sebagai referensi gaya, bukan semua Activity.
3. `terminal-emulator/` dan `terminal-view/` isinya library dari upstream Termux asli (dipertahankan apa adanya) — kirim cuma kalau task-nya beneran soal cara kerja terminal.
4. File test (`src/test/`, `src/androidTest/`) — hampir gak pernah perlu dikirim kecuali kamu mau nulis unit test baru.
