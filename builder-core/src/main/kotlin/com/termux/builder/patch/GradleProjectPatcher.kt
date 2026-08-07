package com.termux.builder.patch

import java.io.File
import java.util.regex.Pattern

/**
 * Patcher file Gradle — pemetaan Kotlin dari clean_toolchains_python() dan
 * inject_sdk_and_ndk() pada build.sh.
 *
 * Tidak memakai python: seluruh regex diimplementasikan dengan
 * java.util.regex + state machine kecil untuk blok brace-aware.
 *
 * Keamanan:
 *  - Setiap file di-backup (.bak) sebelum diubah oleh [BackupManager]
 *  - Rollback mengembalikan file dari .bak
 */
class GradleProjectPatcher {

    companion object {
        private const val LOG_TAG = "GradleProjectPatcher"

        /** Mapping AGP -> Gradle (versi modern, lebih lengkap dari build.sh). */
        val AGP_TO_GRADLE: Map<String, String> = mapOf(
            "8.4" to "8.7", "8.5" to "8.7", "8.6" to "8.7",
            "8.7" to "8.9", "8.8" to "8.10.2", "8.9" to "8.11.1",
            "8.10" to "8.11.1", "8.11" to "8.13", "8.12" to "8.13",
            "8.13" to "8.13", "9.0" to "9.0"
        )

        private val GRADLE_VERSION_RE = Pattern.compile("gradle-([0-9.]+)-all\\.zip")
    }

    /** Deteksi versi AGP dari root build.gradle. */
    fun detectAgpVersion(gradleFile: File): String? {
        if (!gradleFile.exists()) return null
        val content = gradleFile.readText()
        val m = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9.]+)").find(content)
        return m?.groupValues?.get(1)
    }

    /** Mapping AGP -> versi Gradle (fallback 8.7). */
    fun agpToGradle(agpVersion: String?): String {
        if (agpVersion == null) return "8.7"
        // Ambil major.minor
        val parts = agpVersion.split(".")
        val key = if (parts.size >= 2) "${parts[0]}.${parts[1]}" else agpVersion
        return AGP_TO_GRADLE[key] ?: "8.7"
    }

    /**
     * Sanitize file Gradle agar kompatibel Java 17:
     *  - JavaVersion.VERSION_1x / VERSION_2x -> VERSION_17
     *  - sourceCompatibility / targetCompatibility angka -> JavaVersion.VERSION_17
     *  - jvmTarget '2x' -> '17'
     *  - Nonaktifkan jvmToolchain / javaCompiler / toolchain block
     */
    fun sanitizeJava17(content: String): String {
        var src = content

        // JavaVersion.VERSION_1[5-9], VERSION_2[0-9] -> VERSION_17
        src = Pattern.compile("JavaVersion\\.VERSION_1[5-9]").matcher(src).replaceAll("JavaVersion.VERSION_17")
        src = Pattern.compile("JavaVersion\\.VERSION_2[0-9]").matcher(src).replaceAll("JavaVersion.VERSION_17")

        // sourceCompatibility / targetCompatibility = 1x/2x (angka) -> JavaVersion.VERSION_17
        src = Pattern.compile("(?m)(sourceCompatibility|targetCompatibility)\\s*=\\s*['\"]?(1[5-9]|2[0-9])['\"]?")
            .matcher(src).replaceAll("$1 = JavaVersion.VERSION_17")
        src = Pattern.compile("(?m)(sourceCompatibility|targetCompatibility)\\s+['\"]?(1[5-9]|2[0-9])['\"]?")
            .matcher(src).replaceAll("$1 JavaVersion.VERSION_17")

        // jvmTarget '2x' -> '17'
        src = Pattern.compile("jvmTarget\\s*=\\s*['\"]2[0-9]['\"]").matcher(src).replaceAll("jvmTarget = \"17\"")

        // javaCompiler = javaToolchains { ... } -> comment
        src = disableBraceBlock(src, "javaCompiler", "/* javaCompiler disabled */")

        // javaCompiler = ... (single line) -> comment
        src = Pattern.compile("(?im)^(\\s*javaCompiler\\s*=\\s*[^\\n]+)$").matcher(src)
            .replaceAll("$1 // javaCompiler disabled")

        // jvmToolchain { ... } -> comment (brace-aware)
        src = disableBraceBlock(src, "jvmToolchain", "/* jvmToolchain disabled */")
        src = Pattern.compile("(?im)^(\\s*jvmToolchain\\s*\\{[^\\n]*)$").matcher(src)
            .replaceAll("$1 // jvmToolchain disabled")

        // toolchain { ... } block -> comment
        src = disableBraceBlock(src, "toolchain", "/* toolchain disabled */")

        return src
    }

    /**
     * Nonaktifkan blok brace-aware: cari kata kunci di awal statement lalu
     * komentari seluruh blok hingga brace penutup seimbang.
     */
    private fun disableBraceBlock(src: String, keyword: String, replacement: String): String {
        val sb = StringBuilder(src)
        var idx = 0
        while (true) {
            val m = Pattern.compile("(?i)\\b$keyword\\s*(?=\\{)").matcher(sb)
            if (!m.find(idx)) break
            val start = m.start()
            // Cari awal baris (agar komentar menimpa dari awal statement)
            val lineStart = sb.lastIndexOf("\n", start - 1) + 1
            // Hitung brace balance dari posisi '{'
            val openIdx = sb.indexOf("{", start)
            if (openIdx == -1) { idx = start + 1; continue }
            var depth = 0
            var end = -1
            var i = openIdx
            while (i < sb.length) {
                val c = sb[i]
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) { end = i + 1; break }
                }
                i++
            }
            if (end != -1) {
                val len = end - lineStart
                sb.replace(lineStart, end, replacement + "\n" + " ".repeat(0))
                // Hapus baris baru ganda yang tertinggal
                idx = lineStart + replacement.length
            } else {
                idx = start + 1
            }
        }
        return sb.toString()
    }

    /**
     * Inject / update compileSdk & ndkVersion pada file Gradle.
     * Mendukung .gradle (Groovy) dan .gradle.kts.
     */
    fun injectSdkAndNdk(content: String, isKts: Boolean, sdkVersion: Int, ndkVersion: String): String {
        var src = content

        // ---- compileSdk ----
        if (isKts) {
            if (Pattern.compile("(?m)compileSdk\\s*=").matcher(src).find()) {
                src = Pattern.compile("compileSdk\\s*=\\s*[0-9]+").matcher(src).replaceAll("compileSdk = $sdkVersion")
            } else if (Pattern.compile("compileSdkVersion\\s*=").matcher(src).find()) {
                src = Pattern.compile("compileSdkVersion\\s*=\\s*[0-9]+").matcher(src).replaceAll("compileSdkVersion = $sdkVersion")
            } else {
                src = insertIntoAndroidBlock(src, "    compileSdk = $sdkVersion")
            }
        } else {
            if (Pattern.compile("(?m)compileSdk\\s+[0-9]+").matcher(src).find()) {
                src = Pattern.compile("compileSdk\\s+[0-9]+").matcher(src).replaceAll("compileSdk $sdkVersion")
            } else if (Pattern.compile("(?m)compileSdkVersion\\s+[0-9]+").matcher(src).find()) {
                src = Pattern.compile("compileSdkVersion\\s+[0-9]+").matcher(src).replaceAll("compileSdkVersion $sdkVersion")
            } else {
                src = insertIntoAndroidBlock(src, "    compileSdk $sdkVersion")
            }
        }

        // ---- ndkVersion ----
        if (isKts) {
            if (Pattern.compile("ndkVersion\\s*=").matcher(src).find()) {
                src = Pattern.compile("ndkVersion\\s*=\\s*['\"][^'\"]+['\"]").matcher(src)
                    .replaceAll("ndkVersion = \"$ndkVersion\"")
            } else {
                src = insertIntoAndroidBlock(src, "    ndkVersion = \"$ndkVersion\"")
            }
        } else {
            if (Pattern.compile("(?m)ndkVersion\\s+['\"]").matcher(src).find()) {
                src = Pattern.compile("ndkVersion\\s+['\"][^'\"]+['\"]").matcher(src)
                    .replaceAll("ndkVersion \"$ndkVersion\"")
            } else {
                src = insertIntoAndroidBlock(src, "    ndkVersion \"$ndkVersion\"")
            }
        }

        return src
    }

    /**
     * Sisipkan baris ke dalam blok android { ... } pertama.
     * Jika blok android tidak ditemukan, tambahkan di akhir file.
     */
    private fun insertIntoAndroidBlock(src: String, line: String): String {
        val m = Pattern.compile("(?m)^(\\s*)(android\\s*\\{)").matcher(src)
        if (m.find()) {
            val insertPos = m.end()
            return src.substring(0, insertPos) + "\n$line" + src.substring(insertPos)
        }
        // Fallback: tambahkan di akhir
        return src.trimEnd() + "\n\n$line\n"
    }

    /** Update versi Gradle di gradle-wrapper.properties. */
    fun updateWrapperGradleVersion(propertiesContent: String, gradleVersion: String): String {
        return GRADLE_VERSION_RE.matcher(propertiesContent)
            .replaceAll("gradle-$gradleVersion-all.zip")
    }

    /** Deteksi compileSdk dari file-file Gradle project (default 34). */
    fun detectCompileSdk(projectFiles: List<File>): Int {
        for (f in projectFiles) {
            if (!f.exists()) continue
            val content = f.readText()
            val m = Pattern.compile("(compileSdk|compileSdkVersion)\\s*=?\\s*[0-9]+").find(content)
            if (m != null) {
                val num = Pattern.compile("[0-9]+").matcher(m.group(0))
                if (num.find()) return num.group(0).toIntOrNull() ?: 34
            }
        }
        return 34
    }

    /** Deteksi buildToolsVersion dari file-file Gradle (untuk setup dummy build-tools). */
    fun detectBuildToolsVersion(projectFiles: List<File>): String? {
        for (f in projectFiles) {
            if (!f.exists()) continue
            val content = f.readText()
            val m = Pattern.compile("buildToolsVersion\\s*=?\\s*['\"][0-9.]+['\"]").find(content)
            if (m != null) {
                val ver = Pattern.compile("[0-9.]+").matcher(m.group(0))
                if (ver.find()) return ver.group(0)
            }
        }
        return null
    }

    /** Kumpulkan semua file gradle dalam project (max depth 3, exclude build/). */
    fun findGradleFiles(projectRoot: File): List<File> {
        val result = ArrayList<File>()
        if (!projectRoot.isDirectory) return result
        projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension == "gradle" || it.name.endsWith(".gradle.kts") }
            .filter { !it.absolutePath.contains("/build/") && !it.absolutePath.contains("/.gradle/") }
            .forEach { result.add(it) }
        return result
    }
}
