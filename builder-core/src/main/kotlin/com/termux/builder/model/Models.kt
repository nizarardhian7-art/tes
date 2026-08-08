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

    /** Direktori backup file Gradle yang dipatch sebelum diubah (untuk rollback). */
    const val PATCH_BACKUP_DIR = APP_STATE_DIR + "/patch-backups"

    /** $HOME/android-sdk */
    const val DEFAULT_SDK_DIR = BuilderPathsInternal.TERMUX_HOME_DIR_PATH + "/android-sdk"

    /**
     * Versi NDK default yang dipakai builder.
     * HARUS konsisten dengan yang benar-benar diinstall oleh [com.termux.builder.toolchain.ToolchainManager]
     * (android-ndk-r29-aarch64.7z dari Lzhiyong/termux-ndk, diekstrak menjadi 29.0.14206865).
     * Ketidakkonsistenan di versi lama (25.2.9519653 vs 29.0.14206865) menyebabkan AGP
     * mencari NDK yang tidak ada lalu mendownload ulang / gagal.
     */
    const val DEFAULT_NDK_VERSION = "29.0.14206865"

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

    /** $HOME/.gradle — GRADLE_USER_HOME (berisi wrapper/dists + caches). */
    val DEFAULT_GRADLE_HOME: String get() = DEFAULT_HOME_DIR + "/.gradle"

    /** $GRADLE_HOME/wrapper/dists — distribusi Gradle yang sudah diunduh. */
    val GRADLE_WRAPPER_DISTS: String get() = DEFAULT_GRADLE_HOME + "/wrapper/dists"
}

/**
 * Katalog versi & URL resmi dependency toolchain (untuk offline backup & dokumentasi).
 * Semua URL diverifikasi valid (2026-08).
 */
object DependencyCatalog {
    /** NDK r29 (aarch64, Termux) — diekstrak ke $SDK/ndk/29.0.14206865 */
    const val NDK_R29_URL = "https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.7z"
    const val NDK_R29_VERSION = "29.0.14206865"

    /** NDK r25c (aarch64, Termux) — fallback untuk project lama */
    const val NDK_R25C_URL = "https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r25c-aarch64.zip"
    const val NDK_R25C_VERSION = "25.2.9519653"

    /** Base URL repositori resmi Google. */
    const val GOOGLE_REPO_BASE = "https://dl.google.com/android/repository/"

    /**
     * Nama file ZIP platform SDK resmi Google per API level.
     * Diverifikasi 2026-08-08: platform-34_r01/r02/r04 TIDAK ADA (404) —
     * platform 34 hanya tersedia sebagai platform-34-ext7_r03.zip (revision 3, channel 0).
     * Untuk level lain yang tidak ada di map ini, builder mem-parse repository2-1.xml
     * secara live (lihat ToolchainManager.resolvePlatformZipUrl).
     */
    val PLATFORM_ZIP_FALLBACK: Map<Int, String> = mapOf(
        36 to "platform-36_r01.zip",
        35 to "platform-35_r01.zip",
        34 to "platform-34-ext7_r03.zip",
        33 to "platform-33_r01.zip",
        32 to "platform-32_r01.zip",
        31 to "platform-31_r01.zip",
        30 to "platform-30_r03.zip",
        29 to "platform-29_r05.zip",
        28 to "platform-28_r01.zip",
        27 to "platform-27_r03.zip",
        26 to "platform-26_r02.zip",
        25 to "platform-25_r03.zip",
        24 to "platform-24_r01.zip"
    )

    /** Nama file ZIP build-tools resmi Google per versi (diverifikasi valid). */
    val BUILD_TOOLS_ZIP: Map<String, String> = mapOf(
        "33.0.1" to "build-tools_r33.0.1-linux.zip",
        "34.0.0" to "build-tools_r34-linux.zip"
    )

    /** Nama file ZIP CMake resmi Google (diverifikasi valid). */
    val CMAKE_ZIP: Map<String, String> = mapOf(
        "3.18.1" to "cmake-3.18.1-linux.zip",
        "3.22.1" to "cmake-3.22.1-linux.zip"
    )

    /** Versi Gradle minimum per AGP (tabel resmi Android Developers). */
    val AGP_MIN_GRADLE: Map<String, String> = mapOf(
        "8.0" to "8.0", "8.1" to "8.0", "8.2" to "8.2", "8.3" to "8.4",
        "8.4" to "8.6", "8.5" to "8.7", "8.6" to "8.7", "8.7" to "8.9",
        "8.8" to "8.10.2", "8.9" to "8.11.1", "8.10" to "8.11.1",
        "8.11" to "8.13", "8.12" to "8.13", "8.13" to "8.13",
        "9.0" to "9.1.0", "9.1" to "9.3.1", "9.2" to "9.4.1", "9.3" to "9.5.0"
    )
}

/** Adapter tipis agar konstanta Termux hanya dipakai di satu tempat. */
internal object BuilderPathsInternal {
    const val TERMUX_HOME_DIR_PATH = "/data/data/com.termux/files/home"
    const val TERMUX_BIN_PREFIX_DIR_PATH = "/data/data/com.termux/files/usr/bin"
}