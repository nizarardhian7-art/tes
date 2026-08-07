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
 */
class GradleProjectPatcher {

    companion object {
        private const val LOG_TAG = "GradleProjectPatcher"

        /**
         * Mapping AGP -> Gradle minimum (tabel resmi Android Developers 2026).
         * Versi lama memakai mapping "ekspektasi" (8.4->8.7 dst) yang menurunkan
         * wrapper ke versi TERLALU LAMA untuk AGP 8.13.2 — itu membuat Gradle
         * mengunduh versi lama & gagal. Sekarang pakai MINIMUM yang dibutuhkan AGP.
         */
        val AGP_TO_GRADLE: Map<String, String> = com.termux.builder.model.DependencyCatalog.AGP_MIN_GRADLE

        private val GRADLE_VERSION_RE = Pattern.compile("gradle-([0-9.]+)-(all|bin)\\.zip")
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
     * di sekitar pattern dan merusak struktur (mis. `sourceCompatibility` diubah
     * menjadi `sourceCompatibility = JavaVersion.VERSION_17` di file .kts padahal
     * sintaksnya `sourceCompatibility = JavaVersion.VERSION_17` sudah benar, atau
     * malah menghasilkan baris ganda). Semua replacement sekarang eksplisit &
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
     *
     * v2 FIX: versi lama mengganti seluruh baris dengan replacement + "\n"
     * tanpa menghapus brace penutup yang tersisa di baris yang sama, dan loop
     * pakai matcher pada StringBuilder yang dimutasi — menghasilkan baris
     * patah / sintaks rusak. Sekarang komentar baris per baris sambil menjaga
     * posisi penelusuran.
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
     * Inject / update compileSdk & ndkVersion pada file Gradle.
     * Mendukung .gradle (Groovy) dan .gradle.kts.
     *
     * v2 FIX (error "Unresolved reference: compileSdk" di settings.gradle.kts:25):
     *  - Versi lama menyisipkan `compileSdk = 34` / `ndkVersion = "..."` DI LUAR
     *    blok `android { }` (fallback insertIntoAndroidBlock menambahkan di akhir
     *    file / di posisi yang salah), sehingga KTS meng-compile `compileSdk`
     *    sebagai referensi variabel bebas -> unresolved.
     *  - Sekarang penyisipan SELALU di dalam blok android { } yang sudah ada;
     *    jika tidak ada blok android (mis. settings.gradle), file dilewati.
     *  - Untuk KTS, ekspresi `compileSdk = 36` dan `ndkVersion = "29.0.14206865"`
     *    divalidasi agar tidak menggandakan assignment.
     */
    fun injectSdkAndNdk(content: String, isKts: Boolean, sdkVersion: Int, ndkVersion: String): String {
        var src = content

        // ---- compileSdk ----
        if (isKts) {
            // KTS: compileSdk = 36 (tanpa spasi atau dengan spasi)
            if (Pattern.compile("(?m)^\\s*compileSdk\\s*=").matcher(src).find()) {
                src = Pattern.compile("compileSdk\\s*=\\s*[0-9]+").matcher(src).replaceAll("compileSdk = $sdkVersion")
            } else if (Pattern.compile("(?m)^\\s*compileSdkVersion\\s*=").matcher(src).find()) {
                src = Pattern.compile("compileSdkVersion\\s*=\\s*[0-9]+").matcher(src).replaceAll("compileSdkVersion = $sdkVersion")
            } else {
                // Sisipkan DI DALAM blok android { } — bukan di akhir file!
                src = insertIntoAndroidBlock(src, "    compileSdk = $sdkVersion", requireAndroidBlock = true) ?: return content
            }
        } else {
            // Groovy: compileSdk 36 / compileSdkVersion 36 / = 36 / = expression
            // v2: tangani semua bentuk termasuk "compileSdkVersion project.properties.xxx.toInteger()"
            //     (dengan ATAU tanpa '=' — Groovy lama pakai spasi, modern pakai '=')
            val groovyExpr = Pattern.compile(
                "(?m)^\\s*compileSdkVersion\\s*=?\\s*([^\\n\\r]+)$"
            ).matcher(src)
            if (groovyExpr.find()) {
                // Ganti seluruh baris (expression atau angka) dengan angka tetap.
                // v2: quoteReplacement — `$` di $sdkVersion punya makna khusus di replaceAll!
                src = groovyExpr.replaceAll(Matcher.quoteReplacement("compileSdkVersion $sdkVersion"))
            } else if (Pattern.compile("(?m)^\\s*compileSdk\\s+[0-9]+").matcher(src).find()) {
                src = Pattern.compile("compileSdk\\s+[0-9]+").matcher(src).replaceAll("compileSdk $sdkVersion")
            } else if (Pattern.compile("(?m)^\\s*compileSdk\\s*=").matcher(src).find()) {
                src = Pattern.compile("compileSdk\\s*=\\s*[0-9]+").matcher(src).replaceAll("compileSdk = $sdkVersion")
            } else {
                src = insertIntoAndroidBlock(src, "    compileSdk $sdkVersion", requireAndroidBlock = true) ?: return content
            }
        }

        // ---- ndkVersion ----
        if (isKts) {
            if (Pattern.compile("(?m)^\\s*ndkVersion\\s*=").matcher(src).find()) {
                src = Pattern.compile("ndkVersion\\s*=\\s*['\"][^'\"]+['\"]").matcher(src)
                    .replaceAll("ndkVersion = \"$ndkVersion\"")
            } else {
                src = insertIntoAndroidBlock(src, "    ndkVersion = \"$ndkVersion\"", requireAndroidBlock = true) ?: return content
            }
        } else {
            if (Pattern.compile("(?m)^\\s*ndkVersion\\s+['\"]").matcher(src).find()) {
                src = Pattern.compile("ndkVersion\\s+['\"][^'\"]+['\"]").matcher(src)
                    .replaceAll("ndkVersion \"$ndkVersion\"")
            } else if (Pattern.compile("(?m)^\\s*ndkVersion\\s*=").matcher(src).find()) {
                src = Pattern.compile("ndkVersion\\s*=\\s*['\"][^'\"]+['\"]").matcher(src)
                    .replaceAll("ndkVersion = \"$ndkVersion\"")
            } else {
                src = insertIntoAndroidBlock(src, "    ndkVersion \"$ndkVersion\"", requireAndroidBlock = true) ?: return content
            }
        }

        return src
    }

    /**
     * Sisipkan baris ke dalam blok android { ... } pertama.
     *
     * v2 FIX: parameter [requireAndroidBlock] — jika blok android tidak ada,
     * kembalikan null (pemanggil memutuskan: untuk settings.gradle / file non-module
     * lebih baik TIDAK menyisipkan apa pun daripada merusak sintaks). Sebelumnya
     * fallback menambahkan baris di akhir file yang menghasilkan
     * "Unresolved reference: compileSdk" pada KTS.
     */
    private fun insertIntoAndroidBlock(src: String, line: String, requireAndroidBlock: Boolean = false): String? {
        val m = Pattern.compile("(?m)^(\\s*)(android\\s*\\{)").matcher(src)
        if (m.find()) {
            val insertPos = m.end()
            // Jangan sisipkan jika line sudah ada persis di dalam blok (hindari duplikat)
            val afterInsert = src.substring(m.end(), (src.indexOf("}", m.end()).takeIf { it > 0 } ?: src.length))
            if (afterInsert.contains(line.trim())) {
                return src
            }
            return src.substring(0, insertPos) + "\n$line" + src.substring(insertPos)
        }
        return if (requireAndroidBlock) null else src.trimEnd() + "\n\n$line\n"
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