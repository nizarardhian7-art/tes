package com.termux.builder.backup

import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.log.BuildLog
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.CommandResult
import com.termux.builder.model.DependencyCatalog
import com.termux.shared.logger.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager backup & rollback.
 *
 * v2 — perbaikan inti untuk keluhan "import zip backup tapi tetap download ulang":
 *
 *  1. EXPORT: versi lama melakukan `rsync --exclude='ndk/'` — NDK TIDAK PERNAH
 *     masuk ke zip backup! Juga gradle dists ($HOME/.gradle/wrapper/dists)
 *     tidak di-backup. Sekarang export menyertakan:
 *       android-sdk/            (platform, build-tools, cmake, licenses, pkg-cache)
 *       android-sdk/ndk/        (NDK lengkap — TIDAK lagi di-exclude)
 *       .gradle/wrapper/dists/  (distribusi Gradle zip — agar gradle tidak unduh ulang)
 *       .gradle/caches/         (cache artifact Maven Google — mempercepat build offline)
 *       wrapper-template/
 *       pkg-cache/              (paket .deb APT)
 *
 *  2. IMPORT: verifikasi struktur, restore ke lokasi yang BENAR, dan jangan
 *     menimpa komponen yang sudah ada & valid (skip-download).
 *
 *  3. Semua log lewat [BuildLog] agar terstruktur.
 */
class BackupManager(private val executor: ProcessExecutor) {

    companion object {
        private const val LOG_TAG = "BackupManager"

        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

        /** Suffix file backup. */
        const val BACKUP_SUFFIX = ".builder.bak"

        /** Nama folder-folder yang dikenali dalam zip backup. */
        val KNOWN_CONTENT = listOf("pkg-cache", "android-sdk", ".gradle", "wrapper-template")
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
    // EXPORT
    // ============================================================

    /**
     * Export environment lengkap ke zip:
     *   - android-sdk/ (termasuk ndk/ — PERBAIKAN v2)
     *   - .gradle/     (wrapper dists + caches — agar gradle tidak unduh ulang)
     *   - wrapper-template/
     *   - pkg-cache/   (deb APT)
     *
     * @param lineCb callback log
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
        progress(BuildLog.step(1, 4, "Menyalin android-sdk (termasuk NDK)..."))

        // v2: TIDAK lagi --exclude='ndk/' — NDK ikut di-backup!
        val rsyncSdk = executor.executeShellCommand(
            "rsync -a --exclude='pkg-cache/' '${sdk.absolutePath}/' '${File(stage, "android-sdk").absolutePath}/'",
            timeoutSeconds = 1800
        )
        if (!rsyncSdk.isSuccess) {
            lastError = "rsync android-sdk gagal (exit ${rsyncSdk.exitCode}): ${tailOf(rsyncSdk)}"
            stage.deleteRecursively()
            return null
        }
        val ndkSize = File(stage, "android-sdk/ndk").let {
            if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.map { f -> f.length() }.sum() else 0L
        }
        progress(BuildLog.ok("android-sdk tersalin (NDK ${formatSize(ndkSize)})."))

        progress(BuildLog.step(2, 4, "Menyalin .gradle (wrapper dists + caches)..."))
        val gradleHome = File(BuilderPaths.DEFAULT_GRADLE_HOME)
        if (gradleHome.exists()) {
            val rsyncGradle = executor.executeShellCommand(
                "rsync -a --exclude='daemon/' '${gradleHome.absolutePath}/' '${File(stage, ".gradle").absolutePath}/'",
                timeoutSeconds = 1800
            )
            if (!rsyncGradle.isSuccess) {
                Logger.logWarn(LOG_TAG, "rsync .gradle gagal (non-fatal): ${tailOf(rsyncGradle)}")
            }
            // Hapus folder kosong hasil rsync daemon yang di-exclude
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

        val pkgCache = File("$sdkDir/pkg-cache")
        if (pkgCache.exists()) {
            pkgCache.listFiles()?.filter { it.extension == "deb" }?.forEach {
                it.copyTo(File(stage, "pkg-cache/${it.name}"), overwrite = true)
            }
        }
        val aptCache = File("${BuilderPaths.PREFIX_BIN_DIR}/../var/cache/apt/archives")
        if (aptCache.exists()) {
            aptCache.listFiles()?.filter { it.extension == "deb" }?.forEach {
                it.copyTo(File(stage, "pkg-cache/${it.name}"), overwrite = true)
            }
        }

        progress(BuildLog.step(4, 4, "Mengompres zip..."))
        val zipName = "builder-backup-complete-${DATE_FORMAT.format(Date())}.zip"
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
    // IMPORT
    // ============================================================

    fun importEnvironmentBackup(sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR): Boolean {
        val outputDir = File(BuilderPaths.DEFAULT_OUTPUT_DIR)
        val candidates = ArrayList<File>()
        outputDir.listFiles()?.filter { it.name.startsWith("builder-backup-complete-") && it.extension == "zip" }?.forEach { candidates.add(it) }
        File("/sdcard").listFiles()?.filter { it.name.startsWith("builder-backup-complete-") && it.extension == "zip" }?.forEach { candidates.add(it) }

        val backup = candidates.maxByOrNull { it.lastModified() } ?: return false

        return importEnvironmentBackupFromFile(backup, sdkDir)
    }

    /**
     * Import backup dari file zip.
     *
     * v2:
     *  - Verifikasi struktur (pkg-cache/android-sdk/.gradle/wrapper-template).
     *  - Restore android-sdk (termasuk ndk/) dengan rsync — tanpa menghapus
     *    komponen yang sudah ada (rsync tanpa --delete).
     *  - Restore .gradle (wrapper/dists) agar gradle tidak unduh ulang.
     *  - Install .deb pkg-cache.
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

        val hasKnownContent = KNOWN_CONTENT.any { File(restoreDir, it).exists() }
        if (!hasKnownContent) {
            lastError = "ZIP berhasil diekstrak tapi tidak berisi struktur backup yang dikenali " +
                "(pkg-cache/android-sdk/.gradle/wrapper-template). Isi ZIP: " +
                (restoreDir.listFiles()?.joinToString(", ") { it.name } ?: "(kosong)")
            restoreDir.deleteRecursively()
            return false
        }

        // ---- 1. pkg-cache: install .deb ----
        val pkgCache = File(restoreDir, "pkg-cache")
        val debs = pkgCache.listFiles()?.filter { it.extension == "deb" }
        if (!debs.isNullOrEmpty()) {
            progress(BuildLog.step(1, 4, "Menginstall ${debs.size} paket .deb dari backup..."))
            val debArgs = debs.joinToString(" ") { "'${it.absolutePath}'" }
            val dpkgResult = executor.executeShellCommand(
                "dpkg -i --force-depends $debArgs",
                lineCallback = lineCb,
                timeoutSeconds = 900
            )
            if (!dpkgResult.isSuccess) {
                Logger.logWarn(LOG_TAG, "dpkg -i sebagian gagal (non-fatal): ${tailOf(dpkgResult)}")
            }
        } else {
            progress(BuildLog.info("Tidak ada pkg-cache di backup."))
        }

        // ---- 2. android-sdk (termasuk ndk/) ----
        val sdkBackup = File(restoreDir, "android-sdk")
        if (sdkBackup.exists()) {
            progress(BuildLog.step(2, 4, "Merestore android-sdk (platform, build-tools, NDK)..."))
            val r = executor.executeShellCommand(
                "rsync -a '${sdkBackup.absolutePath}/' '$sdkDir/'",
                lineCallback = lineCb,
                timeoutSeconds = 1800
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore android-sdk gagal (non-fatal): ${tailOf(r)}")
            else {
                val ndkDir = File("$sdkDir/ndk")
                val ndkVersions = ndkDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
                progress(BuildLog.ok("android-sdk direstore (NDK: ${ndkVersions.joinToString(", ") { it }.ifBlank { "tidak ada" }})."))
            }
        }

        // ---- 3. .gradle (wrapper dists + caches) ----
        val gradleBackup = File(restoreDir, ".gradle")
        if (gradleBackup.exists()) {
            progress(BuildLog.step(3, 4, "Merestore .gradle (wrapper dists + caches)..."))
            val r = executor.executeShellCommand(
                "rsync -a '${gradleBackup.absolutePath}/' '${BuilderPaths.DEFAULT_GRADLE_HOME}/'",
                lineCallback = lineCb,
                timeoutSeconds = 1800
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore .gradle gagal (non-fatal): ${tailOf(r)}")
            else progress(BuildLog.ok(".gradle direstore."))
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

        restoreDir.deleteRecursively()
        progress(BuildLog.ok("Import backup selesai. Toolchain siap digunakan — komponen yang sudah ada tidak akan diunduh ulang."))
        return true
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
