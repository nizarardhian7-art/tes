package com.termux.builder.orchestrator

import android.content.Context
import com.termux.builder.backup.BackupManager
import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.log.BuildLog
import com.termux.builder.log.LogStreamParser
import com.termux.builder.model.BuildConfig
import com.termux.builder.model.BuildPhase
import com.termux.builder.model.BuildProgress
import com.termux.builder.model.BuildResult
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.HardwareProfile
import com.termux.builder.patch.GradleProjectPatcher
import com.termux.builder.scan.ProjectScanner
import com.termux.builder.sync.WorkspaceSync
import com.termux.builder.toolchain.HardwareDetector
import com.termux.builder.toolchain.ToolchainManager
import com.termux.shared.logger.Logger
import java.io.File

/**
 * BuildOrchestrator — state machine utama.
 *
 * Memindahkan seluruh logic build.sh ke kode Kotlin dengan alur:
 *   SCANNING -> TOOLCHAIN_SETUP -> SYNCING -> PATCHING -> BUILDING -> COPYING -> SUCCESS/FAILED
 *
 * Semua proses eksternal (gradlew, apt, rsync, cmake, ninja) dieksekusi via
 * [ProcessExecutor] (AppShell) sehingga dapat dipantau dan dibatalkan.
 *
 * Thread-safety: instance ini dipakai dari satu thread worker (BuildForegroundService).
 */
class BuildOrchestrator(
    private val context: Context,
    private val executor: ProcessExecutor,
    private val progressCallback: (BuildProgress) -> Unit
) {

    companion object {
        private const val LOG_TAG = "BuildOrchestrator"
    }

    private val hardwareDetector = HardwareDetector()
    private val projectScanner = ProjectScanner()
    private val toolchainManager = ToolchainManager(context, executor)
    private val workspaceSync = WorkspaceSync(executor)
    private val patcher = GradleProjectPatcher()
    private val backupManager = BackupManager(executor)
    private val logParser = LogStreamParser()

    private var isRunning = false

    val isBuildRunning: Boolean get() = isRunning

    /** Batal build aktif. */
    fun cancel() {
        executor.cancel()
    }

    /**
     * Line callback default: kirim setiap baris output subprocess ke UI
     * (progress BUILDING dengan detail = baris).
     */
    private fun defaultLineCallback(): ProcessExecutor.LineCallback {
        return object : ProcessExecutor.LineCallback {
            override fun onLine(line: String) {
                if (line.isNotBlank()) {
                    progressCallback(BuildProgress(
                        BuildPhase.BUILDING,
                        line.take(300),
                        percent = 0,
                        detail = line
                    ))
                }
            }
        }
    }

    /**
     * Kirim pesan log terstruktur ke UI dengan phase tertentu.
     * Semua pesan lewat [BuildLog] agar konsisten (section/step/ok/warn/error).
     */
    private fun log(phase: BuildPhase, msg: String, percent: Int) {
        progressCallback(BuildProgress(phase, msg.take(300), percent, detail = msg))
    }

    /**
     * Jalankan build APK penuh.
     * @param config konfigurasi build (project path, mode)
     * @return BuildResult
     */
    fun buildApk(config: BuildConfig): BuildResult {
        if (isRunning) {
            return BuildResult.failure(BuildPhase.FAILED, "Build sudah berjalan")
        }
        isRunning = true
        executor.reset()
        val startTime = System.currentTimeMillis()
        logParser.reset()

        try {
            val projectRoot = File(config.projectPath)
            if (!projectRoot.isDirectory) {
                return finish(BuildResult.failure(BuildPhase.FAILED, "Project path tidak ditemukan: ${config.projectPath}"))
            }

            // ---- 1. SCANNING ----
            log(BuildPhase.SCANNING, BuildLog.section("MEMULAI BUILD — ${File(config.projectPath).name} (${config.mode.buildType.gradleTask})"), 2)
            log(BuildPhase.SCANNING, BuildLog.step(1, 8, "Scanning project..."), 2)
            val scanResult = projectScanner.scan(config.projectPath)
            val project = scanResult.androidProjects.firstOrNull { it.path == config.projectPath }
                ?: scanResult.androidProjects.firstOrNull { it.path == File(config.projectPath).absolutePath }
                ?: scanResult.androidProjects.firstOrNull()
            if (project == null) {
                return finish(BuildResult.failure(BuildPhase.FAILED, "Tidak ada project Android valid di ${config.projectPath}"))
            }
            log(BuildPhase.SCANNING, BuildLog.ok("Project: ${project.name}"), 2)

            // ---- 2. HARDWARE PROFILE ----
            val profile = hardwareDetector.detect()
            log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.step(2, 8, "Hardware: ${profile.profileName} (${profile.ramMb} MB)"), 3)

            // ---- 3. TOOLCHAIN SETUP (skip jika sudah lengkap) ----
            log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.step(3, 8, "Memeriksa toolchain..."), 4)
            val sdkReady = toolchainManager.isSdkReady()
            val ndkReady = toolchainManager.isNdkInstalled()
            if (sdkReady && ndkReady) {
                log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.ok("SDK & NDK sudah siap — skip setup toolchain."), 10)
            } else {
                log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.warn("Toolchain belum lengkap (SDK=$sdkReady, NDK=$ndkReady), setup..."), 5)
                val setupOk = toolchainManager.setupToolchain(profile) { msg ->
                    log(BuildPhase.TOOLCHAIN_SETUP, msg, 8)
                }
                if (!setupOk) {
                    val reason = toolchainManager.lastError ?: "Setup toolchain gagal (alasan tidak diketahui — lihat log di atas)"
                    return finish(BuildResult.failure(BuildPhase.TOOLCHAIN_SETUP, reason))
                }
            }

            // ---- 4. SYNC WORKSPACE ----
            log(BuildPhase.SYNCING, BuildLog.step(4, 8, "Synchronizing workspace (precision sync)..."), 15)
            val workspaceDir = workspaceSync.sync(config.projectPath, nativeMode = false, clean = config.mode.isClean)
            val targetRoot = File(workspaceDir)

            // ---- 5. DETECT SDK/NDK/AGP REQUIREMENTS ----
            val gradleFiles = patcher.findGradleFiles(targetRoot)
            if (gradleFiles.isEmpty()) {
                return finish(BuildResult.failure(BuildPhase.FAILED, "Tidak ada file Gradle di workspace"))
            }

            val compileSdk = patcher.detectCompileSdk(gradleFiles)
            log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.step(5, 8, "Memastikan platform android-$compileSdk..."), 20)
            // v2: hasil ensurePlatformSdk DIPERIKSA — versi lama mengabaikan return value
            val platformOk = toolchainManager.ensurePlatformSdk(compileSdk, progress = { msg ->
                log(BuildPhase.TOOLCHAIN_SETUP, msg, 20)
            })
            if (!platformOk) {
                val reason = toolchainManager.lastError ?: "Platform android-$compileSdk tidak tersedia"
                return finish(BuildResult.failure(BuildPhase.TOOLCHAIN_SETUP, reason))
            }

            // Build-tools & cmake dummy yang diminta project
            val btVer = patcher.detectBuildToolsVersion(gradleFiles)
            if (btVer != null) toolchainManager.setupDummyBuildTools(btVer)
            toolchainManager.setupDummyBuildTools("33.0.1")
            toolchainManager.setupDummyBuildTools("34.0.0")
            toolchainManager.setupDummyCmake("3.22.1")
            toolchainManager.setupDummyCmake("3.18.1")

            // Wrapper template
            toolchainManager.ensureWrapperTemplate(progress = { msg ->
                log(BuildPhase.TOOLCHAIN_SETUP, msg, 22)
            })

            // ---- 6. WRAPPER & GRADLE VERSION ----
            log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.step(6, 8, "Menyiapkan Gradle wrapper..."), 24)
            val wrapperProps = File(targetRoot, "gradle/wrapper/gradle-wrapper.properties")
            val gradlew = File(targetRoot, "gradlew")
            if (!wrapperProps.exists()) {
                // Salin dari template
                val templateProps = File("${BuilderPaths.DEFAULT_WRAPPER_DIR}/gradle/wrapper/gradle-wrapper.properties")
                if (templateProps.exists()) {
                    File(targetRoot, "gradle/wrapper").mkdirs()
                    templateProps.copyTo(wrapperProps, overwrite = true)
                }
                if (!gradlew.exists()) {
                    val templateGradlew = File(BuilderPaths.DEFAULT_WRAPPER_DIR, "gradlew")
                    if (templateGradlew.exists()) templateGradlew.copyTo(gradlew, overwrite = true)
                }
            }

            // Pastikan gradle-wrapper.jar ADA di project (sering hilang di source project)
            val projectWrapperJar = File(targetRoot, "gradle/wrapper/gradle-wrapper.jar")
            if (!projectWrapperJar.exists() || projectWrapperJar.length() < 10_000) {
                val templateJar = File("${BuilderPaths.DEFAULT_WRAPPER_DIR}/gradle/wrapper/gradle-wrapper.jar")
                if (templateJar.exists()) {
                    File(targetRoot, "gradle/wrapper").mkdirs()
                    templateJar.copyTo(projectWrapperJar, overwrite = true)
                    Logger.logInfo(LOG_TAG, "gradle-wrapper.jar disalin dari template ke project")
                }
            }

            // Detect AGP -> Gradle version
            val rootGradle = File(targetRoot, "build.gradle").takeIf { it.exists() }
                ?: File(targetRoot, "build.gradle.kts").takeIf { it.exists() }
                ?: gradleFiles.firstOrNull()
            val agpVersion = rootGradle?.let { patcher.detectAgpVersion(it) }
            val gradleVersion = patcher.agpToGradle(agpVersion)
            log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.info("AGP=${agpVersion ?: "?"} -> Gradle $gradleVersion"), 24)

            if (wrapperProps.exists()) {
                val propsContent = wrapperProps.readText()
                val newProps = patcher.updateWrapperGradleVersion(propsContent, gradleVersion)
                if (newProps != propsContent) wrapperProps.writeText(newProps)
                // Fix CRLF
                if (wrapperProps.readText().contains("\r")) {
                    wrapperProps.writeText(wrapperProps.readText().replace("\r", ""))
                }
            }

            // v2: cek apakah gradle distribution sudah ada di GRADLE_USER_HOME
            // (dari backup) — kalau ya, beri tahu user bahwa tidak akan diunduh ulang
            if (toolchainManager.isGradleDistributionPresent(gradleVersion)) {
                log(BuildPhase.TOOLCHAIN_SETUP, BuildLog.ok("Gradle $gradleVersion sudah ada di cache — tidak diunduh ulang."), 25)
            }

            // Fix gradlew (CRLF + DEFAULT_JVM_OPTS)
            if (gradlew.exists()) {
                var gContent = gradlew.readText().replace("\r", "")
                if (!gContent.contains("DEFAULT_JVM_OPTS=\"-Xmx64m")) {
                    gContent = gContent.replace(
                        Regex("DEFAULT_JVM_OPTS=.*"),
                        "DEFAULT_JVM_OPTS=\"-Xmx64m -Xms64m\""
                    )
                }
                gradlew.writeText(gContent)
                gradlew.setExecutable(true)
            }

            // ---- 7. PATCH GRADLE FILES (dengan backup) ----
            log(BuildPhase.PATCHING, BuildLog.step(7, 8, "Patching Gradle files (sanitize Java 17, inject SDK/NDK)..."), 30)
            val installedNdkVersion = toolchainManager.installedNdkVersion() ?: BuilderPaths.DEFAULT_NDK_VERSION
            // v3: deteksi juga minSdk & targetSdk agar injection lengkap
            val minSdk = patcher.detectMinSdk(gradleFiles)
            val targetSdk = patcher.detectTargetSdk(gradleFiles)

            var patchedCount = 0
            var skippedCount = 0
            for (file in gradleFiles) {
                if (executor.isCancelled) break
                // v2: gradle.properties hanya untuk deteksi versi — jangan di-patch
                // (sanitize/inject bisa merusak property file yang bukan build script)
                if (file.name == "gradle.properties") continue
                backupManager.backupFileForPatch(file)
                var content = file.readText()
                val original = content
                content = patcher.sanitizeJava17(content)
                content = patcher.injectSdkAndNdk(content, file.name.endsWith(".kts"), compileSdk, installedNdkVersion, minSdk, targetSdk)
                if (content != original) {
                    file.writeText(content)
                    patchedCount++
                } else {
                    skippedCount++
                }
            }
            log(BuildPhase.PATCHING, BuildLog.ok("Patched $patchedCount file Gradle (${skippedCount} tidak berubah)"), 35)

            // ---- 8. local.properties + gradle.properties (append) ----
            val cmakeVersion = "3.22.1"
            File(targetRoot, "local.properties").writeText(
                "sdk.dir=${BuilderPaths.DEFAULT_SDK_DIR}\n" +
                    "cmake.dir=${BuilderPaths.DEFAULT_SDK_DIR}/cmake/$cmakeVersion\n"
            )

            val gradleProps = File(targetRoot, "gradle.properties")
            val extraProps = buildString {
                append("org.gradle.java.installations.auto-detect=false\n")
                append("org.gradle.java.installations.auto-download=false\n")
                append("org.gradle.java.installations.paths=${BuilderPaths.PREFIX_BIN_DIR}/../lib/jvm/java-17-openjdk\n")
                append("org.gradle.native=false\n")
                append("systemProp.org.gradle.native=false\n")
                append("kotlin.compiler.execution.strategy=in-process\n")
                append("kotlin.incremental=true\n")
                append("org.gradle.caching=true\n")
                append("org.gradle.daemon.performance.disable-logging=true\n")
                append("android.aapt2FromMavenOverride=${BuilderPaths.PREFIX_BIN_DIR}/aapt2\n")
                append("org.gradle.workers.max=${profile.maxWorkers}\n")
                append("org.gradle.parallel=false\n")
                append("org.gradle.jvmargs=${profile.gradleJvmArgs}\n")
            }
            val existing = if (gradleProps.exists()) gradleProps.readText() else ""
            if (!existing.contains("org.gradle.java.installations.auto-detect=false")) {
                gradleProps.appendText("\n# TermuxMod Builder overrides\n$extraProps")
            }

            // ---- 9. BUILD ----
            log(BuildPhase.BUILDING, BuildLog.step(8, 8, "Menjalankan ./gradlew ${config.mode.buildType.gradleTask}..."), 40)
            log(BuildPhase.BUILDING, BuildLog.info("Ini bisa memakan waktu 5-30 menit (atau lebih lama di device lama)."), 40)
            val gradleFlags = buildString {
                append("-Dorg.gradle.native=false ")
                append("-Dorg.gradle.java.installations.auto-detect=false ")
                append("-Dorg.gradle.java.installations.auto-download=false ")
                append("-Pandroid.injected.build.abi=arm64-v8a ")
                append("-Pandroid.ninja.jobs=${profile.ninjaJobs} ")
                append("--no-daemon ")
                append("--no-parallel ")
                append("--console=plain ")
                append("--build-cache ")
                if (config.mode.isClean) append("--rerun-tasks ")
            }

            val buildResult = executor.executeShellCommand(
                "./gradlew ${config.mode.buildType.gradleTask} $gradleFlags",
                workingDirectory = targetRoot.absolutePath,
                environment = mapOf(
                    "JAVA_HOME" to "${BuilderPaths.PREFIX_BIN_DIR}/../lib/jvm/java-17-openjdk",
                    "PATH" to "${BuilderPaths.PREFIX_BIN_DIR}:${BuilderPaths.PREFIX_BIN_DIR}/../lib/jvm/java-17-openjdk/bin:" + "\$PATH"
                ),
                lineCallback = object : ProcessExecutor.LineCallback {
                    override fun onLine(line: String) {
                        // v2: bersihkan \r dari progress bar gradle agar log tidak naik-turun
                        val clean = logParser.cleanLine(line)
                        if (clean.isBlank()) return
                        val parsed = logParser.processLine(clean)
                        val percent = logParser.getLastProgressPercent()
                        progressCallback(BuildProgress(
                            BuildPhase.BUILDING,
                            clean.take(200),
                            40 + (percent / 10).coerceIn(0, 50),
                            detail = BuildLog.raw(clean)
                        ))
                    }
                },
                timeoutSeconds = 0 // tanpa batas — build bisa berjam-jam
            )

            if (executor.isCancelled) {
                backupManager.rollbackAll()
                return finish(BuildResult(false, BuildPhase.CANCELLED, "Build dibatalkan",
                    elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000))
            }

            // ---- 10. COPY APK ----
            if (buildResult.isSuccess) {
                log(BuildPhase.COPYING, BuildLog.step(9, 9, "Mencari & menyalin APK..."), 92)
                val apkFile = findApk(targetRoot)
                if (apkFile == null) {
                    val summary = logParser.buildErrorSummary()
                    return finish(BuildResult.failure(BuildPhase.FAILED,
                        "Build sukses tapi APK tidak ditemukan",
                        summary = summary.rawTail.take(500),
                        elapsed = (System.currentTimeMillis() - startTime) / 1000))
                }

                // Salin ke output dir
                val outDir = File(config.outputDir)
                outDir.mkdirs()
                val outApk = File(outDir, config.apkFileName)
                apkFile.copyTo(outApk, overwrite = true)

                // Juga salin kembali ke project asli (app/build/outputs/apk/debug)
                val destProjDir = File(config.projectPath, "app/build/outputs/apk/${config.mode.buildType.name.lowercase()}")
                destProjDir.mkdirs()
                try {
                    apkFile.copyTo(File(destProjDir, config.apkFileName), overwrite = true)
                } catch (e: Exception) {
                    // mungkin read-only, abaikan
                }

                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                log(BuildPhase.SUCCESS, BuildLog.section("BUILD SUKSES"), 100)
                log(BuildPhase.SUCCESS, BuildLog.ok("APK: ${outApk.absolutePath} (${formatSize(outApk.length())})"), 100)
                log(BuildPhase.SUCCESS, BuildLog.info("Waktu total: ${BuildLog.duration(elapsed)}"), 100)
                return finish(BuildResult.success(outApk.absolutePath, elapsed,
                    "APK: ${outApk.absolutePath} (${formatSize(outApk.length())})"))
            }

            // ---- FAILED ----
            val summary = logParser.buildErrorSummary()
            log(BuildPhase.FAILED, BuildLog.section("BUILD GAGAL"), 0)
            log(BuildPhase.FAILED, BuildLog.error("Exit code ${buildResult.exitCode}. Ringkasan error:"), 0)
            summary.lines.take(15).forEach { line ->
                log(BuildPhase.FAILED, BuildLog.error(line.take(300)), 1)
            }
            return finish(BuildResult.failure(BuildPhase.FAILED,
                "Build gagal (exit ${buildResult.exitCode})",
                summary = summary.lines.joinToString("\n").ifBlank { buildResult.stderr.take(500) },
                elapsed = (System.currentTimeMillis() - startTime) / 1000))
        } catch (e: Exception) {
            return finish(BuildResult.failure(BuildPhase.FAILED, "Exception: ${e.message}"))
        }
    }

    /** Cari file APK hasil build (non-unsigned). */
    private fun findApk(workspaceRoot: File): File? {
        var best: File? = null
        workspaceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "apk" && !it.name.contains("-unsigned") }
            .forEach { f ->
                if (best == null || f.lastModified() > best!!.lastModified()) best = f
            }
        return best
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var v = bytes.toDouble()
        var u = 0
        while (v >= 1024 && u < units.size - 1) { v /= 1024; u++ }
        return String.format("%.1f %s", v, units[u])
    }

    /** Finalisasi: set isRunning=false dan kirim progress akhir. */
    private fun finish(result: BuildResult): BuildResult {
        isRunning = false
        progressCallback(BuildProgress(
            if (result.success) BuildPhase.SUCCESS else
                if (result.phase == BuildPhase.CANCELLED) BuildPhase.CANCELLED else BuildPhase.FAILED,
            result.message,
            if (result.success) 100 else 0
        ))
        return result
    }
}