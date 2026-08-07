package com.termux.builder.scan

import com.termux.builder.model.ProjectKind
import com.termux.builder.model.ProjectScanResult
import com.termux.builder.model.ScannedProject
import java.io.File

/**
 * Scanner project Android & native.
 *
 * Memindai storage (default /sdcard) untuk menemukan:
 *  - Project Android: file settings.gradle / settings.gradle.kts / build.gradle(.kts) / gradlew
 *  - Project Native:  CMakeLists.txt / Android.mk
 *
 * Logika mengikuti build.sh collect_android_projects() / collect_native_projects(),
 * namun diimplementasikan murni dalam Kotlin (walk file tree, bukan find).
 */
class ProjectScanner(
    private val scanRoot: String = "/sdcard",
    private val maxDepth: Int = 4
) {

    companion object {
        private val ANDROID_MARKERS = listOf(
            "settings.gradle", "settings.gradle.kts", "gradlew",
            "app/build.gradle", "app/build.gradle.kts"
        )
        private val GRADLE_FILES = listOf("settings.gradle", "settings.gradle.kts", "build.gradle", "build.gradle.kts")
        private val NATIVE_FILES = listOf("CMakeLists.txt", "Android.mk")

        private val EXCLUDED_DIRS = setOf("build", ".gradle", ".cxx", ".idea")
        private const val MAX_WALK_DEPTH = 4
    }

    /**
     * Scan storage untuk project Android & native.
     * @param rootPath path yang discan; null -> [scanRoot]
     * @param maxDepth kedalaman maksimum walk; 0 = tidak terbatas
     */
    fun scan(rootPath: String = scanRoot, maxDepth: Int = maxDepth): ProjectScanResult {
        val root = File(rootPath)
        if (!root.exists() || !root.isDirectory) {
            return ProjectScanResult(emptyList(), emptyList())
        }

        val androidProjects = LinkedHashSet<String>()
        val nativeProjects = LinkedHashSet<String>()

        walkFiles(root, depth = 0, maxDepth = if (maxDepth <= 0) Int.MAX_VALUE else maxDepth) { file ->
            val relPath = file.absolutePath
            val parent = file.parentFile ?: return@walkFiles

            // Skip file dalam direktori build/.gradle/.cxx/.idea
            if (isExcluded(relPath)) return@walkFiles

            val name = file.name
            when {
                name in GRADLE_FILES -> {
                    val projectRoot = resolveAndroidProjectRoot(parent, relPath)
                    if (projectRoot != null) androidProjects.add(projectRoot)
                }
                name in NATIVE_FILES -> {
                    val projectRoot = resolveNativeProjectRoot(parent)
                    if (projectRoot != null) nativeProjects.add(projectRoot)
                }
            }
        }

        return ProjectScanResult(
            androidProjects.map { ScannedProject(it, File(it).name, ProjectKind.ANDROID, findBuildFiles(it)) },
            nativeProjects.map { ScannedProject(it, File(it).name, ProjectKind.NATIVE, findBuildFiles(it)) }
        )
    }

    /** Resolve akar project Android dari lokasi file gradle (sama dengan build.sh). */
    private fun resolveAndroidProjectRoot(gradleDir: File, gradlePath: String): String? {
        // Jika di dalam direktori "app" dan parent-nya punya settings.gradle / gradlew -> root = parent
        if (gradleDir.name == "app") {
            val parent = gradleDir.parentFile
            if (parent != null && (hasFile(parent, "settings.gradle") || hasFile(parent, "settings.gradle.kts") || hasFile(parent, "gradlew"))) {
                return parent.absolutePath
            }
        }

        // Jika dir ini punya settings.gradle / gradlew / app/build.gradle -> root = dir
        if (hasFile(gradleDir, "settings.gradle") || hasFile(gradleDir, "settings.gradle.kts") ||
            hasFile(gradleDir, "gradlew") || hasFile(gradleDir, "app/build.gradle") || hasFile(gradleDir, "app/build.gradle.kts")) {
            return gradleDir.absolutePath
        }

        return null
    }

    /** Resolve akar project native: jika di jni/, root = parent. */
    private fun resolveNativeProjectRoot(markerDir: File): String {
        return if (markerDir.name == "jni") {
            markerDir.parentFile?.absolutePath ?: markerDir.absolutePath
        } else {
            markerDir.absolutePath
        }
    }

    private fun findBuildFiles(projectRoot: String): List<String> {
        val root = File(projectRoot)
        val result = ArrayList<String>()
        if (!root.isDirectory) return result

        root.listFiles()?.forEach { child ->
            if (child.isFile && (child.name in GRADLE_FILES || child.name in NATIVE_FILES)) {
                result.add(child.absolutePath)
            }
        }
        // Juga cek app/build.gradle
        val appGradle = File(root, "app/build.gradle")
        if (appGradle.exists()) result.add(appGradle.absolutePath)
        val appGradleKts = File(root, "app/build.gradle.kts")
        if (appGradleKts.exists()) result.add(appGradleKts.absolutePath)

        return result.distinct()
    }

    private fun hasFile(dir: File, relPath: String): Boolean {
        return File(dir, relPath).exists()
    }

    /** Cek apakah path mengandung direktori yang di-exclude. */
    private fun isExcluded(absolutePath: String): Boolean {
        val segments = absolutePath.split('/')
        return segments.any { it in EXCLUDED_DIRS }
    }

    /** Walk file tree dengan kedalaman maksimum. */
    private fun walkFiles(dir: File, depth: Int, maxDepth: Int, consumer: (File) -> Unit) {
        if (depth > maxDepth) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name !in EXCLUDED_DIRS) {
                    walkFiles(child, depth + 1, maxDepth, consumer)
                }
            } else {
                consumer(child)
            }
        }
    }
}
