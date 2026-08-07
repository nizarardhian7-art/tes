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
 */
class GradleProjectPatcher(
    private val backupManager: BackupManager,
    private val executor: ProcessExecutor
) {

    companion object {
        private const val LOG_TAG = "GradleProjectPatcher"

        private val AGP_TO_GRADLE_MAP = mapOf(
            "8.8" to "8.10.2",
            "8.7" to "8.9",
            "8.6" to "8.7",
            "8.5" to "8.7",
            "8.4" to "8.6",
            "8.3" to "8.4",
            "8.2" to "8.2",
            "8.1" to "8.0",
            "8.0" to "8.0",
            "7.4" to "7.5",
            "7.3" to "7.4",
            "7.2" to "7.3.3",
            "7.1" to "7.2",
            "7.0" to "7.0.2"
        )
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

    fun findGradleFiles(projectDir: File): List<File> {
        val files = ArrayList<File>()
        projectDir.walkTopDown()
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .forEach { files.add(it) }
        return files
    }

    fun detectCompileSdk(files: List<File>): Int {
        for (f in files) {
            val text = f.readText()
            val m1 = Regex("""compileSdk\s*=\s*(\d+)""").find(text)
            if (m1 != null) return m1.groupValues[1].toInt()
            val m2 = Regex("""compileSdkVersion\s+(\d+)""").find(text)
            if (m2 != null) return m2.groupValues[1].toInt()
        }
        return 34
    }

    fun detectBuildToolsVersion(files: List<File>): String? {
        for (f in files) {
            val text = f.readText()
            val m = Regex("""buildToolsVersion\s*=?\s*["']([^"']+)["']""").find(text)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    fun detectAgpVersion(files: List<File>): String? {
        for (f in files) {
            val text = f.readText()
            val m1 = Regex("""com\.android\.tools\.build:gradle:([\d\.\w\-]+)""").find(text)
            if (m1 != null) return m1.groupValues[1]
            val m2 = Regex("""id\(?["']com\.android\.application["']\)?\s+version\s+["']([^"']+)["']""").find(text)
            if (m2 != null) return m2.groupValues[1]
        }
        return null
    }

    fun agpToGradle(agpVersion: String?): String {
        if (agpVersion.isNullOrBlank()) return "8.13"
        val majorMinor = agpVersion.split('.').take(2).joinToString(".")
        return AGP_TO_GRADLE_MAP[majorMinor] ?: "8.13"
    }

    fun updateWrapperGradleVersion(projectDir: File, gradleVersion: String): Boolean {
        val propsFile = File(projectDir, "gradle/wrapper/gradle-wrapper.properties")
        if (!propsFile.exists()) return false

        backupManager.backupFileForPatch(propsFile)
        var text = propsFile.readText()
        text = text.replace(
            Regex("""distributionUrl=.*gradle-[\d\.\w\-]+-(bin|all)\.zip"""),
            "distributionUrl=https\\://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"
        )
        propsFile.writeText(text)
        return true
    }

    fun sanitizeJava17(projectDir: File): Boolean {
        val files = findGradleFiles(projectDir)
        for (f in files) {
            backupManager.backupFileForPatch(f)
            var text = f.readText()
            text = text.replace(Regex("""JavaVersion\.VERSION_1_8"""), "JavaVersion.VERSION_17")
            text = text.replace(Regex("""JavaVersion\.VERSION_11"""), "JavaVersion.VERSION_17")
            text = text.replace(Regex("""sourceCompatibility\s*=\s*JavaVersion\.VERSION_\w+"""), "sourceCompatibility = JavaVersion.VERSION_17")
            text = text.replace(Regex("""targetCompatibility\s*=\s*JavaVersion\.VERSION_\w+"""), "targetCompatibility = JavaVersion.VERSION_17")
            f.writeText(text)
        }
        return true
    }

    fun injectSdkAndNdk(projectDir: File, sdkDir: String, ndkDir: String): Boolean {
        val localProps = File(projectDir, "local.properties")
        backupManager.backupFileForPatch(localProps)
        val text = "sdk.dir=$sdkDir\nndk.dir=$ndkDir\n"
        localProps.writeText(text)
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
        val gradleFiles = findGradleFiles(projectDir)

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