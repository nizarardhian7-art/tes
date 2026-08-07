package com.termux.builder.model

import java.io.File

/** Tipe build yang didukung engine. */
enum class BuildType {
    DEBUG, RELEASE;

    val gradleTask: String
        get() = if (this == DEBUG) "assembleDebug" else "assembleRelease"
}

/** Mode build (dipetakan dari menu build.sh: 1/2/3). */
enum class BuildMode {
    /** Build APK debug biasa. */
    DEBUG_FAST,

    /** Build APK release. */
    RELEASE_FAST,

    /** Clean + full rebuild debug (--rerun-tasks). */
    CLEAN_REBUILD_DEBUG;

    val buildType: BuildType
        get() = when (this) {
            DEBUG_FAST, CLEAN_REBUILD_DEBUG -> BuildType.DEBUG
            RELEASE_FAST -> BuildType.RELEASE
        }

    val isClean: Boolean
        get() = this == CLEAN_REBUILD_DEBUG
}

/** Jenis project yang ditemukan oleh [ProjectScanner]. */
enum class ProjectKind {
    ANDROID,
    NATIVE;

    val label: String
        get() = when (this) {
            ANDROID -> "Android (APK)"
            NATIVE -> "Native C/C++"
        }
}

/**
 * Konfigurasi lengkap satu sesi build.
 * Semua nilai path menggunakan absolute path (diambil dari TermuxConstants
 * sehingga selalu konsisten dengan environment Termux yang aktif).
 */
data class BuildConfig(
    val projectPath: String,
    val mode: BuildMode = BuildMode.DEBUG_FAST,
    val projectName: String = File(projectPath).name,
    val outputDir: String = BuilderPaths.DEFAULT_OUTPUT_DIR,
    val workspaceDir: String = BuilderPaths.DEFAULT_WORKSPACE_DIR,
    val sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR,
    val ndkVersion: String = BuilderPaths.DEFAULT_NDK_VERSION,
    val apkFileName: String = "${File(projectPath).name}-${mode.buildType.name.lowercase()}.apk"
)

/** Profile hardware yang diturunkan dari RAM (sama dengan build.sh detect_device_hardware). */
data class HardwareProfile(
    val ramMb: Long,
    val profileName: String,
    val jvmMaxHeap: String,
    val maxWorkers: Int,
    val ninjaJobs: Int
) {
    val gradleJvmArgs: String
        get() = "-Xmx$jvmMaxHeap -XX:MaxMetaspaceSize=384m -XX:+UseG1GC"
}

/** Informasi satu project hasil scan. */
data class ScannedProject(
    val path: String,
    val name: String,
    val kind: ProjectKind,
    val buildFiles: List<String> = emptyList()
)

/** Hasil scan: daftar project Android + native. */
data class ProjectScanResult(
    val androidProjects: List<ScannedProject>,
    val nativeProjects: List<ScannedProject>
) {
    val isEmpty: Boolean
        get() = androidProjects.isEmpty() && nativeProjects.isEmpty()
}

/** Status build yang dipancarkan ke UI via callback. */
enum class BuildPhase {
    IDLE,
    SCANNING,
    TOOLCHAIN_SETUP,
    SYNCING,
    PATCHING,
    BUILDING,
    COPYING,
    SUCCESS,
    FAILED,
    CANCELLED
}

/** Progress point yang dikirim BuildOrchestrator ke UI/FGS. */
data class BuildProgress(
    val phase: BuildPhase = BuildPhase.IDLE,
    val message: String = "",
    val percent: Int = 0,
    val detail: String = "",
    val buildType: BuildType? = null
)

/** Hasil akhir sebuah build. */
data class BuildResult(
    val success: Boolean,
    val phase: BuildPhase,
    val message: String,
    val apkPath: String? = null,
    val nativeOutputDir: String? = null,
    val elapsedSeconds: Long = 0,
    val errorSummary: String = ""
) {
    companion object {
        fun failure(phase: BuildPhase, message: String, summary: String = "", elapsed: Long = 0) =
            BuildResult(false, phase, message, errorSummary = summary, elapsedSeconds = elapsed)

        fun success(apkPath: String, elapsed: Long, message: String = "Build successful") =
            BuildResult(true, BuildPhase.SUCCESS, message, apkPath = apkPath, elapsedSeconds = elapsed)
    }
}

/** Hasil eksekusi satu perintah (dari ProcessExecutor). */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val cancelled: Boolean = false,
    val durationMs: Long = 0
) {
    val isSuccess: Boolean get() = exitCode == 0 && !cancelled
}

/** Ringkasan error dari LogStreamParser. */
data class ErrorSummary(
    val lines: List<String>,
    val rawTail: String
) {
    companion object {
        val EMPTY = ErrorSummary(emptyList(), "")
    }
}

/** Kumpulan path penting engine (pemetaan konstanta build.sh -> TermuxConstants). */
object BuilderPaths {
    /** $HOME — home Termux. */
    const val DEFAULT_HOME_DIR = BuilderPathsInternal.TERMUX_HOME_DIR_PATH

    /** $HOME/.termux-apk-builder — state engine. */
    const val APP_STATE_DIR = BuilderPathsInternal.TERMUX_HOME_DIR_PATH + "/.termux-apk-builder"

    /** File "last project" (persistensi menu). */
    const val LAST_PROJECT_FILE = APP_STATE_DIR + "/last_project.txt"

    /** $HOME/android-sdk */
    const val DEFAULT_SDK_DIR = BuilderPathsInternal.TERMUX_HOME_DIR_PATH + "/android-sdk"

    /** Versi NDK default yang dipakai build.sh (r25c). */
    const val DEFAULT_NDK_VERSION = "25.2.9519653"

    /** $SDK_DIR/ndk/<version> */
    val DEFAULT_NDK_DIR: String get() = "$DEFAULT_SDK_DIR/ndk/$DEFAULT_NDK_VERSION"

    /** $SDK_DIR/wrapper-template */
    const val DEFAULT_WRAPPER_DIR = DEFAULT_SDK_DIR + "/wrapper-template"

    /** $HOME/workspace — workspace sinkronisasi presisi. */
    const val DEFAULT_WORKSPACE_DIR = BuilderPathsInternal.TERMUX_HOME_DIR_PATH + "/workspace"

    /** /sdcard/BuildOutputs */
    const val DEFAULT_OUTPUT_DIR = "/sdcard/BuildOutputs"

    /** /sdcard/build-error.log */
    const val DEFAULT_LOG_FILE = "/sdcard/build-error.log"

    /** PREFIX/bin (dari TermuxConstants). */
    val PREFIX_BIN_DIR: String get() = BuilderPathsInternal.TERMUX_BIN_PREFIX_DIR_PATH
}

/** Adapter tipis agar konstanta Termux hanya dipakai di satu tempat. */
internal object BuilderPathsInternal {
    const val TERMUX_HOME_DIR_PATH = "/data/data/com.termux/files/home"
    const val TERMUX_BIN_PREFIX_DIR_PATH = "/data/data/com.termux/files/usr/bin"
}
