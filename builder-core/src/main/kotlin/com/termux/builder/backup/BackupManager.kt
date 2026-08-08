package com.termux.builder.backup

import android.content.Context
import com.termux.builder.script.BuilderScriptLauncher
import java.io.File

/**
 * Manager backup environment (v5).
 *
 * Arsitektur v5: TIDAK ada lagi logika backup/import di Kotlin. Semua kerja
 * berat (rsync android-sdk + NDK, .gradle wrapper dists + caches, pkg-cache
 * .deb, wrapper-template, verifikasi struktur, restore offline via dpkg -i)
 * dilakukan oleh `builder_core.sh` — non-interaktif wrapper dari build_ref.sh
 * yang sudah terbukti berhasil di device user.
 *
 * Kelas ini hanya:
 *  - Memvalidasi input (path zip untuk import).
 *  - Meluncurkan script di terminal Termux (Runner TERMINAL_SESSION) sehingga
 *    user melihat progress persis seperti di build.sh asli.
 */
class BackupManager(private val context: Context) {

    /** Alasan kegagalan terakhir (untuk ditampilkan di UI bila launch gagal). */
    var lastError: String? = null
        private set

    /**
     * Import backup environment.
     * @param zipPath path file backup .zip (hasil SAF copy ke cache dir)
     */
    fun importEnvironmentBackupFromFile(zipFile: File): Boolean {
        lastError = null
        if (!zipFile.exists() || zipFile.length() == 0L) {
            lastError = "File backup tidak ditemukan atau kosong: ${zipFile.absolutePath}"
            return false
        }
        try {
            BuilderScriptLauncher.launchImport(context, zipFile.absolutePath)
            return true
        } catch (e: Exception) {
            lastError = e.message ?: "Gagal meluncurkan import di terminal Termux"
            return false
        }
    }

    /**
     * Export backup environment.
     * @return null bila launch gagal (hasil zip dilihat di terminal Termux);
     *         path non-null hanya sebagai sinyal "berhasil diluncurkan".
     */
    fun exportEnvironmentBackup(): String? {
        lastError = null
        try {
            BuilderScriptLauncher.launchExport(context)
            return BuilderScriptLauncher.SCRIPT_NAME
        } catch (e: Exception) {
            lastError = e.message ?: "Gagal meluncurkan export di terminal Termux"
            return null
        }
    }

    companion object {
        private const val LOG_TAG = "BackupManager"
    }
}
