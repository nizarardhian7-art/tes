package com.termux.builder.sync

import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuilderPaths
import java.io.File

/**
 * Sinkronisasi workspace presisi (Precision Sync) — pemetaan dari blok rsync
 * pada build.sh build_project() / build_native_project().
 *
 * Menyalin source project dari storage ke workspace dengan rsync --delete
 * (menghapus file yang sudah tidak ada di source, tapi menjaga folder cache
 * build/.gradle/.cxx/.idea).
 */
class WorkspaceSync(private val executor: ProcessExecutor) {

    companion object {
        /** Exclude default untuk project Android (build.sh). */
        val ANDROID_EXCLUDES = listOf(
            "build/", "app/build/", ".gradle/", ".cxx/", ".idea/"
        )

        /** Exclude untuk project native (build.sh build_native_project). */
        val NATIVE_EXCLUDES = listOf(
            "build/", "build_native/", "libs/", "obj/", ".cxx/"
        )

        private val WORKSPACE_BASE = BuilderPaths.DEFAULT_WORKSPACE_DIR
    }

    /** Apakah rsync tersedia (via APT android-tools / rsync). */
    fun isRsyncAvailable(): Boolean = executor.isExecutableAvailable("rsync")

    /**
     * Sinkronkan source project ke workspace target.
     *
     * @param sourcePath path project asli (mis. /sdcard/MyApp)
     * @param nativeMode true untuk project native (exclude berbeda)
     * @param clean true untuk menghapus workspace target terlebih dahulu
     * @return path workspace target
     */
    fun sync(sourcePath: String, nativeMode: Boolean = false, clean: Boolean = false): String {
        val projectName = File(sourcePath).name
        val targetDir = if (nativeMode) {
            File(WORKSPACE_BASE, "Native_$projectName")
        } else {
            File(WORKSPACE_BASE, projectName)
        }

        if (clean) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        val excludes = if (nativeMode) NATIVE_EXCLUDES else ANDROID_EXCLUDES
        val excludeArgs = excludes.joinToString(" ") { "--exclude='$it'" }

        if (isRsyncAvailable()) {
            val result = executor.executeShellCommand(
                "rsync -a --delete $excludeArgs '${sourcePath}/' '${targetDir.absolutePath}/'",
                timeoutSeconds = 600
            )
            if (!result.isSuccess) {
                // JANGAN diam-diam lanjut dengan workspace yang mungkin kosong/parsial —
                // sebelumnya hasil rsync tidak pernah dicek sama sekali di sini.
                throw IllegalStateException(
                    "rsync sinkronisasi workspace gagal (exit ${result.exitCode}): " +
                        result.stderr.ifBlank { result.stdout }.trim().takeLast(300)
                )
            }
        } else {
            // Fallback: copy manual dengan exclude (pakai cp + find -delete)
            copyWithExcludes(File(sourcePath), targetDir, excludes)
        }

        // Verifikasi akhir: workspace tidak boleh kosong bila source-nya tidak kosong.
        val sourceHasFiles = File(sourcePath).walkTopDown().any { it.isFile }
        val targetHasFiles = targetDir.walkTopDown().any { it.isFile }
        if (sourceHasFiles && !targetHasFiles) {
            throw IllegalStateException(
                "Sinkronisasi workspace menghasilkan folder kosong padahal source " +
                    "'$sourcePath' berisi file. Cek permission storage."
            )
        }

        return targetDir.absolutePath
    }

    /** Fallback copy manual bila rsync tidak tersedia. */
    private fun copyWithExcludes(source: File, target: File, excludes: List<String>) {
        if (!source.isDirectory) return
        source.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val rel = file.absolutePath.removePrefix(source.absolutePath).removePrefix("/")
                excludes.none { ex ->
                    val exClean = ex.removeSuffix("/")
                    rel == exClean || rel.startsWith("$exClean/") || rel.contains("/$exClean/")
                }
            }
            .forEach { file ->
                val rel = file.absolutePath.removePrefix(source.absolutePath).removePrefix("/")
                val dest = File(target, rel)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
            }
    }

    /** Hapus workspace cache seluruhnya (menu "Clear All Workspace Cache"). */
    fun clearAllWorkspaceCache() {
        File(WORKSPACE_BASE).deleteRecursively()
        File("${BuilderPaths.PREFIX_BIN_DIR}/../../.gradle/daemon").deleteRecursively()
    }
}
