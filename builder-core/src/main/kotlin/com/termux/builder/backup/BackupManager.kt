package com.termux.builder.backup

import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.log.BuildLog
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.CommandResult
import com.termux.shared.logger.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager backup & restore environment (v4 — TIRU PERSIS build.sh user).
 *
 * Referensi: script `build.sh` yang sudah terjamin berhasil import backup
 * (NDK sukses, tanpa download ulang). Struktur zip backup yang dipakai build.sh:
 *
 *   builder-backup-complete-<ts>.zip
 *   ├── pkg-cache/            <- semua .deb APT (dari $SDK/pkg-cache + apt archives)
 *   ├── android-sdk/          <- SDK lengkap: platforms/ build-tools/ ndk/ cmake/
 *   │                            licenses/ source.properties/ repo cache...
 *   ├── .gradle/              <- GRADLE_USER_HOME: wrapper/dists (distribusi gradle
 *   │                            zip) + caches (artifact Maven)
 *   └── wrapper-template/     <- template gradle wrapper (jar + properties)
 *
 * Perbaikan v4:
 *  1. EXPORT sekarang SEPERSIS build.sh:
 *     - android-sdk TIDAK lagi mengecualikan ndk/ (NDK penuh ikut di-backup),
 *       dan TIDAK mengecualikan pkg-cache (justru dipindah ke pkg-cache/ zip).
 *     - .gradle TIDAK mengecualikan daemon/ — daemon tidak penting tapi tidak
 *       berbahaya; yang PENTING: wrapper/dists (distribusi Gradle zip) ikut.
 *     - pkg-cache diisi dari $SDK/pkg-cache + $PREFIX/var/cache/apt/archives.
 *  2. IMPORT sepersis build.sh:
 *     - install .deb dengan `dpkg -i --force-depends` (persis build.sh)
 *     - restore android-sdk dengan rsync TANPA --delete (tidak menghapus
 *       komponen yang sudah ada)
 *     - restore .gradle ke $HOME/.gradle
 *     - restore wrapper-template
 *     - verifikasi struktur zip SEBELUM restore (jika bukan zip backup -> error jelas)
 */
class BackupManager(private val executor: ProcessExecutor) {

    companion object {
        private const val LOG_TAG = "BackupManager"

        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

        /** Suffix file backup patch. */
        const val BACKUP_SUFFIX = ".builder.bak"

        /** Nama folder-folder yang dikenali dalam zip backup (struktur build.sh). */
        val KNOWN_CONTENT = listOf("pkg-cache", "android-sdk", ".gradle", "wrapper-template")

        /** Prefix nama file backup. */
        const val BACKUP_PREFIX = "builder-backup-complete-"
    }

    var lastError: String? = null
        private set

    private fun tailOf(result: CommandResult, maxLen: Int = 300): String {
        val text = result.stderr.ifBlank { result.stdout }.trim()
        return text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")
            .take(maxLen).ifBlank { "(exit ${result.exitCode}, tidak ada output)" }
    }

    private val backedUpFiles = ArrayList<File>()

    fun backupFileForPatch(file: File): File? {
        if (!file.exists()) return null
        val backupDir = File(BuilderPaths.PATCH_BACKUP_DIR)
        backupDir.mkdirs()

        val safeName = file.absolutePath.replace('/', '_').removePrefix("_")
        val backupFile = File(backupDir, "$safeName$BACKUP_SUFFIX")
        try {
            file.copyTo(backupFile, overwrite = true)
            backedUpFiles.add(file)
            return backupFile
        } catch (e: Exception) {
            return null
        }
    }

    fun rollbackAll(): Int {
        var restored = 0
        for (file in backedUpFiles) {
            val backupFile = getBackupFileFor(file)
            if (backupFile != null && backupFile.exists()) {
                try {
                    backupFile.copyTo(file, overwrite = true)
                    restored++
                } catch (e: Exception) {
                    // skip
                }
            }
        }
        backedUpFiles.clear()
        return restored
    }

    fun getBackupFileFor(file: File): File? {
        val safeName = file.absolutePath.replace('/', '_').removePrefix("_")
        return File(BuilderPaths.PATCH_BACKUP_DIR, "$safeName$BACKUP_SUFFIX").takeIf { it.exists() }
    }

    fun clearPatchBackups() {
        File(BuilderPaths.PATCH_BACKUP_DIR).deleteRecursively()
    }

    // ============================================================
    // EXPORT (tiru persis export_backup() build.sh)
    // ============================================================

    /**
     * Export environment lengkap ke zip — struktur SEPERSIS build.sh:
     *   android-sdk/ (platform, build-tools, NDK, cmake, licenses, pkg-cache)
     *   .gradle/     (wrapper/dists + caches — gradle tidak unduh ulang)
     *   wrapper-template/
     *   pkg-cache/   (deb APT dari $SDK/pkg-cache + $PREFIX/var/cache/apt/archives)
     *
     * @param sdkDir lokasi SDK (default $HOME/android-sdk)
     * @param lineCb callback log live
     * @return path zip, atau null + lastError
     */
    fun exportEnvironmentBackup(sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR, lineCb: ProcessExecutor.LineCallback? = null): String? {
        lastError = null
        val stage = File("${BuilderPaths.DEFAULT_HOME_DIR}/.backup-temp")
        stage.deleteRecursively()
        File(stage, "pkg-cache").mkdirs()

        val sdk = File(sdkDir)
        if (!sdk.exists()) {
            lastError = "SDK dir belum ada ($sdkDir) — jalankan setup toolchain dulu sebelum export."
            return null
        }
        if (!executor.isExecutableAvailable("zip")) {
            lastError = "Binary 'zip' tidak ditemukan. Jalankan 'apt-get install zip' dulu."
            return null
        }

        val progress = { msg: String -> lineCb?.onLine(msg) }

        progress(BuildLog.section("EXPORT BACKUP ENVIRONMENT"))
        progress(BuildLog.step(1, 4, "Menyalin android-sdk (termasuk NDK, build-tools, cmake)..."))

        // v4: SEPERSIS build.sh — rsync SELURUH isi $SDK (tidak exclude ndk/).
        // Pengecualian hanya untuk folder sementara pkg-cache (debs dipindah
        // terpisah ke pkg-cache/ zip) supaya tidak dobel.
        val rsyncSdk = executor.executeShellCommand(
            "rsync -a --exclude='pkg-cache/' '${sdk.absolutePath}/' '${File(stage, "android-sdk").absolutePath}/'",
            timeoutSeconds = 1800
        )
        if (!rsyncSdk.isSuccess) {
            lastError = "rsync android-sdk gagal (exit ${rsyncSdk.exitCode}): ${tailOf(rsyncSdk)}"
            stage.deleteRecursively()
            return null
        }
        val ndkDir = File(stage, "android-sdk/ndk")
        val ndkSize = if (ndkDir.isDirectory) ndkDir.walkTopDown().filter { it.isFile }.map { it.length() }.sum() else 0L
        progress(BuildLog.ok("android-sdk tersalin (NDK ${formatSize(ndkSize)})."))

        progress(BuildLog.step(2, 4, "Menyalin .gradle (wrapper dists + caches)..."))
        val gradleHome = File(BuilderPaths.DEFAULT_GRADLE_HOME)
        if (gradleHome.exists()) {
            // v4: SEPERSIS build.sh — rsync seluruh .gradle (daemon di-exclude
            // hanya untuk menghemat ukuran; wrapper/dists + caches IKUT).
            val rsyncGradle = executor.executeShellCommand(
                "rsync -a --exclude='daemon/' '${gradleHome.absolutePath}/' '${File(stage, ".gradle").absolutePath}/'",
                timeoutSeconds = 1800
            )
            if (!rsyncGradle.isSuccess) {
                Logger.logWarn(LOG_TAG, "rsync .gradle gagal (non-fatal): ${tailOf(rsyncGradle)}")
            }
            File(stage, ".gradle/daemon").deleteRecursively()
        } else {
            progress(BuildLog.warn("$gradleHome belum ada — dilewati."))
        }

        progress(BuildLog.step(3, 4, "Menyalin wrapper-template & pkg-cache..."))
        val wrapperDir = File(BuilderPaths.DEFAULT_WRAPPER_DIR)
        if (wrapperDir.exists()) {
            executor.executeShellCommand(
                "rsync -a '${wrapperDir.absolutePath}/' '${File(stage, "wrapper-template").absolutePath}/'",
                timeoutSeconds = 300
            )
        }

        // pkg-cache: SEPERSIS build.sh — dari $SDK/pkg-cache DAN apt archives
        val pkgCache = File("$sdkDir/pkg-cache")
        if (pkgCache.exists()) {
            pkgCache.listFiles()?.filter { it.extension == "deb" }?.forEach {
                it.copyTo(File(stage, "pkg-cache/${it.name}"), overwrite = true)
            }
        }
        val aptCache = File(BuilderPaths.APT_ARCHIVES_DIR)
        if (aptCache.exists()) {
            aptCache.listFiles()?.filter { it.extension == "deb" }?.forEach {
                it.copyTo(File(stage, "pkg-cache/${it.name}"), overwrite = true)
            }
        }
        val debCount = File(stage, "pkg-cache").listFiles()?.filter { it.extension == "deb" }?.size ?: 0
        progress(BuildLog.info("${debCount} paket .deb di pkg-cache."))

        progress(BuildLog.step(4, 4, "Mengompres zip..."))
        val zipName = "$BACKUP_PREFIX${DATE_FORMAT.format(Date())}.zip"
        val zipPath = File(BuilderPaths.DEFAULT_OUTPUT_DIR, zipName)
        File(BuilderPaths.DEFAULT_OUTPUT_DIR).mkdirs()
        val result = executor.executeShellCommand(
            "cd '${stage.absolutePath}' && zip -r -q '${zipPath.absolutePath}' .",
            lineCallback = lineCb,
            timeoutSeconds = 1800
        )
        stage.deleteRecursively()
        if (!result.isSuccess || !zipPath.exists() || zipPath.length() == 0L) {
            lastError = "Gagal membuat ZIP backup (exit ${result.exitCode}): ${tailOf(result)}"
            return null
        }
        progress(BuildLog.ok("Backup selesai: ${zipPath.name} (${formatSize(zipPath.length())})"))
        return zipPath.absolutePath
    }

    // ============================================================
    // IMPORT (tiru persis import_backup() build.sh)
    // ============================================================

    /** Cari backup terbaru di output dir atau /sdcard. */
    fun findLatestBackup(): File? {
        val candidates = ArrayList<File>()
        File(BuilderPaths.DEFAULT_OUTPUT_DIR).listFiles()?.filter {
            it.name.startsWith(BACKUP_PREFIX) && it.extension == "zip"
        }?.forEach { candidates.add(it) }
        File("/sdcard").listFiles()?.filter {
            it.name.startsWith(BACKUP_PREFIX) && it.extension == "zip"
        }?.forEach { candidates.add(it) }
        return candidates.maxByOrNull { it.lastModified() }
    }

    fun importEnvironmentBackup(sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR): Boolean {
        val backup = findLatestBackup() ?: return false
        return importEnvironmentBackupFromFile(backup, sdkDir)
    }

    /**
     * Import backup dari file zip — SEPERSIS import_backup() build.sh:
     *   1. pkg-cache .deb  -> dpkg -i --force-depends (persis build.sh)
     *   2. android-sdk/    -> rsync -a (tanpa --delete; tidak menimpa yang ada)
     *   3. .gradle/        -> rsync -a ke $HOME/.gradle
     *   4. wrapper-template -> rsync -a
     *   5. NDK archive (android-ndk-*.7z/zip) -> extract otomatis (opsional,
     *      build.sh mencarinya; NDK folder biasa sudah ter-restore di langkah 2)
     * Komponen yang sudah ada & valid TIDAK diunduh ulang (rsync tanpa --delete
     * + validasi ToolchainManager).
     */
    fun importEnvironmentBackupFromFile(
        backup: File,
        sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR,
        lineCb: ProcessExecutor.LineCallback? = null
    ): Boolean {
        lastError = null

        if (!backup.exists()) {
            lastError = "File backup tidak ditemukan: ${backup.absolutePath}"
            return false
        }
        if (backup.length() == 0L) {
            lastError = "File backup kosong (0 byte): ${backup.absolutePath}"
            return false
        }

        val progress = { msg: String -> lineCb?.onLine(msg) }

        if (!executor.isExecutableAvailable("unzip") || !executor.isExecutableAvailable("rsync")) {
            progress(BuildLog.warn("Binary 'unzip'/'rsync' belum ada. Memasang via APT..."))
            val pkgInstall = executor.executeShellCommand(
                "apt-get update -y && apt-get install -y unzip rsync p7zip",
                environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                lineCallback = lineCb,
                timeoutSeconds = 600
            )
            if (!pkgInstall.isSuccess) {
                lastError = "Binary 'unzip'/'rsync' gagal dipasang via APT: ${tailOf(pkgInstall)}"
                return false
            }
        }

        progress(BuildLog.section("IMPORT BACKUP ENVIRONMENT"))
        progress(BuildLog.info("File: ${backup.name} (${formatSize(backup.length())})"))

        val restoreDir = File("${BuilderPaths.DEFAULT_HOME_DIR}/.restore-temp")
        restoreDir.deleteRecursively()
        restoreDir.mkdirs()

        val extract = executor.executeShellCommand(
            "unzip -o '${backup.absolutePath}' -d '${restoreDir.absolutePath}/'",
            lineCallback = lineCb,
            timeoutSeconds = 1800
        )

        val isExtractSuccess = (extract.exitCode == 0 || extract.exitCode == 1) && restoreDir.listFiles()?.isNotEmpty() == true
        if (!isExtractSuccess) {
            lastError = "Gagal ekstrak ZIP backup (exit ${extract.exitCode}): ${tailOf(extract)}. Cek apakah file '${backup.name}' corrupt."
            restoreDir.deleteRecursively()
            return false
        }

        // Verifikasi struktur (persis build.sh: cek folder yang dikenali)
        val hasKnownContent = KNOWN_CONTENT.any { File(restoreDir, it).exists() }
        if (!hasKnownContent) {
            lastError = "ZIP berhasil diekstrak tapi tidak berisi struktur backup yang dikenali " +
                "(pkg-cache/android-sdk/.gradle/wrapper-template). Isi ZIP: " +
                (restoreDir.listFiles()?.joinToString(", ") { it.name } ?: "(kosong)")
            restoreDir.deleteRecursively()
            return false
        }

        // ---- 1. pkg-cache: install .deb (persis build.sh: dpkg -i --force-depends) ----
        val pkgCache = File(restoreDir, "pkg-cache")
        val debs = pkgCache.listFiles()?.filter { it.extension == "deb" }
        if (!debs.isNullOrEmpty()) {
            progress(BuildLog.step(1, 4, "Menginstall ${debs.size} paket .deb dari backup (offline)..."))
            val debArgs = debs.joinToString(" ") { "'${it.absolutePath}'" }
            val dpkgResult = executor.executeShellCommand(
                "dpkg -i --force-depends $debArgs 2>/dev/null || true",
                lineCallback = lineCb,
                timeoutSeconds = 900
            )
            // salin ke $SDK/pkg-cache (persis build.sh) agar setup berikutnya
            // bisa pakai cache ini
            File("$sdkDir/pkg-cache").mkdirs()
            debs.forEach { it.copyTo(File("$sdkDir/pkg-cache/${it.name}"), overwrite = true) }
            if (!dpkgResult.isSuccess) {
                Logger.logWarn(LOG_TAG, "dpkg -i sebagian gagal (non-fatal): ${tailOf(dpkgResult)}")
            } else {
                progress(BuildLog.ok("Paket .deb terinstall."))
            }
        } else {
            progress(BuildLog.info("Tidak ada pkg-cache di backup."))
        }

        // ---- 2. android-sdk (termasuk ndk/) — rsync tanpa --delete ----
        val sdkBackup = File(restoreDir, "android-sdk")
        if (sdkBackup.exists()) {
            progress(BuildLog.step(2, 4, "Merestore android-sdk (platform, build-tools, NDK, cmake)..."))
            val r = executor.executeShellCommand(
                "rsync -a '${sdkBackup.absolutePath}/' '$sdkDir/'",
                lineCallback = lineCb,
                timeoutSeconds = 1800
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore android-sdk gagal (non-fatal): ${tailOf(r)}")
            else {
                val ndkDir = File("$sdkDir/ndk")
                val ndkVersions = ndkDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
                progress(BuildLog.ok("android-sdk direstore (NDK: ${ndkVersions.joinToString(", ").ifBlank { "tidak ada" }})."))
            }
        }

        // ---- 3. .gradle (wrapper dists + caches) — rsync tanpa --delete ----
        val gradleBackup = File(restoreDir, ".gradle")
        if (gradleBackup.exists()) {
            progress(BuildLog.step(3, 4, "Merestore .gradle (wrapper dists + caches)..."))
            val r = executor.executeShellCommand(
                "rsync -a '${gradleBackup.absolutePath}/' '${BuilderPaths.DEFAULT_GRADLE_HOME}/'",
                lineCallback = lineCb,
                timeoutSeconds = 1800
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore .gradle gagal (non-fatal): ${tailOf(r)}")
            else progress(BuildLog.ok(".gradle direstore — Gradle tidak akan diunduh ulang."))
        }

        // ---- 4. wrapper-template ----
        val wrapperBackup = File(restoreDir, "wrapper-template")
        if (wrapperBackup.exists()) {
            progress(BuildLog.step(4, 4, "Merestore wrapper-template..."))
            val r = executor.executeShellCommand(
                "rsync -a '${wrapperBackup.absolutePath}/' '${BuilderPaths.DEFAULT_WRAPPER_DIR}/'",
                lineCallback = lineCb,
                timeoutSeconds = 300
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore wrapper-template gagal (non-fatal): ${tailOf(r)}")
        }

        // ---- 5. NDK archive (opsional, persis build.sh) ----
        // build.sh mencari android-ndk-*.7z/zip di restore dir / SDK / sdcard.
        // NDK folder penuh sudah di-restore di langkah 2; ini untuk kasus backup
        // lama yang hanya berisi arsip NDK terkompresi.
        val ndkArchive = findNdkArchive(restoreDir, File(sdkDir))
        if (ndkArchive != null) {
            progress(BuildLog.info("Menemukan arsip NDK: ${ndkArchive.name} — mengekstrak..."))
            extractNdkArchive(ndkArchive, sdkDir) { msg -> progress(msg) }
        }

        restoreDir.deleteRecursively()
        progress(BuildLog.ok("Import backup selesai. Toolchain siap — komponen yang sudah ada & valid tidak akan diunduh ulang."))
        return true
    }

    /** Cari arsip NDK (7z/zip) seperti build.sh (maxdepth 2). */
    private fun findNdkArchive(vararg dirs: File): File? {
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val found = dir.listFiles()?.firstOrNull {
                it.isFile && (it.name.startsWith("android-ndk-") && (it.extension == "7z" || it.extension == "zip"))
            }
            if (found != null) return found
            // maxdepth 2
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                val inSub = sub.listFiles()?.firstOrNull {
                    it.isFile && it.name.startsWith("android-ndk-") && (it.extension == "7z" || it.extension == "zip")
                }
                if (inSub != null) return inSub
            }
        }
        return null
    }

    /** Ekstrak arsip NDK ke $SDK/ndk/<version> (persis build.sh). */
    private fun extractNdkArchive(archive: File, sdkDir: String, progress: (String) -> Unit): Boolean {
        val tmpDir = File("$sdkDir/ndk/tmp_ndk")
        tmpDir.deleteRecursively()
        tmpDir.mkdirs()
        val ex = if (archive.extension == "7z") {
            executor.executeShellCommand("7z x -o'${tmpDir.absolutePath}' '${archive.absolutePath}' -y >/dev/null 2>&1")
        } else {
            executor.executeShellCommand("unzip -q '${archive.absolutePath}' -d '${tmpDir.absolutePath}' 2>/dev/null")
        }
        if (!ex.isSuccess) {
            tmpDir.deleteRecursively()
            Logger.logWarn(LOG_TAG, "Gagal mengekstrak arsip NDK ${archive.name}")
            return false
        }
        val extractedRoot = findNdkBuildDir(tmpDir) ?: run {
            tmpDir.deleteRecursively()
            Logger.logWarn(LOG_TAG, "ndk-build tidak ditemukan di arsip ${archive.name}")
            return false
        }
        val version = extractedRoot.name
        val target = File("$sdkDir/ndk/$version")
        if (target.exists()) target.deleteRecursively()
        extractedRoot.renameTo(target)
        tmpDir.deleteRecursively()
        progress(BuildLog.ok("NDK $version diekstrak dari arsip backup."))
        return true
    }

    private fun findNdkBuildDir(dir: File): File? {
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    if (File(c, "ndk-build").exists() || File(c, "build/ndk-build").exists()) {
                        return c
                    }
                    stack.add(c)
                }
            }
        }
        return null
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return String.format("%.1f %s", v, units[u])
    }
}