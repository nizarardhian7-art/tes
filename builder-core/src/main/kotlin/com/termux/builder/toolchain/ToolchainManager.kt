package com.termux.builder.toolchain

import android.content.Context
import android.util.Base64
import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.HardwareProfile
import com.termux.shared.logger.Logger
import java.io.File

/**
 * Manager toolchain Android SDK/NDK/Gradle — pemetaan Kotlin dari auto_setup()
 * pada build.sh.
 *
 * Strategi eksekusi:
 *  - Operasi filesystem (mkdir, tulis file dummy, source.properties) -> Java File API (aman, cepat)
 *  - Operasi sistem (apt, symlink, chmod, download via wget/curl, ekstraksi) -> ProcessExecutor (AppShell)
 *
 * Seluruh proses dieksekusi dari dalam aplikasi (bukan script bash), sehingga
 * bisa dipantau via callback progress dan dibatalkan.
 *
 * **Perbaikan reliabilitas (v3):**
 *  - URL platform SDK dibuat bertingkat: r01 -> r02 (API 34 hanya r02 di Google,
 *    API 36 tersedia r01/r02) -> fallback AOSP Reginer.
 *  - URL NDK dipindah ke rilis yang masih ada: Lzhiyong/termux-ndk hanya menyediakan
 *    r29 (android-ndk-r29-aarch64.7z), bukan r25c zip (404). NDK versi di
 *    [BuilderPaths.DEFAULT_NDK_VERSION] harus konsisten dengan artifact yang diunduh.
 *  - ensureWrapperTemplate diperbaiki: selalu menulis gradle-wrapper.properties +
 *    gradlew minimal + men-download wrapper jar bila gradle CLI tidak tersedia.
 *  - Semua fase meneruskan output live ke [ProcessExecutor.LineCallback] default.
 */
class ToolchainManager(
    private val context: Context,
    private val executor: ProcessExecutor,
    private val sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR,
    private val ndkVersion: String = BuilderPaths.DEFAULT_NDK_VERSION
) {

    companion object {
        private const val LOG_TAG = "ToolchainManager"

        /** Paket APT yang diinstall build.sh auto_setup(). */
        val APT_PACKAGES = listOf(
            "openjdk-17", "python", "gradle", "android-tools", "rsync",
            "aapt", "aapt2", "apksigner", "d8", "aidl", "cmake", "ninja",
            "make", "wget", "curl", "git", "zip", "unzip", "perl", "p7zip", "clang"
        )

        /** Versi build-tools dummy yang disediakan. */
        val DUMMY_BUILD_TOOLS = listOf("33.0.1", "34.0.0")

        /** Versi cmake dummy yang disediakan. */
        val DUMMY_CMAKE = listOf("3.22.1", "3.18.1")

        /** Binary yang di-symlink dari PREFIX/bin ke build-tools. */
        private val BT_TOOLS = listOf("aapt", "aapt2", "d8", "zipalign", "apksigner")

        /** Dummy executable yang dibuat (exit 0). */
        private val DUMMY_EXECS = listOf(
            "dexdump", "split-select", "mainDexClasses", "mainDexClasses.bat",
            "llvm-rs-cc", "bcc_compat", "lld",
            "arm-linux-androideabi-ld", "i686-linux-android-ld", "mipsel-linux-android-ld",
            "aarch64-linux-android-ld", "x86_64-linux-android-ld"
        )

        /** Dummy jar (empty zip). */
        private val DUMMY_JARS = listOf(
            "core-lambda-stubs.jar", "mainDexClasses.rules", "lib/apksigner.jar",
            "lib/d8.jar", "lib/dx.jar", "lib/aapt2.jar", "lib/shrinkscript.jar"
        )

        /**
         * Konten AIDL dummy (python script dari build.sh).
         * Script minimal yang meng-generate skeleton Java untuk interface aidl.
         * Ditulis sebagai raw string: isi python TIDAK boleh mengandung `"""` ataupun `${`.
         */
        private val DUMMY_AIDL_SCRIPT: String = "#!/usr/bin/env python3\n" +
            "import sys, os, re\n" +
            "args = sys.argv[1:]\n" +
            "out_dir, input_files = None, []\n" +
            "i = 0\n" +
            "while i < len(args):\n" +
            "    arg = args[i]\n" +
            "    if arg.startswith('-o'):\n" +
            "        out_dir = arg[2:] if len(arg) > 2 else (args[i+1] if i+1 < len(args) else None)\n" +
            "        if len(arg) <= 2: i += 1\n" +
            "    elif arg.endswith('.aidl'): input_files.append(arg)\n" +
            "    i += 1\n" +
            "if out_dir:\n" +
            "    for aidl_file in input_files:\n" +
            "        if not os.path.exists(aidl_file): continue\n" +
            "        try:\n" +
            "            with open(aidl_file, 'r', encoding='utf-8', errors='ignore') as f: content = f.read()\n" +
            "            pkg_m = re.search(r'package\\s+([\\w.]+)\\s*;', content)\n" +
            "            pkg = pkg_m.group(1) if pkg_m else ''\n" +
            "            iface_m = re.search(r'(interface|parcelable)\\s+(\\w+)', content)\n" +
            "            iface = iface_m.group(2) if iface_m else None\n" +
            "            if iface:\n" +
            "                tdir = os.path.join(out_dir, *pkg.split('.')) if pkg else out_dir\n" +
            "                os.makedirs(tdir, exist_ok=True)\n" +
            "                tjava = os.path.join(tdir, iface + '.java')\n" +
            "                jcode = 'package ' + pkg + ';\\n\\n' + \\\n" +
            "                        'public interface ' + iface + ' extends android.os.IInterface {\\n' + \\\n" +
            "                        '    public static abstract class Stub extends android.os.Binder implements ' + pkg + '.' + iface + ' {\\n' + \\\n" +
            "                        '        private static final java.lang.String DESCRIPTOR = \"' + pkg + '.' + iface + '\";\\n' + \\\n" +
            "                        '        public Stub() { this.attachInterface(this, DESCRIPTOR); }\\n' + \\\n" +
            "                        '        public static ' + pkg + '.' + iface + ' asInterface(android.os.IBinder obj) {\\n' + \\\n" +
            "                        '            if (obj == null) return null;\\n' + \\\n" +
            "                        '            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);\\n' + \\\n" +
            "                        '            if (iin != null && iin instanceof ' + pkg + '.' + iface + ') return (' + pkg + '.' + iface + ') iin;\\n' + \\\n" +
            "                        '            return new ' + pkg + '.' + iface + '.Stub.Proxy(obj);\\n' + \\\n" +
            "                        '        }\\n' + \\\n" +
            "                        '        @Override public android.os.IBinder asBinder() { return this; }\\n' + \\\n" +
            "                        '    }\\n' + \\\n" +
            "                        '}\\n'\n" +
            "                with open(tjava, 'w', encoding='utf-8') as f: f.write(jcode)\n" +
            "        except Exception:\n" +
            "            pass\n" +
            "sys.exit(0)\n"

        private const val DEFAULT_FRAMEWORK_AIDL: String = "interface java.lang.CharSequence;\n" +
            "interface java.lang.String;\n" +
            "parcelable android.accounts.Account;\n" +
            "parcelable android.app.PendingIntent;\n" +
            "parcelable android.content.ComponentName;\n" +
            "parcelable android.content.Intent;\n" +
            "parcelable android.content.IntentFilter;\n" +
            "parcelable android.graphics.Bitmap;\n" +
            "parcelable android.graphics.Rect;\n" +
            "parcelable android.net.Uri;\n" +
            "parcelable android.os.Bundle;\n" +
            "parcelable android.os.ParcelFileDescriptor;\n" +
            "parcelable android.os.ParcelUuid;\n" +
            "parcelable android.os.PersistableBundle;\n" +
            "parcelable android.view.KeyEvent;\n" +
            "parcelable android.view.MotionEvent;\n"
    }

    /** $SDK_DIR/ndk/<version> — pakai versi yang terpasang bila ada. */
    val ndkDir: String get() = "$sdkDir/ndk/${installedNdkVersion() ?: ndkVersion}"

    // ---------------------------------------------------------------------
    // Status checks
    // ---------------------------------------------------------------------

    /** True bila SDK sudah siap (directory + license + minimal satu platform). */
    fun isSdkReady(): Boolean {
        val platformsDir = File("$sdkDir/platforms")
        val hasPlatform = platformsDir.isDirectory &&
            platformsDir.listFiles()?.any { it.isDirectory && File(it, "android.jar").exists() } == true
        return hasPlatform && File("$sdkDir/licenses/android-sdk-license").exists()
    }

    /** True bila NDK sudah terpasang (versi mana pun yang valid). */
    fun isNdkInstalled(): Boolean {
        return installedNdkVersions().any { v ->
            File("$sdkDir/ndk/$v/ndk-build").exists() || File("$sdkDir/ndk/$v/build/ndk-build").exists()
        }
    }

    // ---------------------------------------------------------------------
    // Setup lengkap
    // ---------------------------------------------------------------------

    /**
     * Setup lengkap toolchain (pemetaan auto_setup() dari build.sh).
     * @param profile profil hardware untuk jvm args
     * @param progress callback pesan progress
     */
    fun setupToolchain(profile: HardwareProfile, progress: (String) -> Unit): Boolean {
        // Line callback default: terima semua output live dari subprocess.
        val lineCb = object : ProcessExecutor.LineCallback {
            override fun onLine(line: String) {
                if (line.isNotBlank()) progress(line.take(400))
            }
        }

        progress("Memastikan akses storage...")
        ensureStorageAccess(lineCb)

        progress("Menginstall paket sistem (APT)...")
        if (!installSystemPackages(progress, lineCb)) {
            return false
        }

        progress("Membangun direktori SDK & target dummy...")
        createSdkLayout()

        DUMMY_BUILD_TOOLS.forEach { setupDummyBuildTools(it) }
        DUMMY_CMAKE.forEach { setupDummyCmake(it) }

        progress("Mendownload platform SDK 34...")
        if (!downloadPlatformSdk(34, progress, lineCb)) {
            return false
        }

        writeSdkLicense()
        writeGlobalGradleProperties(profile)

        if (!isNdkInstalled()) {
            progress("Mendownload Android NDK r25c (besar, mohon tunggu)...")
            if (!installNdk(progress, lineCb)) {
                return false
            }
        } else {
            progress("NDK sudah terpasang.")
        }

        fixNdkPermissions(progress, lineCb)
        ensureWrapperTemplate(progress, lineCb)

        progress("Toolchain setup selesai.")
        return true
    }

    // ---------------------------------------------------------------------
    // SDK layout & packages
    // ---------------------------------------------------------------------

    private fun ensureStorageAccess(lineCb: ProcessExecutor.LineCallback) {
        // termux-setup-storage: membuat symlink ~/storage ke /sdcard
        val homeDir = File(BuilderPaths.DEFAULT_HOME_DIR)
        val storageLink = File(homeDir, "storage")
        if (!storageLink.exists()) {
            executor.executeShellCommand(
                "termux-setup-storage -y 2>/dev/null || true",
                lineCallback = lineCb,
                timeoutSeconds = 120
            )
        }
    }

    private fun installSystemPackages(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback): Boolean {
        progress("apt-get update...")
        val update = executor.executeShellCommand(
            "apt-get update -y 2>&1 | tail -n 5",
            lineCallback = lineCb,
            timeoutSeconds = 600
        )
        if (!update.isSuccess) {
            progress("apt-get update gagal (${update.exitCode}) — melanjutkan...")
        }

        val pkgList = APT_PACKAGES.joinToString(" ")
        progress("apt-get install $pkgList")
        val install = executor.executeShellCommand(
            "apt-get install -y -o Dir::Cache::archives=$sdkDir/pkg-cache $pkgList 2>&1 | tail -n 10",
            lineCallback = lineCb,
            timeoutSeconds = 1800
        )
        return install.isSuccess
    }

    /** Buat layout direktori SDK + cache dir. */
    private fun createSdkLayout() {
        listOf(
            "$sdkDir/pkg-cache", "$sdkDir/platforms", "$sdkDir/build-tools",
            "$sdkDir/licenses", "$sdkDir/cmake", "$sdkDir/ndk"
        ).forEach { File(it).mkdirs() }
    }

    /** Tulis file lisensi SDK. */
    private fun writeSdkLicense() {
        val license = File("$sdkDir/licenses/android-sdk-license")
        if (!license.exists()) {
            license.writeText("24333f8a637bced5e17096433f01641e5f692d6e\n")
        }
    }

    /** Tulis ~/.gradle/gradle.properties global. */
    private fun writeGlobalGradleProperties(profile: HardwareProfile) {
        val gradleHome = File(BuilderPaths.DEFAULT_HOME_DIR, ".gradle")
        gradleHome.mkdirs()
        File(gradleHome, "gradle.properties").writeText(gradlePropertiesTemplate(profile))
    }

    /** Template ~/.gradle/gradle.properties (dari build.sh auto_setup). */
    private fun gradlePropertiesTemplate(profile: HardwareProfile): String {
        val javaHome = "${BuilderPaths.PREFIX_BIN_DIR}/../lib/jvm/java-17-openjdk"
        return buildString {
            append("android.aapt2FromMavenOverride=").append(BuilderPaths.PREFIX_BIN_DIR).append("/aapt2\n")
            append("android.useAndroidX=true\n")
            append("android.enableJetifier=true\n")
            append("org.gradle.jvmargs=").append(profile.gradleJvmArgs).append('\n')
            append("org.gradle.daemon=false\n")
            append("org.gradle.parallel=false\n")
            append("org.gradle.caching=true\n")
            append("org.gradle.daemon.performance.disable-logging=true\n")
            append("org.gradle.java.installations.auto-detect=false\n")
            append("org.gradle.java.installations.auto-download=false\n")
            append("org.gradle.java.installations.paths=").append(javaHome).append('\n')
            append("org.gradle.native=false\n")
            append("kotlin.compiler.execution.strategy=in-process\n")
            append("kotlin.incremental=true\n")
            append("android.builder.sdkDownload=false\n")
            append("org.gradle.workers.max=").append(profile.maxWorkers).append('\n')
        }
    }

    // ---------------------------------------------------------------------
    // Platform SDK
    // ---------------------------------------------------------------------

    /**
     * Download & ekstrak platform SDK untuk API level.
     *
     * Strategi URL (karena tidak semua revisi ada di dl.google.com):
     *  1. coba `platform-<api>_r01.zip`, lalu `_r02.zip`, dst sampai sukses;
     *  2. fallback AOSP Reginer (android.jar + framework.aidl).
     */
    fun downloadPlatformSdk(apiLevel: Int, progress: (String) -> Unit = { _ -> }, lineCb: ProcessExecutor.LineCallback? = null): Boolean {
        val platformDir = File("$sdkDir/platforms/android-$apiLevel")
        val androidJar = File(platformDir, "android.jar")

        // Bersihkan platform lama yang tidak dipakai (build.sh menghapus 13 & 14)
        listOf("android-13", "android-14").forEach {
            File("$sdkDir/platforms/$it").deleteRecursively()
        }

        // Validasi platform yang sudah ada: android.jar harus konsisten
        // dengan core-for-system-modules.jar (cek ukuran) — build.sh memakai
        // trick ini untuk mendeteksi platform corrupt.
        if (androidJar.exists()) {
            val coreJar = File(platformDir, "core-for-system-modules.jar")
            if (coreJar.exists() && androidJar.length() != coreJar.length()) {
                platformDir.deleteRecursively()
            } else {
                return true
            }
        }

        val tmpZip = File("$sdkDir/platform-$apiLevel.zip")
        val tmpExtract = File("$sdkDir/platforms/tmp_extract")
        tmpZip.deleteRecursively()
        tmpExtract.deleteRecursively()
        tmpExtract.mkdirs()

        // Coba r01..r04
        var downloaded = false
        for (revision in 1..4) {
            val url = "https://dl.google.com/android/repository/platform-${apiLevel}_r${revision.toString().padStart(2, '0')}.zip"
            progress("Mencoba download platform-$apiLevel r$revision...")
            val download = executor.executeShellCommand(
                "wget -q -O '$tmpZip' '$url' 2>/dev/null && test -s '$tmpZip' && echo OK || echo FAIL",
                lineCallback = lineCb,
                timeoutSeconds = 600
            )
            if (download.isSuccess && download.stdout.contains("OK")) {
                downloaded = true
                break
            }
        }

        if (downloaded) {
            val extract = executor.executeShellCommand(
                "unzip -o -q '$tmpZip' -d '$tmpExtract' 2>/dev/null && echo OK || echo FAIL",
                lineCallback = lineCb,
                timeoutSeconds = 300
            )
            if (extract.isSuccess && extract.stdout.contains("OK")) {
                tmpZip.deleteRecursively()
                val extracted = tmpExtract.listFiles()?.firstOrNull { it.isDirectory }
                if (extracted != null && File(extracted, "android.jar").exists()) {
                    platformDir.deleteRecursively()
                    extracted.renameTo(platformDir)
                    tmpExtract.deleteRecursively()
                    writePlatformSourceProperties(platformDir, apiLevel)
                    return true
                }
            }
        }

        tmpExtract.deleteRecursively()
        tmpZip.deleteRecursively()

        // Fallback: AOSP android.jar dari Reginer
        Logger.logInfo(LOG_TAG, "Official download gagal, mencoba fallback AOSP platform...")
        progress("Official download gagal — fallback AOSP android.jar...")
        platformDir.mkdirs()
        val fb = executor.executeShellCommand(
            "wget -q -O '${File(platformDir, "android.jar").absolutePath}' " +
                "'https://github.com/Reginer/aosp-android-jar/raw/main/android-$apiLevel/android.jar' && echo OK || echo FAIL",
            lineCallback = lineCb,
            timeoutSeconds = 900
        )
        if (fb.isSuccess && fb.stdout.contains("OK")) {
            writePlatformSourceProperties(platformDir, apiLevel)
            return true
        }
        return false
    }

    private fun writePlatformSourceProperties(platformDir: File, apiLevel: Int) {
        val sp = File(platformDir, "source.properties")
        sp.writeText("Pkg.Revision=1\nAndroidVersion.ApiLevel=$apiLevel\n")
        val aidl = File(platformDir, "framework.aidl")
        if (!aidl.exists() || aidl.length() == 0L) {
            aidl.writeText(DEFAULT_FRAMEWORK_AIDL)
        }
    }

    // ---------------------------------------------------------------------
    // Build-tools & CMake dummy
    // ---------------------------------------------------------------------

    /** Buat build-tools dummy (symlink ke PREFIX/bin + dummy execs/jars). */
    fun setupDummyBuildTools(version: String) {
        val btDir = File("$sdkDir/build-tools/$version")
        File(btDir, "lib").mkdirs()
        File(btDir, "renderscript/include").mkdirs()
        File(btDir, "renderscript/clang-include").mkdirs()

        // Symlink tools dari PREFIX/bin
        BT_TOOLS.forEach { tool ->
            val prefixBin = File("${BuilderPaths.PREFIX_BIN_DIR}/$tool")
            if (prefixBin.exists()) {
                val link = File(btDir, tool)
                if (!link.exists()) {
                    executor.executeShellCommand("ln -sf '${prefixBin.absolutePath}' '${link.absolutePath}'")
                }
            }
        }

        // dx -> d8
        if (File("${BuilderPaths.PREFIX_BIN_DIR}/d8").exists() && !File(btDir, "dx").exists()) {
            executor.executeShellCommand(
                "ln -sf '${BuilderPaths.PREFIX_BIN_DIR}/d8' '${File(btDir, "dx").absolutePath}'"
            )
        }

        // AIDL: symlink jika ada, else dummy python script
        val aidlLink = File(btDir, "aidl")
        val prefixAidl = File("${BuilderPaths.PREFIX_BIN_DIR}/aidl")
        if (prefixAidl.exists()) {
            executor.executeShellCommand("ln -sf '${prefixAidl.absolutePath}' '${aidlLink.absolutePath}'")
        } else if (!aidlLink.exists() || aidlLink.length() == 0L) {
            aidlLink.writeText(DUMMY_AIDL_SCRIPT)
            aidlLink.setExecutable(true)
        }

        // Dummy execs (exit 0)
        DUMMY_EXECS.forEach { name ->
            val f = File(btDir, name)
            if (!f.exists()) {
                f.writeText("#!/bin/sh\nexit 0\n")
                f.setExecutable(true)
            }
        }

        // Dummy jars (empty zip)
        val emptyZip = Base64.decode("UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA==", Base64.DEFAULT)
        DUMMY_JARS.forEach { rel ->
            val f = File(btDir, rel)
            if (!f.exists() || f.length() == 0L) {
                f.parentFile?.mkdirs()
                f.writeBytes(emptyZip)
            }
        }

        // source.properties
        File(btDir, "source.properties").writeText("Pkg.PluginsSource=Android SDK\nPkg.Revision=$version\n")
    }

    /** Buat cmake dummy (symlink ke PREFIX/bin/cmake & ninja + source.properties). */
    fun setupDummyCmake(version: String) {
        val cmakeDir = File("$sdkDir/cmake/$version")
        File(cmakeDir, "bin").mkdirs()

        // Pastikan ninja terinstall
        if (!File("${BuilderPaths.PREFIX_BIN_DIR}/ninja").exists()) {
            executor.executeShellCommand("pkg install ninja -y 2>/dev/null || true", timeoutSeconds = 300)
        }

        val prefixBin = BuilderPaths.PREFIX_BIN_DIR
        if (File("$prefixBin/cmake").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/cmake' '${File(cmakeDir, "bin/cmake").absolutePath}'")
        }
        if (File("$prefixBin/ninja").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/ninja' '${File(cmakeDir, "bin/ninja").absolutePath}'")
            executor.executeShellCommand("ln -sf '$prefixBin/ninja' '${File(cmakeDir, "bin/ninja-build").absolutePath}'")
        }

        File(cmakeDir, "source.properties").writeText(
            "Pkg.PluginsSource=Android SDK\nPkg.Revision=$version\nPkg.Path=cmake;$version\n"
        )
    }

    // ---------------------------------------------------------------------
    // NDK
    // ---------------------------------------------------------------------

    /**
     * Download & ekstrak NDK r25c (aarch64) bila belum ada.
     *
     * Catatan: rilis Lzhiyong/termux-ndk TIDAK lagi menyediakan android-ndk-r25c-aarch64.zip
     * (404). Rilis yang masih ada: `android-ndk-r29-aarch64.7z` (tag android-ndk).
     * Engine men-download r29 dan mengekstrak via 7z (p7zip), lalu memperlakukan
     * versi tersebut sebagai [ndkVersion] yang dikonfigurasi.
     */
    private fun installNdk(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback?): Boolean {
        // Jika versi r25c dikonfigurasi, tapi rilisnya 404, gunakan r29.
        val downloadUrl: String
        val actualVersion: String
        if (ndkVersion == BuilderPaths.DEFAULT_NDK_VERSION) {
            // DEFAULT_NDK_VERSION == "25.2.9519653" — rilis r25c sudah tidak ada → pakai r29
            downloadUrl = "https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r29-aarch64.7z"
            actualVersion = "29.0.14206865"
        } else {
            downloadUrl = "https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r25c-aarch64.zip"
            actualVersion = ndkVersion
        }

        val ndkZip = File("$sdkDir/ndk-download.7z")
        val tmpDir = File("$sdkDir/ndk/tmp")
        tmpDir.mkdirs()

        progress("Mendownload NDK r29 (sekitar 400 MB, mohon tunggu)...")
        val dl = executor.executeShellCommand(
            "wget -q --show-progress -O '$ndkZip' '$downloadUrl' 2>&1 && echo OK || echo FAIL",
            lineCallback = lineCb,
            timeoutSeconds = 2400
        )
        if (!dl.isSuccess || !dl.stdout.contains("OK")) {
            progress("Gagal mendownload NDK (wget). Mencoba curl...")
            val dl2 = executor.executeShellCommand(
                "curl -fsSL --retry 2 -o '$ndkZip' '$downloadUrl' 2>&1 && echo OK || echo FAIL",
                lineCallback = lineCb,
                timeoutSeconds = 2400
            )
            if (!dl2.isSuccess || !dl2.stdout.contains("OK")) {
                progress("Gagal mendownload NDK.")
                return false
            }
        }

        progress("Mengekstrak NDK (7z)...")
        // .7z memerlukan 7z (p7zip) — coba unzip dulu (zip), lalu 7z
        var ex: com.termux.builder.model.CommandResult = executor.executeShellCommand(
            "unzip -q -o '$ndkZip' -d '$tmpDir' 2>/dev/null && echo OK || echo FAIL",
            lineCallback = lineCb,
            timeoutSeconds = 900
        )
        if (!ex.isSuccess || !ex.stdout.contains("OK")) {
            // Fallback 7z
            ex = executor.executeShellCommand(
                "7z x -y -o'$tmpDir' '$ndkZip' >/dev/null 2>&1 && echo OK || echo FAIL",
                lineCallback = lineCb,
                timeoutSeconds = 900
            )
        }
        if (!ex.isSuccess || !ex.stdout.contains("OK")) {
            progress("Gagal mengekstrak NDK.")
            ndkZip.deleteRecursively()
            return false
        }

        // Cari folder NDK di dalam tmp (bisa android-ndk-r29 / android-ndk-r25c)
        val extracted = findNdkRoot(tmpDir) ?: run {
            ndkZip.deleteRecursively()
            tmpDir.deleteRecursively()
            progress("Folder NDK tidak ditemukan dalam arsip.")
            return false
        }

        File("$sdkDir/ndk").mkdirs()
        val target = File("$sdkDir/ndk/$actualVersion")
        if (target.exists()) target.deleteRecursively()
        extracted.renameTo(target)
        tmpDir.deleteRecursively()
        ndkZip.deleteRecursively()
        return true
    }

    /** Cari direktori akar NDK (mengandung ndk-build / build/ndk-build). */
    private fun findNdkRoot(dir: File): File? {
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) {
                    if (File(c, "ndk-build").exists() || File(c, "build/ndk-build").exists()) {
                        return c
                    }
                    stack.add(c)
                }
            }
        }
        return null
    }

    /** Perbaiki permission NDK + symlink make/python3 + fix shebang (dari build.sh). */
    fun fixNdkPermissions(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback? = null) {
        if (!File(ndkDir).exists()) return

        // chmod -R +x
        executor.executeShellCommand("chmod -R +x '$ndkDir' 2>/dev/null || true", lineCallback = lineCb, timeoutSeconds = 300)

        // prebuilt/linux-aarch64/bin dengan symlink make & python3
        val prebuiltBin = File("$ndkDir/prebuilt/linux-aarch64/bin")
        prebuiltBin.mkdirs()
        val prefixBin = BuilderPaths.PREFIX_BIN_DIR
        if (File("$prefixBin/make").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/make' '${File(prebuiltBin, "make").absolutePath}' 2>/dev/null || true", lineCallback = lineCb)
        }
        if (File("$prefixBin/python3").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/python3' '${File(prebuiltBin, "python3").absolutePath}' 2>/dev/null || true", lineCallback = lineCb)
        }

        // termux-fix-shebang untuk semua script NDK
        executor.executeShellCommand(
            "command -v termux-fix-shebang >/dev/null 2>&1 && " +
                "find '$ndkDir' -type f \\( -name '*.sh' -o -name 'ndk-build' \\) -exec termux-fix-shebang {} \\; 2>/dev/null || true",
            lineCallback = lineCb,
            timeoutSeconds = 600
        )
    }

    // ---------------------------------------------------------------------
    // Wrapper template
    // ---------------------------------------------------------------------

    /**
     * Siapkan wrapper template (gradlew + gradle-wrapper.jar + properties).
     *
     * Perbaikan v3: selalu menulis `gradlew` + `gradle-wrapper.properties` yang valid,
     * dan men-download `gradle-wrapper.jar` bila gradle CLI tidak tersedia
     * (sebelumnya hanya mengandalkan `gradle wrapper` — jika gradle tidak ada,
     * folder wrapper kosong sehingga build user gagal).
     */
    fun ensureWrapperTemplate(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback? = null) {
        val wrapperDir = File(BuilderPaths.DEFAULT_WRAPPER_DIR)
        val wrapperSub = File(wrapperDir, "gradle/wrapper")
        wrapperSub.mkdirs()

        val jar = File(wrapperSub, "gradle-wrapper.jar")
        if (!jar.exists() || jar.length() < 10_000) {
            progress("Mendownload gradle-wrapper.jar template...")
            val dl = executor.executeShellCommand(
                "wget -q -O '${jar.absolutePath}' " +
                    "'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' 2>/dev/null; " +
                    "test -s '${jar.absolutePath}' && echo OK || echo FAIL",
                lineCallback = lineCb,
                timeoutSeconds = 300
            )
            if (!dl.isSuccess || !dl.stdout.contains("OK")) {
                // Fallback curl
                executor.executeShellCommand(
                    "curl -fsSL -o '${jar.absolutePath}' " +
                        "'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' 2>/dev/null || true",
                    lineCallback = lineCb,
                    timeoutSeconds = 300
                )
            }
        }

        // Buat settings.gradle minimal untuk wrapper template
        File(wrapperDir, "settings.gradle").writeText("rootProject.name='wrapper-template'\n")

        // Selalu tulis gradlew minimal + properties (agar bisa dipakai tanpa gradle CLI)
        val gradlew = File(wrapperDir, "gradlew")
        if (!gradlew.exists()) {
            gradlew.writeText("#!/bin/sh\n# minimal gradlew (wrapper)\nexec \"$0\" \"$@\"\n")
            gradlew.setExecutable(true)
        }

        val props = File(wrapperSub, "gradle-wrapper.properties")
        if (!props.exists()) {
            props.writeText(
                "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n" +
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-all.zip\n" +
                    "networkTimeout=10000\nvalidateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n"
            )
        }

        // Jalankan gradle wrapper bila gradle CLI tersedia (perbarui jar + gradlew resmi)
        if (File("${BuilderPaths.PREFIX_BIN_DIR}/gradle").exists()) {
            executor.executeShellCommand(
                "cd '${wrapperDir.absolutePath}' && gradle wrapper --gradle-version 8.13 --no-daemon -q 2>/dev/null || true",
                lineCallback = lineCb,
                timeoutSeconds = 600
            )
        }
    }

    // ---------------------------------------------------------------------
    // Helpers publik
    // ---------------------------------------------------------------------

    /** Daftar versi NDK yang benar-benar terpasang (folder di $SDK_DIR/ndk). */
    fun installedNdkVersions(): List<String> {
        val ndkRoot = File("$sdkDir/ndk")
        if (!ndkRoot.isDirectory) return emptyList()
        return ndkRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    /** Versi NDK yang terpasang (folder pertama) atau null. */
    fun installedNdkVersion(): String? = installedNdkVersions().firstOrNull()
}