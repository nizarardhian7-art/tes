package com.termux.builder.backup

import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuilderPaths
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

        /** Direktori backup file yang di-patch (di state dir). */
        val PATCH_BACKUP_DIR: String get() = "${BuilderPaths.APP_STATE_DIR}/patch-backups"
    }

    /** Daftar file yang di-backup pada sesi ini (untuk rollback). */
    private val backedUpFiles = ArrayList<File>()

    /**
     * Backup file sebelum dipatch. Menyimpan salinan ke state dir dengan nama
     * hash path agar aman dari konflik.
     */
    fun backupFileForPatch(file: File): File? {
        if (!file.exists()) return null
        val backupDir = File(PATCH_BACKUP_DIR)
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
        return File(PATCH_BACKUP_DIR, "$safeName$BACKUP_SUFFIX").takeIf { it.exists() }
    }

    /** Bersihkan semua backup patch lama. */
    fun clearPatchBackups() {
        File(PATCH_BACKUP_DIR).deleteRecursively()
    }

    /**
     * Export environment build lengkap ke ZIP di output dir.
     * @return path zip yang dihasilkan, atau null bila gagal
     */
    fun exportEnvironmentBackup(sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR): String? {
        val stage = File("${BuilderPaths.DEFAULT_HOME_DIR}/.backup-temp")
        stage.deleteRecursively()
        File(stage, "pkg-cache").mkdirs()

        val sdk = File(sdkDir)
        if (!sdk.exists()) return null

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
            "cd '${stage.absolutePath}' && zip -q -r '${zipPath.absolutePath}' . 2>/dev/null && echo OK || echo FAIL",
            timeoutSeconds = 900
        )
        stage.deleteRecursively()
        return if (result.isSuccess && result.stdout.contains("OK")) zipPath.absolutePath else null
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

        val restoreDir = File("${BuilderPaths.DEFAULT_HOME_DIR}/.restore-temp")
        restoreDir.deleteRecursively()
        restoreDir.mkdirs()

        val extract = executor.executeShellCommand(
            "unzip -q -o '${backup.absolutePath}' -d '${restoreDir.absolutePath}/' 2>/dev/null && echo OK || echo FAIL",
            timeoutSeconds = 900
        )
        if (!extract.isSuccess || !extract.stdout.contains("OK")) {
            restoreDir.deleteRecursively()
            return false
        }

        // Install .deb offline
        val pkgCache = File(restoreDir, "pkg-cache")
        val debs = pkgCache.listFiles()?.filter { it.extension == "deb" }
        if (!debs.isNullOrEmpty()) {
            val debArgs = debs.joinToString(" ") { "'${it.absolutePath}'" }
            executor.executeShellCommand(
                "dpkg -i --force-depends $debArgs 2>/dev/null || true",
                timeoutSeconds = 900
            )
        }

        // Restore SDK (tanpa ndk — NDK di-restore dari archive terpisah)
        val sdkBackup = File(restoreDir, "android-sdk")
        if (sdkBackup.exists()) {
            executor.executeShellCommand(
                "rsync -a '${sdkBackup.absolutePath}/' '$sdkDir/' 2>/dev/null || true",
                timeoutSeconds = 900
            )
        }
        val gradleBackup = File(restoreDir, ".gradle")
        if (gradleBackup.exists()) {
            executor.executeShellCommand(
                "rsync -a '${gradleBackup.absolutePath}/' '${BuilderPaths.DEFAULT_HOME_DIR}/.gradle/' 2>/dev/null || true",
                timeoutSeconds = 900
            )
        }
        val wrapperBackup = File(restoreDir, "wrapper-template")
        if (wrapperBackup.exists()) {
            executor.executeShellCommand(
                "rsync -a '${wrapperBackup.absolutePath}/' '${BuilderPaths.DEFAULT_WRAPPER_DIR}/' 2>/dev/null || true",
                timeoutSeconds = 300
            )
        }

        restoreDir.deleteRecursively()
        return true
    }
}
