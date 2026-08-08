package com.termux.builder.toolchain

import android.content.Context
import android.util.Base64
import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.log.BuildLog
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.CommandResult
import com.termux.builder.model.DependencyCatalog
import com.termux.builder.model.HardwareProfile
import com.termux.builder.model.SetupPhase
import com.termux.builder.model.SetupState
import com.termux.shared.logger.Logger
import java.io.File

/**
 * Manager toolchain Android SDK/NDK/Gradle — pemetaan Kotlin dari auto_setup()
 * pada build.sh.
 *
 * v2 — perombakan besar:
 *  - SKIP-DOWNLOAD: semua komponen (platform SDK, NDK, build-tools, cmake,
 *    gradle wrapper) DIVALIDASI dulu sebelum download. Jika sudah ada & valid,
 *    langsung dipakai — TIDAK didownload ulang. Inilah perbaikan inti untuk
 *    keluhan "import zip backup tapi tetap download ulang NDK/platform".
 *  - URL platform SDK yang BENAR: versi lama memakai platform-34_r01..r04.zip
 *    yang TIDAK ADA (404). Sekarang nama file di-resolve dari manifest
 *    repository2-1.xml Google + tabel fallback yang sudah diverifikasi.
 *  - Versi konsisten: NDK default 29.0.14206865 (r29 aarch64 Termux) —
 *    sebelumnya tidak konsisten dengan DEFAULT_NDK_VERSION=25.2.9519653.
 *  - Semua progress lewat [BuildLog] agar log terstruktur (section/step/ok/warn).
 */
class ToolchainManager(
    private val context: Context,
    private val executor: ProcessExecutor,
    private val sdkDir: String = BuilderPaths.DEFAULT_SDK_DIR,
    private val ndkVersion: String = BuilderPaths.DEFAULT_NDK_VERSION
) {

    companion object {
        private const val LOG_TAG = "ToolchainManager"

        /** Paket APT yang dibutuhkan toolchain (v4: idempotent — hanya install yang kurang). */
        val APT_PACKAGES: List<String> get() = BuilderPaths.REQUIRED_APT_PACKAGES

        /** Versi build-tools yang disediakan (dummy + real bila ada). */
        val DUMMY_BUILD_TOOLS = listOf("33.0.1", "34.0.0")

        /** Versi cmake yang disediakan. */
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

    val ndkDir: String get() = "$sdkDir/ndk/${installedNdkVersion() ?: ndkVersion}"

    var lastError: String? = null
        private set

    private fun fail(message: String): Boolean {
        lastError = message
        Logger.logError(LOG_TAG, message)
        return false
    }

    private fun tailOf(result: CommandResult, maxLen: Int = 300): String {
        val text = result.stderr.ifBlank { result.stdout }.trim()
        val lastLines = text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")
        return lastLines.take(maxLen).ifBlank { "(exit ${result.exitCode}, tidak ada output)" }
    }

    // ============================================================
    // VALIDASI KOMPONEN (skip-download)
    // ============================================================

    /**
     * SDK siap bila ada platform android dengan android.jar valid + license.
     */
    fun isSdkReady(): Boolean {
        val platformsDir = File("$sdkDir/platforms")
        val hasPlatform = platformsDir.isDirectory &&
            platformsDir.listFiles()?.any { it.isDirectory && File(it, "android.jar").exists() && File(it, "android.jar").length() > 1_000_000 } == true
        return hasPlatform && File("$sdkDir/licenses/android-sdk-license").exists()
    }

    /**
     * NDK terpasang bila ada direktori versi dengan ndk-build & toolchain valid.
     * v2: tidak cukup cek ndk-build — toolchain prebuilt juga harus ada, agar
     * AGP tidak mencoba download ulang di tengah build.
     */
    fun isNdkInstalled(): Boolean {
        return installedNdkVersions().any { v ->
            val ndkDirV = File("$sdkDir/ndk/$v")
            val ndkBuild = File(ndkDirV, "ndk-build").exists() || File(ndkDirV, "build/ndk-build").exists()
            val prebuilt = File(ndkDirV, "prebuilt").isDirectory
            // Minimal ndk-build + source.properties (tanda NDK utuh)
            ndkBuild && (prebuilt || File(ndkDirV, "source.properties").exists())
        }
    }

    /** Versi NDK yang benar-benar terpasang, atau null. */
    fun installedNdkVersions(): List<String> {
        val ndkRoot = File("$sdkDir/ndk")
        if (!ndkRoot.isDirectory) return emptyList()
        return ndkRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun installedNdkVersion(): String? = installedNdkVersions().firstOrNull()

    /**
     * Cek apakah platform tertentu sudah terpasang & valid.
     */
    fun isPlatformInstalled(apiLevel: Int): Boolean {
        val platformDir = File("$sdkDir/platforms/android-$apiLevel")
        if (!platformDir.isDirectory) return false
        val androidJar = File(platformDir, "android.jar")
        return androidJar.exists() && androidJar.length() > 1_000_000
    }

    /**
     * Cek apakah gradle distribution (zip) sudah ada di GRADLE_USER_HOME/wrapper/dists.
     * v2: ini kunci "tidak download ulang gradle" — Gradle sendiri meletakkan
     * zip di $HOME/.gradle/wrapper/dists/<name>/<hash>/<name>.zip. Jika zip
     * sudah ada, wrapper memakainya tanpa unduh.
     */
    fun isGradleDistributionPresent(gradleVersion: String): Boolean {
        val distsRoot = File(BuilderPaths.GRADLE_WRAPPER_DISTS)
        if (!distsRoot.isDirectory) return false
        return distsRoot.listFiles()?.any { dir ->
            dir.isDirectory && dir.name.startsWith("gradle-$gradleVersion-") &&
                dir.walkTopDown().any { it.isFile && it.name.endsWith(".zip") && it.length() > 1_000_000 }
        } == true
    }

    // ============================================================
    // SETUP UTAMA
    // ============================================================

    /**
     * Setup toolchain IDEMPOTENT + RESUME (v4).
     *
     * Perbaikan masalah "Cancel lalu Build lagi -> apt-get install gagal exit 100":
     *  - Sebelum `apt-get install`, cek paket yang SUDAH terpasang via `dpkg -l`
     *    dan hanya install yang KURANG. Jika semua paket sudah ada, apt-get
     *    TIDAK dijalankan ulang sama sekali.
     *  - Progress setup ditulis ke marker file [BuilderPaths.SETUP_STATE_FILE]
     *    ([SetupState]) setiap kali satu fase tuntas. Saat build di-cancel di
     *    tengah, build berikutnya MELANJUTKAN dari fase terakhir yang terekam —
     *    tidak mulai dari nol.
     */
    fun setupToolchain(profile: HardwareProfile, progress: (String) -> Unit): Boolean {
        lastError = null
        val lineCb = object : ProcessExecutor.LineCallback {
            override fun onLine(line: String) {
                if (line.isNotBlank()) progress(BuildLog.raw(line.take(400)))
            }
        }

        val bashExists = File("${BuilderPaths.PREFIX_BIN_DIR}/bash").exists()
        val shExists = File("${BuilderPaths.PREFIX_BIN_DIR}/sh").exists()
        if (!bashExists && !shExists) {
            return fail(
                "Bootstrap Termux belum terpasang dengan benar: " +
                    "${BuilderPaths.PREFIX_BIN_DIR}/bash tidak ditemukan. " +
                    "Buka app ini sekali dan biarkan proses install bootstrap awal " +
                    "(TermuxInstaller) selesai sebelum menjalankan setup toolchain."
            )
        }

        progress(BuildLog.section("SETUP TOOLCHAIN"))
        progress(BuildLog.info("SDK dir : $sdkDir"))
        progress(BuildLog.info("NDK versi: $ndkVersion"))

        // v4: load state setup sebelumnya (resume setelah cancel)
        var state = SetupState.load()
        if (state.phase != SetupPhase.INIT && state.phase != SetupPhase.COMPLETE) {
            progress(BuildLog.info("Melanjutkan setup dari fase '${state.phase}' (build sebelumnya di-cancel/terputus)."))
        }

        // ---- 0. Storage access (idempotent, sangat cepat) ----
        progress(BuildLog.step(1, 7, "Memastikan akses storage..."))
        ensureStorageAccess(lineCb)

        // ---- 1. Paket APT (v4: hanya install yang kurang) ----
        if (state.aptReady && allAptPackagesInstalled()) {
            progress(BuildLog.step(2, 7, "Menginstall paket sistem (APT)..."))
            progress(BuildLog.ok("Semua ${APT_PACKAGES.size} paket APT sudah terpasang — skip apt-get install."))
        } else {
            progress(BuildLog.step(2, 7, "Menginstall paket sistem (APT)..."))
            if (!installSystemPackages(progress, lineCb)) {
                return false
            }
            state = state.copy(aptReady = true, phase = SetupPhase.APT).also { it.save() }
        }

        val criticalBins = listOf("wget", "curl", "unzip", "zip", "rsync", "dpkg", "cmake", "ninja", "python3")
        val missing = executor.findMissingBinaries(criticalBins)
        if (missing.isNotEmpty()) {
            return fail(
                "apt-get install melaporkan sukses, tapi binary berikut tetap tidak " +
                    "ditemukan setelah install: ${missing.joinToString(", ")}. " +
                    "Coba jalankan 'apt-get update' manual dari Termux shell untuk melihat error aslinya."
            )
        }

        // ---- 3. Layout SDK (idempotent) ----
        if (!state.layoutReady) {
            progress(BuildLog.step(3, 7, "Membangun direktori SDK..."))
            createSdkLayout()
            state = state.copy(layoutReady = true, phase = SetupPhase.LAYOUT).also { it.save() }
        } else {
            progress(BuildLog.step(3, 7, "Membangun direktori SDK..."))
            progress(BuildLog.ok("Layout SDK sudah ada — skip."))
        }

        // Build-tools & cmake dummy (idempotent di dalam fungsinya)
        (BuilderPaths.DUMMY_BUILD_TOOLS_VERSIONS).forEach { setupDummyBuildTools(it) }
        (BuilderPaths.DUMMY_CMAKE_VERSIONS).forEach { setupDummyCmake(it) }

        // ---- 4. Platform SDK 34 ----
        if (!state.platform34Ready) {
            progress(BuildLog.step(4, 7, "Memastikan platform SDK (34)..."))
            if (!ensurePlatformSdk(34, progress, lineCb)) {
                return false
            }
            state = state.copy(platform34Ready = true, phase = SetupPhase.PLATFORM_34).also { it.save() }
        } else {
            progress(BuildLog.step(4, 7, "Memastikan platform SDK (34)..."))
            progress(BuildLog.ok("Platform android-34 sudah terpasang (dari state) — skip."))
        }

        writeSdkLicense()
        writeGlobalGradleProperties(profile)

        // ---- 5. NDK ----
        if (state.ndkReady && isNdkInstalled()) {
            progress(BuildLog.step(5, 7, "Memastikan Android NDK..."))
            progress(BuildLog.ok("NDK sudah terpasang: ${installedNdkVersions().joinToString(", ")} — skip download."))
        } else if (isNdkInstalled()) {
            progress(BuildLog.step(5, 7, "Memastikan Android NDK..."))
            progress(BuildLog.ok("NDK terdeteksi: ${installedNdkVersions().joinToString(", ")} — skip download."))
            state = state.copy(ndkReady = true, phase = SetupPhase.NDK).also { it.save() }
        } else {
            progress(BuildLog.step(5, 7, "Memastikan Android NDK..."))
            progress(BuildLog.warn("NDK belum terpasang, mendownload (sekali saja)..."))
            if (!installNdk(progress, lineCb)) {
                return false
            }
            state = state.copy(ndkReady = true, phase = SetupPhase.NDK).also { it.save() }
        }

        // ---- 6. Permission NDK & wrapper template ----
        progress(BuildLog.step(6, 7, "Memperbaiki permission NDK & wrapper template..."))
        fixNdkPermissions(progress, lineCb)
        if (!state.wrapperReady) {
            ensureWrapperTemplate(progress, lineCb)
            state = state.copy(wrapperReady = true, phase = SetupPhase.WRAPPER).also { it.save() }
        } else {
            progress(BuildLog.ok("Wrapper template sudah siap (dari state) — skip."))
        }

        // ---- 7. Verifikasi akhir ----
        progress(BuildLog.step(7, 7, "Verifikasi akhir..."))
        val sdkOk = isSdkReady()
        val ndkOk = isNdkInstalled()
        if (!sdkOk) progress(BuildLog.warn("SDK belum lengkap (platform android.jar belum ada) — build mungkin gagal."))
        if (!ndkOk) progress(BuildLog.warn("NDK belum lengkap — build native akan gagal."))

        state.copy(phase = SetupPhase.COMPLETE, sdkReady = sdkOk, ndkReady = ndkOk).save()
        progress(BuildLog.ok("Toolchain setup selesai (SDK=$sdkOk, NDK=$ndkOk)."))
        return true
    }

    private fun ensureStorageAccess(lineCb: ProcessExecutor.LineCallback) {
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

    /**
     * Install paket APT secara IDEMPOTENT (v4).
     *
     * Perbaikan "Cancel lalu Build lagi -> apt-get install gagal exit 100":
     *  - Cek dulu via `dpkg -l` paket mana yang SUDAH terpasang.
     *  - Hanya paket yang KURANG yang diinstall (`apt-get install -y <missing>`).
     *  - Jika tidak ada yang kurang, apt-get install TIDAK dijalankan sama sekali
     *    (mencegah error exit 100 pada repo yang rusak / dpkg lock).
     */
    private fun installSystemPackages(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback?): Boolean {
        val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")

        // v4: identifikasi paket yang belum terpasang via dpkg -l
        val missingPackages = missingAptPackages()
        if (missingPackages.isEmpty()) {
            progress(BuildLog.ok("Semua paket sistem sudah terpasang — apt-get install dilewati."))
            return true
        }
        progress(BuildLog.info("Paket yang belum terpasang: ${missingPackages.joinToString(", ")}"))

        File("$sdkDir/pkg-cache/partial").mkdirs()

        progress(BuildLog.info("apt-get update..."))
        val update = executor.executeShellCommand(
            "apt-get update -y",
            environment = env,
            lineCallback = lineCb,
            timeoutSeconds = 600
        )
        if (!update.isSuccess) {
            // v4: jika update gagal tapi semua paket yang kita butuhkan sudah ada,
            // lanjutkan saja (offline / repo rusak tidak memblokir).
            val stillMissing = missingAptPackages()
            if (stillMissing.isEmpty()) {
                progress(BuildLog.warn("apt-get update gagal tapi semua paket sudah terpasang — lanjut."))
                return true
            }
            return fail("apt-get update gagal (exit ${update.exitCode}): ${tailOf(update)}. Pastikan perangkat terhubung ke internet.")
        }

        val pkgList = missingPackages.joinToString(" ")
        progress(BuildLog.info("apt-get install -y $pkgList (hanya paket yang kurang)"))
        val install = executor.executeShellCommand(
            "apt-get install -y --fix-missing -o Dir::Cache::archives=$sdkDir/pkg-cache $pkgList",
            environment = env,
            lineCallback = lineCb,
            timeoutSeconds = 1800
        )
        if (!install.isSuccess) {
            // v4: cek ulang — jika ternyata paket sudah terpasang (mis. sebagian
            // berhasil sebelum error), jangan gagalkan setup total.
            val stillMissing = missingAptPackages()
            if (stillMissing.isEmpty()) {
                progress(BuildLog.warn("apt-get install melaporkan error (exit ${install.exitCode}) tapi semua paket sudah terpasang — lanjut."))
                return true
            }
            return fail("apt-get install gagal (exit ${install.exitCode}): ${tailOf(install)}. Paket kurang: ${stillMissing.joinToString(", ")}")
        }
        return true
    }

    /**
     * Daftar paket APT yang BELUM terpasang, dicek via `dpkg -l`.
     * v4: ini kunci idempotensi — tanpa ini, cancel lalu build ulang akan
     * menjalankan apt-get install lagi (gagal exit 100 di Termux).
     */
    fun missingAptPackages(): List<String> {
        val result = executor.executeShellCommand(
            "dpkg -l 2>/dev/null | awk '{print \\$2}'",
            timeoutSeconds = 60
        )
        val installed = if (result.isSuccess) {
            result.stdout.lines().map { it.trim() }.filter { it.isNotBlank() }.toSet()
        } else {
            emptySet()
        }
        return APT_PACKAGES.filter { it !in installed }
    }

    /** true bila semua paket APT yang dibutuhkan sudah terpasang. */
    fun allAptPackagesInstalled(): Boolean = missingAptPackages().isEmpty()

    private fun createSdkLayout() {
        listOf(
            "$sdkDir/pkg-cache",
            "$sdkDir/pkg-cache/partial",
            "$sdkDir/platforms",
            "$sdkDir/build-tools",
            "$sdkDir/licenses",
            "$sdkDir/cmake",
            "$sdkDir/ndk"
        ).forEach { File(it).mkdirs() }
    }

    private fun writeSdkLicense() {
        val license = File("$sdkDir/licenses/android-sdk-license")
        if (!license.exists()) {
            license.writeText("24333f8a637bced5e17096433f01641e5f692d6e\n")
        }
    }

    private fun writeGlobalGradleProperties(profile: HardwareProfile) {
        val gradleHome = File(BuilderPaths.DEFAULT_GRADLE_HOME)
        gradleHome.mkdirs()
        File(gradleHome, "gradle.properties").writeText(gradlePropertiesTemplate(profile))
    }

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

    // ============================================================
    // PLATFORM SDK (dengan skip-download & URL benar)
    // ============================================================

    /**
     * Pastikan platform SDK apiLevel terpasang.
     * Jika sudah ada & valid -> SKIP download (ini perbaikan inti).
     */
    fun ensurePlatformSdk(apiLevel: Int, progress: (String) -> Unit = { _ -> }, lineCb: ProcessExecutor.LineCallback? = null): Boolean {
        if (isPlatformInstalled(apiLevel)) {
            progress(BuildLog.ok("Platform android-$apiLevel sudah terpasang — skip download."))
            return true
        }
        progress(BuildLog.info("Platform android-$apiLevel belum lengkap, menyiapkan..."))
        return downloadPlatformSdk(apiLevel, progress, lineCb)
    }

    /**
     * Download platform SDK dari repositori resmi Google.
     *
     * v2 FIX (404): versi lama mencoba platform-34_r01..r04.zip yang TIDAK ADA.
     * Nama file sekarang di-resolve dari manifest repository2-1.xml Google
     * (paket `platforms;android-34` revision terbaru di channel 0) dengan
     * fallback tabel [DependencyCatalog.PLATFORM_ZIP_FALLBACK] yang sudah
     * diverifikasi (platform-34-ext7_r03.zip, dst).
     */
    fun downloadPlatformSdk(apiLevel: Int, progress: (String) -> Unit = { _ -> }, lineCb: ProcessExecutor.LineCallback? = null): Boolean {
        val platformDir = File("$sdkDir/platforms/android-$apiLevel")
        val androidJar = File(platformDir, "android.jar")

        // Hapus folder platform rusak/lama yang bukan milik level ini
        listOf("android-13", "android-14").forEach {
            File("$sdkDir/platforms/$it").deleteRecursively()
        }

        if (androidJar.exists() && androidJar.length() > 1_000_000) {
            progress(BuildLog.ok("Platform android-$apiLevel valid — skip download."))
            return true
        }

        val tmpZip = File("$sdkDir/platform-$apiLevel.zip")
        val tmpExtract = File("$sdkDir/platforms/tmp_extract")
        tmpZip.deleteRecursively()
        tmpExtract.deleteRecursively()
        tmpExtract.mkdirs()

        val hasWget = executor.isExecutableAvailable("wget")
        val hasCurl = executor.isExecutableAvailable("curl")
        if (!hasWget && !hasCurl) {
            return fail("Tidak bisa download platform SDK: binary 'wget' atau 'curl' tidak ditemukan di PREFIX/bin.")
        }

        // Resolve nama file zip dari manifest Google (nama resmi berbeda per level)
        val zipFileName = resolvePlatformZipFileName(apiLevel, progress)
            ?: run {
                tmpExtract.deleteRecursively()
                return fail("Tidak dapat menentukan nama file ZIP platform android-$apiLevel dari repository2-1.xml Google. Coba manual: letakkan platform di $platformDir dengan android.jar lengkap.")
            }
        val url = DependencyCatalog.GOOGLE_REPO_BASE + zipFileName
        progress(BuildLog.info("Download platform: $zipFileName"))

        val cmd = if (hasWget) {
            "wget -O '$tmpZip' '$url' && test -s '$tmpZip'"
        } else {
            "curl -fsSL -o '$tmpZip' '$url' && test -s '$tmpZip'"
        }
        val download = executor.executeShellCommand(
            cmd,
            lineCallback = lineCb,
            timeoutSeconds = 900
        )

        if (!download.isSuccess || !tmpZip.exists() || tmpZip.length() < 10_000_000) {
            tmpZip.deleteRecursively()
            tmpExtract.deleteRecursively()
            return fail(
                "Download platform android-$apiLevel gagal ($url). " +
                    "Alasan: ${tailOf(download)}. " +
                    "Solusi offline: letakkan android.jar (lengkap, >10MB) di $platformDir."
            )
        }

        val extract = executor.executeShellCommand(
            "unzip -o -q '$tmpZip' -d '$tmpExtract' 2>/dev/null",
            lineCallback = lineCb,
            timeoutSeconds = 300
        )
        if (extract.isSuccess) {
            tmpZip.deleteRecursively()
            val extracted = tmpExtract.listFiles()?.firstOrNull { it.isDirectory }
            if (extracted != null && File(extracted, "android.jar").exists() && File(extracted, "android.jar").length() > 1_000_000) {
                platformDir.deleteRecursively()
                extracted.renameTo(platformDir)
                tmpExtract.deleteRecursively()
                writePlatformSourceProperties(platformDir, apiLevel)
                progress(BuildLog.ok("Platform android-$apiLevel terpasang."))
                return true
            }
        }

        tmpExtract.deleteRecursively()
        tmpZip.deleteRecursively()

        // Fallback AOSP (hanya android.jar — tidak lengkap tapi bisa untuk compile)
        progress(BuildLog.warn("Download resmi gagal — mencoba fallback AOSP android.jar..."))
        platformDir.mkdirs()
        val fbCmd = if (hasWget) {
            "wget -O '${File(platformDir, "android.jar").absolutePath}' 'https://github.com/Reginer/aosp-android-jar/raw/main/android-$apiLevel/android.jar'"
        } else {
            "curl -fsSL -o '${File(platformDir, "android.jar").absolutePath}' 'https://github.com/Reginer/aosp-android-jar/raw/main/android-$apiLevel/android.jar'"
        }
        val fb = executor.executeShellCommand(
            fbCmd,
            lineCallback = lineCb,
            timeoutSeconds = 900
        )
        if (fb.isSuccess && File(platformDir, "android.jar").length() > 1_000_000) {
            writePlatformSourceProperties(platformDir, apiLevel)
            progress(BuildLog.ok("Fallback AOSP android.jar terpasang (android-$apiLevel)."))
            return true
        }
        return fail(
            "Download platform SDK android-$apiLevel gagal total (resmi maupun fallback AOSP). " +
                "Alasan resmi: ${tailOf(download)}. Alasan fallback: ${tailOf(fb)}. " +
                "Masukkan platform android-$apiLevel lengkap ke backup zip (folder android-sdk/platforms/android-$apiLevel)."
        )
    }

    /**
     * Resolve nama file ZIP platform dari manifest repository2-1.xml Google.
     * Mengambil paket `platforms;android-<api>` revision terbaru channel 0
     * dengan archive URL `platform-*.zip`.
     *
     * Karena XML besar (~1MB), dipakai `grep` via shell (lebih cepat daripada
     * parse XML di Kotlin). Fallback ke tabel [DependencyCatalog.PLATFORM_ZIP_FALLBACK].
     */
    private fun resolvePlatformZipFileName(apiLevel: Int, progress: (String) -> Unit): String? {
        // Fallback tabel (diverifikasi) — cepat & pasti
        DependencyCatalog.PLATFORM_ZIP_FALLBACK[apiLevel]?.let {
            return it
        }

        progress(BuildLog.info("Query repository2-1.xml Google untuk platform-$apiLevel..."))
        val manifest = File("$sdkDir/repository2-1.xml")
        val dl = executor.executeShellCommand(
            "wget -q -O '$manifest' 'https://dl.google.com/android/repository/repository2-1.xml' || " +
                "curl -fsSL -o '$manifest' 'https://dl.google.com/android/repository/repository2-1.xml' || true",
            timeoutSeconds = 120
        )
        if (!dl.isSuccess || !manifest.exists() || manifest.length() < 100_000) {
            return null
        }

        // Cari blok remotePackage path="platforms;android-XX" non-obsolete dengan url platform-XX*.zip
        // Ambil yang TERAKHIR (revision tertinggi di akhir file)
        val result = executor.executeShellCommand(
            "awk '/<remotePackage path=\"platforms;android-$apiLevel\">/{f=1} f&&/<url>platform-[^<]+\\.zip<\\/url>/{u=\$0} f&&/<\\/remotePackage>/{if(u!=\"\"){print u; exit}}' '$manifest'",
            timeoutSeconds = 30
        )
        if (result.isSuccess) {
            val m = Regex("<url>([^<]+)</url>").find(result.stdout)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    private fun writePlatformSourceProperties(platformDir: File, apiLevel: Int) {
        val sp = File(platformDir, "source.properties")
        sp.writeText("Pkg.Revision=1\nAndroidVersion.ApiLevel=$apiLevel\n")
        val aidl = File(platformDir, "framework.aidl")
        if (!aidl.exists() || aidl.length() == 0L) {
            aidl.writeText(DEFAULT_FRAMEWORK_AIDL)
        }
    }

    // ============================================================
    // BUILD-TOOLS & CMAKE (dummy + symlink, skip jika sudah ada)
    // ============================================================

    fun setupDummyBuildTools(version: String) {
        val btDir = File("$sdkDir/build-tools/$version")
        if (File(btDir, "source.properties").exists() && File(btDir, "aapt2").exists()) {
            return // sudah disetup sebelumnya
        }
        File(btDir, "lib").mkdirs()
        File(btDir, "renderscript/include").mkdirs()
        File(btDir, "renderscript/clang-include").mkdirs()

        BT_TOOLS.forEach { tool ->
            val prefixBin = File("${BuilderPaths.PREFIX_BIN_DIR}/$tool")
            if (prefixBin.exists()) {
                val link = File(btDir, tool)
                if (!link.exists()) {
                    executor.executeShellCommand("ln -sf '${prefixBin.absolutePath}' '${link.absolutePath}'")
                }
            }
        }

        if (File("${BuilderPaths.PREFIX_BIN_DIR}/d8").exists() && !File(btDir, "dx").exists()) {
            executor.executeShellCommand(
                "ln -sf '${BuilderPaths.PREFIX_BIN_DIR}/d8' '${File(btDir, "dx").absolutePath}'"
            )
        }

        val aidlLink = File(btDir, "aidl")
        val prefixAidl = File("${BuilderPaths.PREFIX_BIN_DIR}/aidl")
        if (prefixAidl.exists()) {
            executor.executeShellCommand("ln -sf '${prefixAidl.absolutePath}' '${aidlLink.absolutePath}'")
        } else if (!aidlLink.exists() || aidlLink.length() == 0L) {
            aidlLink.writeText(DUMMY_AIDL_SCRIPT)
            aidlLink.setExecutable(true)
        }

        DUMMY_EXECS.forEach { name ->
            val f = File(btDir, name)
            if (!f.exists()) {
                f.writeText("#!/bin/sh\nexit 0\n")
                f.setExecutable(true)
            }
        }

        val emptyZip = Base64.decode("UEsFBgAAAAAAAAAAAAAAAAAAAAAAAA==", Base64.DEFAULT)
        DUMMY_JARS.forEach { rel ->
            val f = File(btDir, rel)
            if (!f.exists() || f.length() == 0L) {
                f.parentFile?.mkdirs()
                f.writeBytes(emptyZip)
            }
        }

        File(btDir, "source.properties").writeText("Pkg.PluginsSource=Android SDK\nPkg.Revision=$version\n")
    }

    fun setupDummyCmake(version: String) {
        val cmakeDir = File("$sdkDir/cmake/$version")
        if (File(cmakeDir, "source.properties").exists() && File(cmakeDir, "bin/cmake").exists()) {
            return // sudah disetup
        }
        File(cmakeDir, "bin").mkdirs()

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

    // ============================================================
    // NDK (skip-download jika sudah ada)
    // ============================================================

    private fun installNdk(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback?): Boolean {
        val downloadUrl: String
        val actualVersion: String
        if (ndkVersion == BuilderPaths.DEFAULT_NDK_VERSION) {
            downloadUrl = DependencyCatalog.NDK_R29_URL
            actualVersion = DependencyCatalog.NDK_R29_VERSION
        } else {
            downloadUrl = DependencyCatalog.NDK_R25C_URL
            actualVersion = ndkVersion
        }

        // Double-check: mungkin NDK sudah ada dari backup zip
        if (isNdkInstalled()) {
            progress(BuildLog.ok("NDK sudah terpasang (${installedNdkVersions().joinToString(", ")}) — skip download."))
            return true
        }

        val ndkZip = File("$sdkDir/ndk-download.7z")
        val tmpDir = File("$sdkDir/ndk/tmp")
        tmpDir.mkdirs()

        progress(BuildLog.info("Download NDK (sekitar 400 MB, sekali saja)..."))
        var dl = executor.executeShellCommand(
            "wget --show-progress -O '$ndkZip' '$downloadUrl'",
            lineCallback = lineCb,
            timeoutSeconds = 2400
        )
        if (!dl.isSuccess || !ndkZip.exists() || ndkZip.length() == 0L) {
            progress(BuildLog.warn("Gagal mendownload NDK (wget). Mencoba curl..."))
            dl = executor.executeShellCommand(
                "curl -fsSL --retry 2 -o '$ndkZip' '$downloadUrl'",
                lineCallback = lineCb,
                timeoutSeconds = 2400
            )
            if (!dl.isSuccess || !ndkZip.exists() || ndkZip.length() == 0L) {
                return fail("Download NDK gagal (wget & curl): ${tailOf(dl)}")
            }
        }

        progress(BuildLog.info("Mengekstrak NDK..."))
        var ex = executor.executeShellCommand(
            "unzip -o '$ndkZip' -d '$tmpDir'",
            lineCallback = lineCb,
            timeoutSeconds = 900
        )
        if (!ex.isSuccess) {
            ex = executor.executeShellCommand(
                "7z x -y -o'$tmpDir' '$ndkZip'",
                lineCallback = lineCb,
                timeoutSeconds = 900
            )
        }
        if (!ex.isSuccess) {
            val reason = tailOf(ex)
            ndkZip.deleteRecursively()
            return fail("Gagal mengekstrak NDK (unzip/7z): $reason")
        }

        val extracted = findNdkRoot(tmpDir) ?: run {
            ndkZip.deleteRecursively()
            tmpDir.deleteRecursively()
            fail("Folder NDK (ndk-build) tidak ditemukan di dalam arsip yang diekstrak.")
            null
        } ?: return false

        File("$sdkDir/ndk").mkdirs()
        val target = File("$sdkDir/ndk/$actualVersion")
        if (target.exists()) target.deleteRecursively()
        extracted.renameTo(target)
        tmpDir.deleteRecursively()
        ndkZip.deleteRecursively()
        progress(BuildLog.ok("NDK $actualVersion terpasang."))
        return true
    }

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

    fun fixNdkPermissions(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback? = null) {
        if (!File(ndkDir).exists()) return

        executor.executeShellCommand("chmod -R +x '$ndkDir' 2>/dev/null || true", lineCallback = lineCb, timeoutSeconds = 300)

        val prebuiltBin = File("$ndkDir/prebuilt/linux-aarch64/bin")
        prebuiltBin.mkdirs()
        val prefixBin = BuilderPaths.PREFIX_BIN_DIR
        if (File("$prefixBin/make").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/make' '${File(prebuiltBin, "make").absolutePath}' 2>/dev/null || true", lineCallback = lineCb)
        }
        if (File("$prefixBin/python3").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/python3' '${File(prebuiltBin, "python3").absolutePath}' 2>/dev/null || true", lineCallback = lineCb)
        }

        executor.executeShellCommand(
            "command -v termux-fix-shebang >/dev/null 2>&1 && " +
                "find '$ndkDir' -type f \\( -name '*.sh' -o -name 'ndk-build' \\) -exec termux-fix-shebang {} \\; 2>/dev/null || true",
            lineCallback = lineCb,
            timeoutSeconds = 600
        )
    }

    // ============================================================
    // WRAPPER TEMPLATE (skip-download jika sudah ada)
    // ============================================================

    fun ensureWrapperTemplate(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback? = null) {
        val wrapperDir = File(BuilderPaths.DEFAULT_WRAPPER_DIR)
        val wrapperSub = File(wrapperDir, "gradle/wrapper")
        wrapperSub.mkdirs()

        val jar = File(wrapperSub, "gradle-wrapper.jar")
        if (!jar.exists() || jar.length() < 10_000) {
            progress(BuildLog.info("Mendownload gradle-wrapper.jar template..."))
            executor.executeShellCommand(
                "wget -q -O '${jar.absolutePath}' 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' || " +
                    "curl -fsSL -o '${jar.absolutePath}' 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' || true",
                lineCallback = lineCb,
                timeoutSeconds = 300
            )
        } else {
            progress(BuildLog.ok("gradle-wrapper.jar sudah ada — skip download."))
        }

        File(wrapperDir, "settings.gradle").writeText("rootProject.name='wrapper-template'\n")

        val gradlew = File(wrapperDir, "gradlew")
        if (!gradlew.exists()) {
            gradlew.writeText("#!/bin/sh\n# minimal gradlew (wrapper)\nexec \"$0\" \"$@\"\n")
            gradlew.setExecutable(true)
        }

        val props = File(wrapperSub, "gradle-wrapper.properties")
        if (!props.exists()) {
            props.writeText(
                "distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n" +
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-bin.zip\n" +
                    "networkTimeout=10000\nvalidateDistributionUrl=true\nzipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n"
            )
        }

        if (File("${BuilderPaths.PREFIX_BIN_DIR}/gradle").exists()) {
            executor.executeShellCommand(
                "cd '${wrapperDir.absolutePath}' && gradle wrapper --gradle-version 8.13 --no-daemon -q 2>/dev/null || true",
                lineCallback = lineCb,
                timeoutSeconds = 600
            )
        }
    }
}