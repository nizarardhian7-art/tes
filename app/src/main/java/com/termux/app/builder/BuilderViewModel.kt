package com.termux.app.builder

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.termux.builder.model.BuildMode
import com.termux.builder.model.BuildPhase
import com.termux.builder.model.BuildProgress
import com.termux.builder.model.BuildResult
import com.termux.builder.repo.BuildRepository
import java.io.File

/**
 * ViewModel dashboard builder.
 *
 * Menghubungkan UI ke BuildForegroundService via listener statis (event bus
 * sederhana, karena service & activity punya lifecycle berbeda).
 *
 * v3: menambah dukungan Import/Export backup environment + event message
 * (toast/snackbar) + elapsed time build.
 */
class BuilderViewModel(application: Application) : AndroidViewModel(application) {

    private val repo = BuildRepository(application)

    private val _progress = MutableLiveData(BuildProgress(BuildPhase.IDLE, "Idle"))
    val progress: LiveData<BuildProgress> = _progress

    private val _result = MutableLiveData<BuildResult?>()
    val result: LiveData<BuildResult?> = _result

    private val _isBuilding = MutableLiveData(false)
    val isBuilding: LiveData<Boolean> = _isBuilding

    private val _lastProject = MutableLiveData<String?>()
    val lastProject: LiveData<String?> = _lastProject

    /** Pesan sekali-kali (untuk snackbar/toast). Dipakai ulang dengan timestamp. */
    private val _eventMessage = MutableLiveData<Pair<String, Long>?>()
    val eventMessage: LiveData<Pair<String, Long>?> = _eventMessage

    /** Detail log kumulatif (line terakhir) untuk panel log. */
    private val _logLine = MutableLiveData<String>()
    val logLine: LiveData<String> = _logLine

    /** Kapan build mulai (epoch ms) untuk elapsed timer. */
    private var buildStartTime: Long = 0

    init {
        _lastProject.value = repo.getLastProject()
        _result.value = repo.getLastResult()

        // Daftarkan listener ke service
        BuildForegroundService.listener = { progress ->
            _progress.postValue(progress)
            if (progress.detail.isNotBlank()) {
                _logLine.postValue(progress.detail)
            }
            when (progress.phase) {
                BuildPhase.SUCCESS, BuildPhase.FAILED, BuildPhase.CANCELLED -> {
                    _isBuilding.postValue(false)
                    val result = BuildForegroundService.lastResult
                    if (result != null) {
                        _result.postValue(result)
                        repo.saveLastResult(result)
                    }
                }
                else -> {
                    _isBuilding.postValue(true)
                    if (progress.phase == BuildPhase.SCANNING && buildStartTime == 0L) {
                        buildStartTime = System.currentTimeMillis()
                    }
                }
            }
        }

        // Jika service sedang berjalan, sync state awal
        if (BuildForegroundService.isBuilding()) {
            _isBuilding.value = true
        }
    }

    /** Mulai build project di service background. */
    fun startBuild(projectPath: String, mode: BuildMode) {
        buildStartTime = System.currentTimeMillis()
        _isBuilding.value = true
        _progress.value = BuildProgress(BuildPhase.SCANNING, "Memulai build...", 1, detail = "Menjalankan ./gradlew ${mode.buildType.gradleTask}")
        BuildForegroundService.startBuild(getApplication(), projectPath, mode)
    }

    /** Batal build aktif. */
    fun cancelBuild() {
        BuildForegroundService.cancelBuild(getApplication())
    }

    /** Import backup environment dari file terpilih (SAF path). */
    fun importBackup(filePath: String) {
        if (filePath.isBlank()) return
        _eventMessage.value = null
        _logLine.postValue("► Mulai import backup: $filePath")
        Thread {
            val backupManager = com.termux.builder.backup.BackupManager(
                com.termux.builder.exec.ProcessExecutor(getApplication())
            )
            val liveLog = object : com.termux.builder.exec.ProcessExecutor.LineCallback {
                override fun onLine(line: String) {
                    if (line.isNotBlank()) _logLine.postValue(line)
                }
            }
            val ok = backupManager.importEnvironmentBackupFromFile(File(filePath), lineCb = liveLog)
            val msg = if (ok) {
                getApplication<Application>().getString(com.termux.R.string.builder_import_success)
            } else {
                // Tampilkan ALASAN ASLI, bukan string generik — ini yang sebelumnya hilang.
                val reason = backupManager.lastError ?: "Alasan tidak diketahui (lihat log di panel di atas)"
                "${getApplication<Application>().getString(com.termux.R.string.builder_import_failed)}: $reason"
            }
            _logLine.postValue(if (ok) "✓ $msg" else "✘ $msg")
            _eventMessage.postValue(Pair(msg, System.currentTimeMillis()))
        }.start()
    }

    /** Export backup environment ke output dir. */
    fun exportBackup() {
        _eventMessage.value = null
        _logLine.postValue("► Mulai export backup environment...")
        Thread {
            val backupManager = com.termux.builder.backup.BackupManager(
                com.termux.builder.exec.ProcessExecutor(getApplication())
            )
            val liveLog = object : com.termux.builder.exec.ProcessExecutor.LineCallback {
                override fun onLine(line: String) {
                    if (line.isNotBlank()) _logLine.postValue(line)
                }
            }
            val zipPath = backupManager.exportEnvironmentBackup(lineCb = liveLog)
            val msg = if (zipPath != null) {
                getApplication<Application>().getString(com.termux.R.string.builder_export_success, zipPath)
            } else {
                val reason = backupManager.lastError ?: "Alasan tidak diketahui (lihat log di panel di atas)"
                "${getApplication<Application>().getString(com.termux.R.string.builder_export_failed)}: $reason"
            }
            _logLine.postValue(if (zipPath != null) "✓ $msg" else "✘ $msg")
            _eventMessage.postValue(Pair(msg, System.currentTimeMillis()))
        }.start()
    }

    /** Kosongkan panel log. */
    fun clearLog() {
        _logLine.value = ""
    }

    /** Reset elapsed timer (untuk build baru). */
    fun resetTimer() {
        buildStartTime = 0L
    }

    val elapsedSeconds: Long
        get() = if (buildStartTime == 0L) 0L else (System.currentTimeMillis() - buildStartTime) / 1000

    override fun onCleared() {
        super.onCleared()
        // Jangan hapus listener service saat UI hilang — build harus lanjut di background
    }
}
