package com.termux.builder.orchestrator

import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuildConfig
import com.termux.builder.model.BuildProgress
import com.termux.builder.model.BuildResult
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.HardwareProfile
import com.termux.builder.patch.GradleProjectPatcher
import com.termux.builder.sync.WorkspaceSync
import com.termux.builder.toolchain.ToolchainManager
import java.io.File

/**
 * Engine build native-only (C/C++ CMake / Android.mk) — pemetaan dari
 * build.sh build_native_project().
 *
 * Alur: sync ke workspace -> deteksi build system (CMakeLists.txt / Android.mk)
 * -> eksekusi cmake+ninja ATAU ndk-build -> kumpulkan .so/binary ke output.
 */
class NativeBuildEngine(
    private val context: android.content.Context,
    private val executor: ProcessExecutor,
    private val toolchainManager: ToolchainManager,
    private val workspaceSync: WorkspaceSync,
    private val progress: (BuildProgress) -> Unit
) {

    companion object {
        private const val LOG_TAG = "NativeBuildEngine"

        private val OUTPUT_EXTENSIONS = listOf(".so")
        private val OUTPUT_EXECUTABLE_PATTERN = Regex(".*")
    }

    /**
     * Build project native.
     * @param sourcePath path project asli
     * @param profile profil hardware (untuk ninja jobs)
     * @return BuildResult
     */
    fun buildNative(sourcePath: String, profile: HardwareProfile): BuildResult {
        val startTime = System.currentTimeMillis()
        val projectName = File(sourcePath).name

        progress(BuildProgress(com.termux.builder.model.BuildPhase.SYNCING, "Synchronizing workspace...", 5))
        val workspaceDir = workspaceSync.sync(sourcePath, nativeMode = true)
        val projectRoot = File(workspaceDir)

        if (!toolchainManager.isNdkInstalled()) {
            progress(BuildProgress(com.termux.builder.model.BuildPhase.TOOLCHAIN_SETUP, "NDK belum terinstall, setup toolchain...", 10))
            toolchainManager.setupToolchain(profile) { msg -> /* progress toolchain */ }
        }

        // Deteksi build system
        val hasCMake = File(projectRoot, "CMakeLists.txt").exists()
        val hasAndroidMk = File(projectRoot, "Android.mk").exists() ||
            File(projectRoot, "jni/Android.mk").exists()

        if (!hasCMake && !hasAndroidMk) {
            return BuildResult.failure(
                com.termux.builder.model.BuildPhase.FAILED,
                "Tidak ada CMakeLists.txt atau Android.mk di $sourcePath"
            )
        }

        progress(BuildProgress(com.termux.builder.model.BuildPhase.BUILDING, "Menjalankan native build...", 30))
        val outputLog = StringBuilder()
        val lineHandler = { line: String ->
            outputLog.append(line).append('\n')
            progress(BuildProgress(
                com.termux.builder.model.BuildPhase.BUILDING,
                line.take(200),
                30 + (parsePercent(line) ?: 0) / 5
            ))
        }

        var buildOk = false
        if (hasCMake) {
            buildOk = buildWithCmake(projectRoot, profile, lineHandler)
        } else {
            buildOk = buildWithNdkBuild(projectRoot, profile, lineHandler)
        }

        if (executor.isCancelled) {
            return BuildResult(false, com.termux.builder.model.BuildPhase.CANCELLED, "Native build dibatalkan",
                elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000)
        }

        if (!buildOk) {
            return BuildResult.failure(
                com.termux.builder.model.BuildPhase.FAILED,
                "Native build gagal",
                summary = outputLog.toString().lines().filter { it.contains("error", ignoreCase = true) }.take(10).joinToString("\n"),
                elapsed = (System.currentTimeMillis() - startTime) / 1000
            )
        }

        // Kumpulkan output .so / executable
        progress(BuildProgress(com.termux.builder.model.BuildPhase.COPYING, "Mengumpulkan output...", 90))
        val outDir = File(BuilderPaths.DEFAULT_OUTPUT_DIR, "Native/$projectName")
        outDir.mkdirs()
        var collected = 0

        projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension == "so" || it.canExecute() }
            .filter { !it.name.endsWith(".sh") && !it.name.endsWith(".py") }
            .forEach { f ->
                try {
                    f.copyTo(File(outDir, f.name), overwrite = true)
                    collected++
                } catch (e: Exception) {
                    // skip
                }
            }

        val elapsed = (System.currentTimeMillis() - startTime) / 1000
        progress(BuildProgress(com.termux.builder.model.BuildPhase.SUCCESS, "Native build sukses: $collected file", 100))
        return BuildResult(
            success = true,
            phase = com.termux.builder.model.BuildPhase.SUCCESS,
            message = "Native build successful ($collected file)",
            nativeOutputDir = outDir.absolutePath,
            elapsedSeconds = elapsed
        )
    }

    private fun buildWithCmake(
        projectRoot: File,
        profile: HardwareProfile,
        lineHandler: (String) -> Unit
    ): Boolean {
        val buildDir = File(projectRoot, "build_native")
        buildDir.mkdirs()
        val ndkDir = File(BuilderPaths.DEFAULT_SDK_DIR, "ndk/${BuilderPaths.DEFAULT_NDK_VERSION}")

        val cmakeConfig = executor.executeShellCommand(
            "cmake -G Ninja " +
                "-DCMAKE_TOOLCHAIN_FILE='${ndkDir.absolutePath}/build/cmake/android.toolchain.cmake' " +
                "-DANDROID_ABI=arm64-v8a " +
                "-DANDROID_PLATFORM=android-24 " +
                "-DCMAKE_BUILD_TYPE=Release " +
                "'${projectRoot.absolutePath}'",
            workingDirectory = buildDir.absolutePath,
            lineCallback = object : ProcessExecutor.LineCallback {
                override fun onLine(line: String) = lineHandler(line)
            },
            timeoutSeconds = 1200
        )
        if (!cmakeConfig.isSuccess) return false

        val ninja = executor.executeShellCommand(
            "ninja -j${profile.ninjaJobs}",
            workingDirectory = buildDir.absolutePath,
            lineCallback = object : ProcessExecutor.LineCallback {
                override fun onLine(line: String) = lineHandler(line)
            },
            timeoutSeconds = 3600
        )
        return ninja.isSuccess
    }

    private fun buildWithNdkBuild(
        projectRoot: File,
        profile: HardwareProfile,
        lineHandler: (String) -> Unit
    ): Boolean {
        val ndkBuild = File(BuilderPaths.DEFAULT_SDK_DIR, "ndk/${BuilderPaths.DEFAULT_NDK_VERSION}/ndk-build")
        val result = executor.executeShellCommand(
            "'${ndkBuild.absolutePath}' NDK_PROJECT_PATH='${projectRoot.absolutePath}' " +
                "NDK_OUT='${File(projectRoot, "build").absolutePath}' " +
                "NDK_LIBS_OUT='${File(projectRoot, "libs").absolutePath}' -j${profile.ninjaJobs}",
            workingDirectory = projectRoot.absolutePath,
            lineCallback = object : ProcessExecutor.LineCallback {
                override fun onLine(line: String) = lineHandler(line)
            },
            timeoutSeconds = 3600
        )
        return result.isSuccess
    }

    private fun parsePercent(line: String): Int? {
        return Regex("(\\d{1,3})%").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }
}
