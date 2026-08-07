package com.termux.builder.repo

import android.content.Context
import android.content.SharedPreferences
import com.termux.builder.model.BuildConfig
import com.termux.builder.model.BuildMode
import com.termux.builder.model.BuildResult
import com.termux.builder.model.BuilderPaths

/**
 * Repository persistensi state builder.
 *
 * Menyimpan:
 *  - Last project path (pemetaan get_last_project / save_last_project di build.sh)
 *  - Riwayat build terakhir (hasil, apk path, timestamp)
 *
 * Backed by SharedPreferences (persistent di app), bukan file di storage.
 */
class BuildRepository(context: Context) {

    companion object {
        private const val PREFS_NAME = "termux_builder_state"

        private const val KEY_LAST_PROJECT = "last_project_path"
        private const val KEY_LAST_RESULT_SUCCESS = "last_result_success"
        private const val KEY_LAST_RESULT_MESSAGE = "last_result_message"
        private const val KEY_LAST_RESULT_APK = "last_result_apk"
        private const val KEY_LAST_RESULT_TIMESTAMP = "last_result_timestamp"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Simpan project terakhir (build.sh save_last_project). */
    fun saveLastProject(path: String) {
        if (path.isBlank()) return
        prefs.edit().putString(KEY_LAST_PROJECT, path).apply()
    }

    /** Baca project terakhir (build.sh get_last_project). */
    fun getLastProject(): String? {
        val path = prefs.getString(KEY_LAST_PROJECT, null) ?: return null
        return if (java.io.File(path).isDirectory) path else null
    }

    /** Simpan hasil build terakhir. */
    fun saveLastResult(result: BuildResult) {
        prefs.edit()
            .putBoolean(KEY_LAST_RESULT_SUCCESS, result.success)
            .putString(KEY_LAST_RESULT_MESSAGE, result.message)
            .putString(KEY_LAST_RESULT_APK, result.apkPath)
            .putLong(KEY_LAST_RESULT_TIMESTAMP, System.currentTimeMillis())
            .apply()
    }

    /** Baca hasil build terakhir. */
    fun getLastResult(): BuildResult? {
        if (!prefs.contains(KEY_LAST_RESULT_TIMESTAMP)) return null
        return BuildResult(
            success = prefs.getBoolean(KEY_LAST_RESULT_SUCCESS, false),
            phase = com.termux.builder.model.BuildPhase.SUCCESS,
            message = prefs.getString(KEY_LAST_RESULT_MESSAGE, "") ?: "",
            apkPath = prefs.getString(KEY_LAST_RESULT_APK, null)
        )
    }

    /** Buat BuildConfig dari project terakhir (untuk quick re-build). */
    fun buildConfigFromLastProject(mode: BuildMode = BuildMode.DEBUG_FAST): BuildConfig? {
        val last = getLastProject() ?: return null
        return BuildConfig(projectPath = last, mode = mode)
    }

    /** Buat BuildConfig dari path manual. */
    fun buildConfig(path: String, mode: BuildMode = BuildMode.DEBUG_FAST): BuildConfig {
        return BuildConfig(projectPath = path, mode = mode)
    }
}
