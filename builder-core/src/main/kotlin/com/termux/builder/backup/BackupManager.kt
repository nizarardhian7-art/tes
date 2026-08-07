package com.termux.builder.backup

import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuilderPaths
import com.termux.shared.logger.Logger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manager backup & rollback.
 *
 * Dua tanggung jawab:
 *  1. Backup file Gradle sebelum di-patch (.bak) + rollback ke kondisi asli.
 *  2. Export/import environment build lengkap (SDK, .gradle, wrapper-template,
 *     pkg-cache) — pemetaan dari build.sh export_backup() / import_backup().
 */
class BackupManager(private val executor: ProcessExecutor) {

    companion object {
        private const val LOG_TAG = "BackupManager"

        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)

        /** Suffix file backup. */
        const val BACKUP_SUFFIX = ".builder.bak"
    }

    /**
     * Alasan kegagalan TERAKHIR dari export/import. Selalu diisi pesan spesifik
     * ketika sebuah operasi mengembalikan null/false — sebelumnya semua error
     * dibuang lewat `2>/dev/null` sehingga UI tidak pernah tahu penyebabnya.
     */
    var lastError: String? = null
        private set

    private fun tailOf(result: com.termux.builder.model.CommandResult, maxLen: Int = 300): String {
        val text = result.stderr.ifBlank { result.stdout }.trim()
        return text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")
            .take(maxLen).ifBlank { "(exit ${result.exitCode}, tidak ada output)" }
    }

    /** Daftar file yang di-backup pada sesi ini (untuk rollback). */
    private val backedUpFiles = ArrayList<File>()

    /**
     * Backup file sebelum dipatch. Menyimpan salinan ke state dir dengan nama
     * hash path agar aman dari konflik.
     */
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

    /** Rollback semua file yang di-backup pada sesi ini. */
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

    /** Cari file backup untuk file tertentu. */
    fun getBackupFileFor(file: File): File? {
        val safeName = file.absolutePath.replace('/', '_').removePrefix("_")
        return File(BuilderPaths.PATCH_BACKUP_DIR, "$safeName$BACKUP_SUFFIX").takeIf { it.exists() }
    }

    /** Bersihkan semua backup patch lama. */
    fun clearPatchBackups() {
        File(BuilderPaths.PATCH_BACKUP_DIR).deleteRecursively()
    }

    /**
     * Export environment build lengkap ke ZIP di output dir.
     * @return path zip yang dihasilkan, atau null bila gagal
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

        // rsync SDK (tanpa ndk)
        executor.executeShellCommand(
            "rsync -a --exclude='ndk/' '${sdk.absolutePath}/' '${File(stage, "android-sdk").absolutePath}/'",
            timeoutSeconds = 900
        )

        // .gradle home
        val gradleHome = File("${BuilderPaths.DEFAULT_HOME_DIR}/.gradle")
        if (gradleHome.exists()) {
            executor.executeShellCommand(
                "rsync -a '${gradleHome.absolutePath}/' '${File(stage, ".gradle").absolutePath}/'",
                timeoutSeconds = 900
            )
        }

        // wrapper-template
        val wrapperDir = File(BuilderPaths.DEFAULT_WRAPPER_DIR)
        if (wrapperDir.exists()) {
            executor.executeShellCommand(
                "rsync -a '${wrapperDir.absolutePath}/' '${File(stage, "wrapper-template").absolutePath}/'",
                timeoutSeconds = 300
            )
        }

        // pkg-cache .deb
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

        // ZIP
        val zipName = "builder-backup-complete-${DATE_FORMAT.format(Date())}.zip"
        val zipPath = File(BuilderPaths.DEFAULT_OUTPUT_DIR, zipName)
        File(BuilderPaths.DEFAULT_OUTPUT_DIR).mkdirs()
        val result = executor.executeShellCommand(
            "cd '${stage.absolutePath}' && zip -r '${zipPath.absolutePath}' . && echo OK || echo FAIL",
            lineCallback = lineCb,
            timeoutSeconds = 900
        )
        stage.deleteRecursively()
        if (!result.isSuccess || !result.stdout.contains("OK")) {
            lastError = "Gagal membuat ZIP backup (exit ${result.exitCode}): ${tailOf(result)}"
            return null
        }
        return zipPath.absolutePath
    }

    /**
     * Import environment backup dari ZIP terbaru di output dir / sdcard.
     * @return true bila berhasil
     */
    fun importEnvironmentBackup(sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR): Boolean {
        // Cari backup terbaru
        val outputDir = File(BuilderPaths.DEFAULT_OUTPUT_DIR)
        val candidates = ArrayList<File>()
        outputDir.listFiles()?.filter { it.name.startsWith("builder-backup-complete-") && it.extension == "zip" }?.forEach { candidates.add(it) }
        File("/sdcard").listFiles()?.filter { it.name.startsWith("builder-backup-complete-") && it.extension == "zip" }?.forEach { candidates.add(it) }

        val backup = candidates.maxByOrNull { it.lastModified() } ?: return false

        return importEnvironmentBackupFromFile(backup, sdkDir)
    }

    /**
     * Import environment backup dari file ZIP spesifik (mis. dipilih via SAF).
     * @param lineCb opsional — bila diisi, setiap baris output unzip/rsync/dpkg
     *   diteruskan live ke UI (panel log), bukan hanya hasil akhir true/false.
     * @return true bila berhasil. Bila false, cek [lastError] untuk alasannya.
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

        // AUTO-INSTALL: Jika unzip / rsync belum ada, pasang otomatis via APT
        if (!executor.isExecutableAvailable("unzip") || !executor.isExecutableAvailable("rsync")) {
            lineCb?.onLine("► Binary 'unzip'/'rsync' belum ada. Memasang via APT...")
            val pkgInstall = executor.executeShellCommand(
                "apt-get update -y && apt-get install -y unzip rsync p7zip",
                environment = mapOf("DEBIAN_FRONTEND" to "noninteractive"),
                lineCallback = lineCb,
                timeoutSeconds = 600
            )
            if (!pkgInstall.isSuccess) {
                lastError = "Binary 'unzip'/'rsync' tidak ditemukan dan gagal dipasang otomatis via APT: ${tailOf(pkgInstall)}"
                return false
            }
        }

        val restoreDir = File("${BuilderPaths.DEFAULT_HOME_DIR}/.restore-temp")
        restoreDir.deleteRecursively()
        restoreDir.mkdirs()

        val extract = executor.executeShellCommand(
            "unzip -o '${backup.absolutePath}' -d '${restoreDir.absolutePath}/' && echo OK || echo FAIL",
            lineCallback = lineCb,
            timeoutSeconds = 1800
        )
        if (!extract.isSuccess || !extract.stdout.contains("OK")) {
            lastError = "Gagal ekstrak ZIP backup (exit ${extract.exitCode}): ${tailOf(extract)}. " +
                "Cek apakah file '${backup.name}' adalah ZIP hasil export builder ini dan tidak corrupt."
            restoreDir.deleteRecursively()
            return false
        }

        // Sanity check: apakah isi ZIP memang berisi struktur backup yang diharapkan?
        val hasKnownContent = listOf("pkg-cache", "android-sdk", ".gradle", "wrapper-template")
            .any { File(restoreDir, it).exists() }
        if (!hasKnownContent) {
            lastError = "ZIP berhasil diekstrak tapi tidak berisi struktur backup yang dikenali " +
                "(pkg-cache/android-sdk/.gradle/wrapper-template). Isi ZIP: " +
                (restoreDir.listFiles()?.joinToString(", ") { it.name } ?: "(kosong)")
            restoreDir.deleteRecursively()
            return false
        }

        // Install .deb offline (best-effort, tidak fatal bila sebagian gagal — tapi dicatat)
        val pkgCache = File(restoreDir, "pkg-cache")
        val debs = pkgCache.listFiles()?.filter { it.extension == "deb" }
        if (!debs.isNullOrEmpty()) {
            val debArgs = debs.joinToString(" ") { "'${it.absolutePath}'" }
            val dpkgResult = executor.executeShellCommand(
                "dpkg -i --force-depends $debArgs",
                lineCallback = lineCb,
                timeoutSeconds = 900
            )
            if (!dpkgResult.isSuccess) {
                Logger.logWarn(LOG_TAG, "dpkg -i sebagian gagal (non-fatal): ${tailOf(dpkgResult)}")
            }
        }

        // Restore SDK (tanpa ndk — NDK di-restore dari archive terpisah)
        val sdkBackup = File(restoreDir, "android-sdk")
        if (sdkBackup.exists()) {
            val r = executor.executeShellCommand(
                "rsync -a '${sdkBackup.absolutePath}/' '$sdkDir/'",
                lineCallback = lineCb,
                timeoutSeconds = 900
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore android-sdk gagal (non-fatal): ${tailOf(r)}")
        }
        val gradleBackup = File(restoreDir, ".gradle")
        if (gradleBackup.exists()) {
            val r = executor.executeShellCommand(
                "rsync -a '${gradleBackup.absolutePath}/' '${BuilderPaths.DEFAULT_HOME_DIR}/.gradle/'",
                lineCallback = lineCb,
                timeoutSeconds = 900
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore .gradle gagal (non-fatal): ${tailOf(r)}")
        }
        val wrapperBackup = File(restoreDir, "wrapper-template")
        if (wrapperBackup.exists()) {
            val r = executor.executeShellCommand(
                "rsync -a '${wrapperBackup.absolutePath}/' '${BuilderPaths.DEFAULT_WRAPPER_DIR}/'",
                lineCallback = lineCb,
                timeoutSeconds = 300
            )
            if (!r.isSuccess) Logger.logWarn(LOG_TAG, "restore wrapper-template gagal (non-fatal): ${tailOf(r)}")
        }

        restoreDir.deleteRecursively()
        return true
    }
}