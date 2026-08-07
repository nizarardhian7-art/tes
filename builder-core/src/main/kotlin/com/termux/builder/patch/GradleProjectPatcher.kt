package com.termux.builder.patch

import java.io.File
import java.util.regex.Pattern
import java.util.regex.Matcher

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
 *
 * v3 FIX (error "Unsupported value: 36" + "project ':app' does not specify compileSdk"):
 *  - AGP 9.x menghapus DSL lama `compileSdkVersion` / `minSdkVersion` / `targetSdkVersion`
 *    dan memperkenalkan tipe `SdkVersion` (format `android-36.2`). Menulis
 *    `compileSdkVersion 36` di AGP 9 memunculkan:
 *        "Unsupported value: 36. Format must be one of: android-31, android-36.2, ..."
 *    lalu "project ':app' does not specify compileSdk in build.gradle".
 *  - Solusi: tulis DSL MODERN `compileSdk 36` / `minSdk 21` / `targetSdk 28`
 *    (tanpa suffix "Version") yang dikenali oleh AGP 8.x MAUPUN AGP 9.x.
 *  - Injection sekarang brace-aware & line-aware:
 *      * `compileSdk` hanya ditulis di level android{} (depth 0).
 *      * Baris `compileSdkVersion`/`compileSdk` di dalam defaultConfig/buildTypes
 *        DIHAPUS (tidak valid di sana — kasus termux-shared/build.gradle:35).
 *      * `minSdk`/`targetSdk` ditulis ulang di posisi asalnya (defaultConfig).
 *      * `ndkVersion` ditulis di level android{}.
 *  - `(project.findProperty(...) ?: 36).toString().toInteger()` (expression)
 *    diganti SELURUH BARISNYA menjadi `compileSdk 36` — bukan hanya keyword-nya.
 */
class GradleProjectPatcher {

    companion object {
        private const val LOG_TAG = "GradleProjectPatcher"

        /**
         * Mapping AGP -> Gradle minimum (tabel resmi Android Developers 2026).
         * Versi lama memakai mapping "ekspektasi" (8.4->8.7 dst) yang menurunkan
         * wrapper ke versi TERLALU LAMA untuk AGP 8.13.2 — itu membuat Gradle
         * mengunduh versi lama & gagal. Sekarang pakai MINIMUM yang dibutuhkan AGP.
         *
         * v3: AGP 8.x -> Gradle 8.x (AGP 8.13 -> Gradle 8.13, BUKAN 9.x).
         *     AGP 9.x -> Gradle 9.x (AGP 9.0 -> Gradle 9.1.0 min).
         *     Gradle 9.2.1 TIDAK kompatibel dengan AGP 8.13.x.
         */
        val AGP_TO_GRADLE: Map<String, String> = com.termux.builder.model.DependencyCatalog.AGP_MIN_GRADLE

        private val GRADLE_VERSION_RE = Pattern.compile("gradle-([0-9.]+)-(all|bin)\\.zip")

        // Regex baris SDK properties (Groovy & KTS)
        private val COMPILE_SDK_LINE = Regex("^(compileSdk|compileSdkVersion)\\s*(=|\\(|\\s)")
        private val MIN_SDK_LINE = Regex("^(minSdk|minSdkVersion)\\s*(=|\\(|\\s)")
        private val TARGET_SDK_LINE = Regex("^(targetSdk|targetSdkVersion)\\s*(=|\\(|\\s)")
        private val NDK_VERSION_LINE = Regex("^ndkVersion\\s*(=|\\s)")
        private val ANDROID_BLOCK_RE = Regex("(?m)^\\s*android\\s*\\{")
    }

    /** Deteksi versi AGP dari root build.gradle. */
    fun detectAgpVersion(gradleFile: File): String? {
        if (!gradleFile.exists()) return null
        val content = gradleFile.readText()
        val m = Pattern.compile("com\\.android\\.tools\\.build:gradle:([0-9.]+)").matcher(content)
        return if (m.find()) m.group(1) else null
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
     *
     * CATATAN v2: versi lama melakukan replaceAll yang "memakan" karakter baru
     * di sekitar pattern dan merusak struktur. Semua replacement sekarang eksplisit &
     * aman untuk Groovy maupun KTS.
     */
    fun sanitizeJava17(content: String): String {
        var src = content

        // JavaVersion.VERSION_1[5-9], VERSION_2[0-9] -> VERSION_17
        // Juga tangani VERSION_1_8 / VERSION_11 / VERSION_1_8-style (underscore!)
        src = Pattern.compile("JavaVersion\\.VERSION_1_[5-9]").matcher(src).replaceAll("JavaVersion.VERSION_17")
        src = Pattern.compile("JavaVersion\\.VERSION_1[5-9]").matcher(src).replaceAll("JavaVersion.VERSION_17")
        src = Pattern.compile("JavaVersion\\.VERSION_2[0-9]").matcher(src).replaceAll("JavaVersion.VERSION_17")
        // JavaVersion.VERSION_11 / VERSION_1_1 style (dua digit dengan underscore)
        src = Pattern.compile("JavaVersion\\.VERSION_(1_[5-9]|1_1|2_[0-9])").matcher(src).replaceAll("JavaVersion.VERSION_17")

        // sourceCompatibility / targetCompatibility = 1x/2x (angka, Groovy: `sourceCompatibility 1.8`)
        src = Pattern.compile("(?m)(sourceCompatibility|targetCompatibility)\\s*=\\s*['\"]?(1[5-9]|2[0-9])(\\.[0-9])?['\"]?")
            .matcher(src).replaceAll("$1 = JavaVersion.VERSION_17")
        src = Pattern.compile("(?m)(sourceCompatibility|targetCompatibility)\\s+['\"]?(1[5-9]|2[0-9])(\\.[0-9])?['\"]?")
            .matcher(src).replaceAll("$1 JavaVersion.VERSION_17")

        // sourceCompatibility = JavaVersion.VERSION_1_8 / VERSION_11 dst -> VERSION_17
        src = Pattern.compile("(?m)(sourceCompatibility|targetCompatibility)\\s*=\\s*JavaVersion\\.VERSION_(1[5-9]|2[0-9])(?![0-9_])")
            .matcher(src).replaceAll("$1 = JavaVersion.VERSION_17")

        // jvmTarget '2x' -> '17'
        src = Pattern.compile("jvmTarget\\s*=\\s*['\"]2[0-9]['\"]").matcher(src).replaceAll("jvmTarget = \"17\"")

        // javaCompiler = javaToolchains { ... } -> comment
        src = disableBraceBlock(src, "javaCompiler", "/* javaCompiler disabled */")

        // javaCompiler = ... (single line) -> comment
        src = Pattern.compile("(?im)^(\\s*javaCompiler\\s*=\\s*[^\\n]+)$").matcher(src)
            .replaceAll("$1 // javaCompiler disabled")

        // jvmToolchain { ... } -> comment (brace-aware)
        src = disableBraceBlock(src, "jvmToolchain", "/* jvmToolchain disabled */")

        // jvmToolchain(..) { ... } -> comment (KTS modern, mis. jvmToolchain(17) { })
        src = disableBraceBlock(src, "jvmToolchain\\s*\\([^)]*\\)", "/* jvmToolchain disabled */")

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
        while (idx < sb.length) {
            val m = Pattern.compile("(?i)\\b$keyword\\s*(?=\\{)").matcher(sb)
            if (!m.find(idx)) break
            val start = m.start()
            val openIdx = sb.indexOf("{", start)
            if (openIdx == -1) { idx = start + 1; continue }

            // Hitung brace balance dari posisi '{'
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
            if (end == -1) { idx = start + 1; continue }

            // Komentari baris-baris yang tercakup (utuh, tanpa memotong di tengah baris)
            val lineStart = sb.lastIndexOf("\n", start - 1) + 1
            var pos = lineStart
            val covered = ArrayList<Int>()
            while (pos < end) {
                covered.add(pos)
                val nl = sb.indexOf("\n", pos)
                if (nl == -1 || nl >= end) break
                pos = nl + 1
            }

            // Prefix komentar pada tiap baris yang belum diawali komentar
            for (li in covered) {
                var p = li
                while (p < sb.length && (sb[p] == ' ' || sb[p] == '\t')) p++
                if (p < sb.length && sb[p] != '/' && sb[p] != '*') {
                    sb.insert(li, "// ")
                }
            }
            idx = end + 1
        }
        return sb.toString()
    }

    /**
     * Inject / update compileSdk, minSdk, targetSdk & ndkVersion pada file Gradle.
     * Mendukung .gradle (Groovy) dan .gradle.kts.
     *
     * v3: menulis DSL modern yang kompatibel AGP 8.x & AGP 9.x:
     *   Groovy: compileSdk 36 / minSdk 21 / targetSdk 28 / ndkVersion = "29.0.14206865"
     *   KTS   : compileSdk = 36 / minSdk = 21 / targetSdk = 28 / ndkVersion = "29.0.14206865"
     * `compileSdkVersion`/`minSdkVersion`/`targetSdkVersion` (DSL lama, dihapus di AGP 9)
     * selalu ditulis ulang ke bentuk modern.
     *
     * Jika tidak ada blok android{} (mis. settings.gradle) file dikembalikan apa adanya.
     */
    fun injectSdkAndNdk(
        content: String,
        isKts: Boolean,
        sdkVersion: Int,
        ndkVersion: String,
        minSdkVersion: Int = 21,
        targetSdkVersion: Int = 28
    ): String {
        return rewriteAndroidBlock(content, isKts, sdkVersion, minSdkVersion, targetSdkVersion, ndkVersion)
            ?: content
    }

    /**
     * Tulis ulang isi blok android{...} baris-per-baris dengan depth tracking.
     * Return null jika tidak ada blok android (pemanggil memutuskan untuk skip file).
     *
     * Aturan:
     *  - depth 0 di dalam blok android = level `android { }` langsung.
     *    compileSdk & ndkVersion HANYA valid di sini -> replace / inject.
     *  - depth > 0 (defaultConfig, buildTypes, ...) = compileSdk TIDAK valid -> hapus baris.
     *  - minSdk / targetSdk valid di defaultConfig (dan di level android) -> replace di mana pun.
     */
    private fun rewriteAndroidBlock(
        content: String,
        isKts: Boolean,
        compileSdk: Int,
        minSdk: Int,
        targetSdk: Int,
        ndkVersion: String
    ): String? {
        val start = ANDROID_BLOCK_RE.find(content) ?: return null
        val openIdx = content.indexOf('{', start.range.last)
        if (openIdx == -1) return null

        // Cari brace penutup blok android (balance-aware)
        var depth = 0
        var end = -1
        var i = openIdx
        while (i < content.length) {
            when (content[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
            i++
        }
        if (end == -1) return null

        val before = content.substring(0, openIdx + 1) // termasuk "android {"
        val after = content.substring(end)             // termasuk "}"
        val blockLines = content.substring(openIdx + 1, end).split("\n")

        val sb = StringBuilder()
        var blockDepth = 0
        var foundCompileSdk = false
        var foundNdk = false

        for (line in blockLines) {
            val trimmed = line.trim()
            val indent = line.takeWhile { it == ' ' || it == '\t' }
            val depthBefore = blockDepth
            blockDepth += trimmed.count { it == '{' } - trimmed.count { it == '}' }

            val isCompileSdk = COMPILE_SDK_LINE.containsMatchIn(trimmed)
            val isMinSdk = MIN_SDK_LINE.containsMatchIn(trimmed)
            val isTargetSdk = TARGET_SDK_LINE.containsMatchIn(trimmed)
            val isNdk = NDK_VERSION_LINE.containsMatchIn(trimmed)

            when {
                // compileSdk HANYA valid di level android (depth 0 di dalam blok)
                isCompileSdk && depthBefore == 0 -> {
                    sb.append(indent)
                    if (isKts) sb.append("compileSdk = $compileSdk\n")
                    else sb.append("compileSdk $compileSdk\n")
                    foundCompileSdk = true
                }
                // compileSdk di dalam defaultConfig/buildTypes/... -> hapus (invalid)
                isCompileSdk -> { /* skip line */ }

                isMinSdk -> {
                    sb.append(indent)
                    if (isKts) sb.append("minSdk = $minSdk\n")
                    else sb.append("minSdk $minSdk\n")
                }
                isTargetSdk -> {
                    sb.append(indent)
                    if (isKts) sb.append("targetSdk = $targetSdk\n")
                    else sb.append("targetSdk $targetSdk\n")
                }
                isNdk && depthBefore == 0 -> {
                    sb.append(indent)
                    sb.append("ndkVersion = \"$ndkVersion\"\n")
                    foundNdk = true
                }
                else -> sb.append(line).append("\n")
            }
        }

        val blockContent = sb.toString()
        val injectLines = StringBuilder()
        if (!foundCompileSdk) {
            if (isKts) injectLines.append("    compileSdk = $compileSdk\n")
            else injectLines.append("    compileSdk $compileSdk\n")
        }
        if (!foundNdk) {
            injectLines.append("    ndkVersion = \"$ndkVersion\"\n")
        }

        return if (injectLines.isEmpty()) {
            before + blockContent + after
        } else {
            before + "\n" + injectLines.toString() + blockContent + after
        }
    }

    /** Update versi Gradle di gradle-wrapper.properties. */
    fun updateWrapperGradleVersion(propertiesContent: String, gradleVersion: String): String {
        // v2: preserve suffix asli (bin atau all) — jangan paksa -all
        val matcher = GRADLE_VERSION_RE.matcher(propertiesContent)
        return if (matcher.find()) {
            val suffix = matcher.group(2) // "bin" atau "all"
            propertiesContent.replace(matcher.group(0), "gradle-$gradleVersion-$suffix.zip")
        } else {
            propertiesContent
        }
    }

    /** Deteksi compileSdk dari file-file Gradle project (default 34). */
    fun detectCompileSdk(projectFiles: List<File>): Int {
        // 1) Cari di file Gradle (build.gradle / build.gradle.kts / app/build.gradle)
        for (f in projectFiles) {
            if (!f.exists()) continue
            val content = f.readText()
            val m = Pattern.compile("(compileSdkVersion|compileSdk)\\s*=?\\s*([0-9]+)").matcher(content)
            if (m.find()) {
                return m.group(2).toIntOrNull() ?: 34
            }
        }
        // 2) Fallback: baca gradle.properties (project.properties.compileSdkVersion=36)
        for (f in projectFiles) {
            if (!f.exists() || f.name != "gradle.properties") continue
            val content = f.readText()
            val m = Pattern.compile("(?m)^\\s*compileSdk(?:Version)?\\s*=\\s*([0-9]+)").matcher(content)
            if (m.find()) {
                return m.group(1).toIntOrNull() ?: 34
            }
        }
        return 34
    }

    /** Deteksi minSdk dari file-file Gradle project (default 21). */
    fun detectMinSdk(projectFiles: List<File>): Int {
        for (f in projectFiles) {
            if (!f.exists()) continue
            val content = f.readText()
            val m = Pattern.compile("(minSdkVersion|minSdk)\\s*=?\\s*([0-9]+)").matcher(content)
            if (m.find()) {
                return m.group(2).toIntOrNull() ?: 21
            }
        }
        for (f in projectFiles) {
            if (!f.exists() || f.name != "gradle.properties") continue
            val content = f.readText()
            val m = Pattern.compile("(?m)^\\s*minSdk(?:Version)?\\s*=\\s*([0-9]+)").matcher(content)
            if (m.find()) {
                return m.group(1).toIntOrNull() ?: 21
            }
        }
        return 21
    }

    /** Deteksi targetSdk dari file-file Gradle project (default 28). */
    fun detectTargetSdk(projectFiles: List<File>): Int {
        for (f in projectFiles) {
            if (!f.exists()) continue
            val content = f.readText()
            val m = Pattern.compile("(targetSdkVersion|targetSdk)\\s*=?\\s*([0-9]+)").matcher(content)
            if (m.find()) {
                return m.group(2).toIntOrNull() ?: 28
            }
        }
        for (f in projectFiles) {
            if (!f.exists() || f.name != "gradle.properties") continue
            val content = f.readText()
            val m = Pattern.compile("(?m)^\\s*targetSdk(?:Version)?\\s*=\\s*([0-9]+)").matcher(content)
            if (m.find()) {
                return m.group(1).toIntOrNull() ?: 28
            }
        }
        return 28
    }

    /** Deteksi buildToolsVersion dari file-file Gradle (untuk setup dummy build-tools). */
    fun detectBuildToolsVersion(projectFiles: List<File>): String? {
        for (f in projectFiles) {
            if (!f.exists()) continue
            val content = f.readText()
            val m = Pattern.compile("buildToolsVersion\\s*=?\\s*['\"][0-9.]+['\"]").matcher(content)
            if (m.find()) {
                val ver = Pattern.compile("[0-9.]+").matcher(m.group())
                if (ver.find()) return ver.group()
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
            .filter {
                it.extension == "gradle" ||
                    it.name.endsWith(".gradle.kts") ||
                    // v2: gradle.properties WAJIB ikut — di sinilah project menyimpan
                    // compileSdkVersion/minSdkVersion/ndkVersion (lihat project ini sendiri)
                    it.name == "gradle.properties"
            }
            .filter { !it.absolutePath.contains("/build/") && !it.absolutePath.contains("/.gradle/") }
            .forEach { result.add(it) }
        return result
    }
}
