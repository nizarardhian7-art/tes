package com.termux.app.builder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.termux.builder.model.BuildMode
import com.termux.builder.model.BuildResult
import com.termux.builder.orchestrator.BuildOrchestrator
import com.termux.builder.repo.BuildRepository
import java.io.File

/**
 * ViewModel dashboard builder (v5).
 *
 * Arsitektur v5: UI 100% Kotlin, log live TIDAK ada lagi di app. Semua
 * operasi berat (build/import/export/setup/native) diluncurkan ke terminal
 * Termux asli via [BuilderScriptLauncher]. ViewModel hanya:
 *  - Menyimpan & membaca project terakhir / hasil terakhir.
 *  - Memberi sinyal ke UI bahwa operasi sedang "diluncurkan ke terminal".
 *  - Menyimpan hasil build saat user kembali dari terminal (via
 *    [BuildOrchestrator.saveResult]).
 */
class BuilderViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = BuildRepository(application)
    private val orchestrator = BuildOrchestrator(application)

    private val _isRunning = MutableLiveData(false)
    val isRunning: LiveData<Boolean> = _isRunning

    private val _lastProject = MutableLiveData<String?>()
    val lastProject: LiveData<String?> = _lastProject

    private val _result = MutableLiveData<BuildResult?>()
    val result: LiveData<BuildResult?> = _result

    /** Pesan sekali-kali (snackbar/toast). */
    private val _eventMessage = MutableLiveData<Pair<String, Long>?>()
    val eventMessage: LiveData<Pair<String, Long>?> = _eventMessage

    init {
        _lastProject.value = repo.getLastProject()
        _result.value = repo.getLastResult()
    }

    /**
     * Mulai build project. Operasi dijalankan di terminal Termux; UI tidak
     * menampilkan log live. Setelah terminal selesai, TermuxActivity otomatis
     * finish() sehingga user kembali ke app.
     */
    fun startBuild(projectPath: String, mode: BuildMode) {
        if (projectPath.isBlank()) return
        _isRunning.value = true
        _eventMessage.value = null
        val launched = orchestrator.startBuild(
            com.termux.builder.model.BuildConfig(projectPath = projectPath, mode = mode)
        )
        if (!launched) {
            _isRunning.value = false
            _eventMessage.value = Pair(
                "Gagal meluncurkan build: path project tidak valid ($projectPath)",
                System.currentTimeMillis()
            )
        }
    }

    /** Jalankan setup toolchain (idempotent, resume-safe) di terminal Termux. */
    fun startSetup() {
        _isRunning.value = true
        orchestrator.startSetup()
    }

    /** Jalankan build project native di terminal Termux. */
    fun startNativeBuild(projectPath: String) {
        if (projectPath.isBlank()) return
        _isRunning.value = true
        if (!orchestrator.startNativeBuild(projectPath)) {
            _isRunning.value = false
        }
    }

    /** Import backup environment dari file (path hasil SAF copy). */
    fun importBackup(filePath: String) {
        if (filePath.isBlank()) return
        _isRunning.value = true
        _eventMessage.value = null
        val backupManager = com.termux.builder.backup.BackupManager(getApplication())
        val ok = backupManager.importEnvironmentBackupFromFile(File(filePath))
        if (!ok) {
            _isRunning.value = false
            val reason = backupManager.lastError ?: "Alasan tidak diketahui"
            _eventMessage.value = Pair("Gagal memulai import: $reason", System.currentTimeMillis())
        }
    }

    /** Export backup environment ke /sdcard/BuildOutputs (di terminal). */
    fun exportBackup() {
        _isRunning.value = true
        _eventMessage.value = null
        val backupManager = com.termux.builder.backup.BackupManager(getApplication())
        val launched = backupManager.exportEnvironmentBackup()
        if (launched == null) {
            _isRunning.value = false
            val reason = backupManager.lastError ?: "Alasan tidak diketahui"
            _eventMessage.value = Pair("Gagal memulai export: $reason", System.currentTimeMillis())
        }
    }

    /** Simpan hasil build terakhir (dipanggil UI saat kembali dari terminal). */
    fun saveResult(result: BuildResult) {
        _result.value = result
        repo.saveLastResult(result)
    }

    /** Tandai operasi selesai (dipanggil UI saat kembali dari terminal). */
    fun onOperationFinished() {
        _isRunning.value = false
    }

    /** Reset state hasil. */
    fun clearResult() {
        _result.value = null
    }

    override fun onCleared() {
        super.onCleared()
    }
}
