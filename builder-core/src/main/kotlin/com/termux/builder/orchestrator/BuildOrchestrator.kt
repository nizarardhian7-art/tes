package com.termux.builder.orchestrator

import android.content.Context
import com.termux.app.builder.BuilderScriptLauncher
import com.termux.builder.model.BuildConfig
import com.termux.builder.model.BuildMode
import com.termux.builder.model.BuildPhase
import com.termux.builder.model.BuildResult
import com.termux.builder.repo.BuildRepository

/**
 * Orchestrator build (v5).
 *
 * Arsitektur v5: semua logic berat dipindahkan ke `builder_core.sh` dan
 * dijalankan di terminal Termux asli. Kelas ini TIDAK lagi mengimplementasikan
 * setup toolchain / patch gradle / process exec — semuanya dilakukan script
 * (reuse logika build_ref.sh yang sudah terbukti berhasil di device user).
 *
 * Tanggung jawab:
 *  - Validasi input minimal (path project & mode).
 *  - Menyimpan project terakhir (BuildRepository) supaya quick re-build
 *    konsisten dengan `save_last_project` di build.sh.
 *  - Meluncurkan script `builder_core.sh build <project> <mode>` di terminal
 *    Termux (BuilderScriptLauncher). Output live + finish() balik ke app
 *    ditangani sepenuhnya oleh terminal Termux / TermuxActivity.
 */
class BuildOrchestrator(private val context: Context) {

    private val repo = BuildRepository(context)

    /**
     * Mulai build project via terminal Termux.
     *
     * @return true bila command berhasil diluncurkan (bukan hasil build —
     *         hasil build dilihat langsung di terminal Termux).
     */
    fun startBuild(config: BuildConfig): Boolean {
        val projectPath = config.projectPath
        if (projectPath.isBlank()) return false

        val projectFile = java.io.File(projectPath)
        if (!projectFile.isDirectory) return false

        // Simpan project terakhir (konsisten dengan save_last_project di build.sh)
        repo.saveLastProject(projectPath)

        // Launch script di terminal Termux. TermuxActivity otomatis finish()
        // saat session selesai -> kembali ke APK Builder.
        BuilderScriptLauncher.launchBuild(context, projectPath, config.mode)
        return true
    }

    /** Jalankan setup toolchain (idempotent, resume-safe) di terminal Termux. */
    fun startSetup(): Boolean {
        BuilderScriptLauncher.launchSetup(context)
        return true
    }

    /** Jalankan build native via terminal Termux. */
    fun startNativeBuild(projectPath: String): Boolean {
        if (projectPath.isBlank()) return false
        if (!java.io.File(projectPath).isDirectory) return false
        BuilderScriptLauncher.launchNative(context, projectPath)
        return true
    }

    /** Baca hasil build terakhir yang tersimpan (untuk tampilan ringkas di UI). */
    fun lastResult(): BuildResult? = repo.getLastResult()

    /** Simpan hasil build (dipanggil UI setelah kembali dari terminal). */
    fun saveResult(result: BuildResult) = repo.saveLastResult(result)

    companion object {
        /** Mode default bila tidak ditentukan. */
        fun defaultConfig(projectPath: String): BuildConfig =
            BuildConfig(projectPath = projectPath, mode = BuildMode.DEBUG_FAST)
    }
}
