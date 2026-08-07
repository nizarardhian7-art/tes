package com.termux.builder.patch

import com.termux.builder.backup.BackupManager
import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.HardwareProfile
import com.termux.shared.logger.Logger
import java.io.File

/**
 * Patcher otomatis untuk file Gradle project (settings.gradle, build.gradle,
 * gradle.properties) agar kompatibel dengan lingkungan Android di Termux.
 *
 * Pemetaan presisi dari patch_gradle_files() di build.sh.
 */
class GradlePatcher(
    private val backupManager: BackupManager,
    private val executor: ProcessExecutor
) {

    companion object {
        private const val LOG_TAG = "GradlePatcher"
    }

    /**
     * Jalankan semua patch pada direktori project target.
     */
    fun patchAll(projectDir: File, profile: HardwareProfile, compileSdk: Int = 34, targetSdk: Int = 34): Boolean {
        if (!projectDir.isDirectory) return false

        patchSettingsGradle(projectDir)
        patchBuildGradle(projectDir, compileSdk, targetSdk)
        patchGradleProperties(projectDir, profile)

        return true
    }

    /**
     * Patch settings.gradle / settings.gradle.kts.
     * Sama persis dengan build.sh: memastikan pluginManagement berisi repo google/mavenCentral/gradlePluginPortal.
     */
    fun patchSettingsGradle(projectDir: File): Boolean {
        val settingsFile = File(projectDir, "settings.gradle.kts").takeIf { it.exists() }
            ?: File(projectDir, "settings.gradle").takeIf { it.exists() }
            ?: return true

        backupManager.backupFileForPatch(settingsFile)
        var content = settingsFile.readText()

        // Sama persis dengan build.sh: jika tidak ada google(), tambahkan pluginManagement
        if (!content.contains("google()")) {
            val repoBlock = """
                pluginManagement {
                    repositories {
                        google()
                        mavenCentral()
                        gradlePluginPortal()
                    }
                }
            """.trimIndent()
            content = "$repoBlock\n\n$content"
            settingsFile.writeText(content)
        }

        return true
    }

    /**
     * Patch build.gradle / build.gradle.kts di root & sub-module.
     * Menggunakan Regex presisi (TIDAK memotong 'val', 'var', atau kata kunci Kotlin DSL).
     */
    fun patchBuildGradle(projectDir: File, compileSdk: Int = 34, targetSdk: Int = 34): Boolean {
        val gradleFiles = ArrayList<File>()
        projectDir.walkTopDown()
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .forEach { gradleFiles.add(it) }

        for (f in gradleFiles) {
            backupManager.backupFileForPatch(f)
            var content = f.readText()

            // Mengganti ANGKA SDK saja tanpa merusak 'val', 'var', atau variabel di sekitarnya
            content = content.replace(Regex("""(\bcompileSdk\s*=\s*)\d+""")) { "${it.groupValues[1]}$compileSdk" }
            content = content.replace(Regex("""(\bcompileSdkVersion\s+)\d+""")) { "${it.groupValues[1]}$compileSdk" }
            content = content.replace(Regex("""(\btargetSdk\s*=\s*)\d+""")) { "${it.groupValues[1]}$targetSdk" }
            content = content.replace(Regex("""(\btargetSdkVersion\s+)\d+""")) { "${it.groupValues[1]}$targetSdk" }

            f.writeText(content)
        }

        return true
    }

    /**
     * Patch/buat gradle.properties di dalam project.
     */
    fun patchGradleProperties(projectDir: File, profile: HardwareProfile): Boolean {
        val propsFile = File(projectDir, "gradle.properties")
        backupManager.backupFileForPatch(propsFile)

        var content = if (propsFile.exists()) propsFile.readText() else ""

        val javaHome = "${BuilderPaths.PREFIX_BIN_DIR}/../lib/jvm/java-17-openjdk"
        val requiredProps = mapOf(
            "android.aapt2FromMavenOverride" to "${BuilderPaths.PREFIX_BIN_DIR}/aapt2",
            "android.useAndroidX" to "true",
            "android.enableJetifier" to "true",
            "org.gradle.jvmargs" to profile.gradleJvmArgs,
            "org.gradle.daemon" to "false",
            "org.gradle.parallel" to "false",
            "org.gradle.java.installations.paths" to javaHome,
            "kotlin.compiler.execution.strategy" to "in-process",
            "kotlin.incremental" to "true",
            "android.builder.sdkDownload" to "false",
            "org.gradle.workers.max" to profile.maxWorkers.toString()
        )

        val lines = content.lines().toMutableList()
        for ((key, value) in requiredProps) {
            val idx = lines.indexOfFirst { it.trim().startsWith("$key=") || it.trim().startsWith("$key ") }
            if (idx >= 0) {
                lines[idx] = "$key=$value"
            } else {
                lines.add("$key=$value")
            }
        }

        propsFile.writeText(lines.joinToString("\n"))
        return true
    }
}