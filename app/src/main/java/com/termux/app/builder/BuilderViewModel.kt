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

/**
 * ViewModel dashboard builder.
 *
 * Menghubungkan UI ke BuildForegroundService via listener statis (event bus
 * sederhana, karena service & activity punya lifecycle berbeda).
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

    init {
        _lastProject.value = repo.getLastProject()
        _result.value = repo.getLastResult()

        // Daftarkan listener ke service
        BuildForegroundService.listener = { progress ->
            _progress.postValue(progress)
            if (progress.phase == BuildPhase.SUCCESS ||
                progress.phase == BuildPhase.FAILED ||
                progress.phase == BuildPhase.CANCELLED
            ) {
                _isBuilding.postValue(false)
                val result = BuildForegroundService.lastResult
                if (result != null) {
                    _result.postValue(result)
                    repo.saveLastResult(result)
                }
            } else {
                _isBuilding.postValue(true)
            }
        }

        // Jika service sedang berjalan, sync state awal
        if (BuildForegroundService.isBuilding()) {
            _isBuilding.value = true
        }
    }

    /** Mulai build project di service background. */
    fun startBuild(projectPath: String, mode: BuildMode) {
        _isBuilding.value = true
        _progress.value = BuildProgress(BuildPhase.SCANNING, "Memulai build...", 1)
        BuildForegroundService.startBuild(getApplication(), projectPath, mode)
    }

    /** Batal build aktif. */
    fun cancelBuild() {
        BuildForegroundService.cancelBuild(getApplication())
    }

    override fun onCleared() {
        super.onCleared()
        // Jangan hapus listener service saat UI hilang — build harus lanjut di background
    }
}
