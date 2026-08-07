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

    private fun tailOf(result: com.termux.builder.model.CommandResult, maxLen: Int = 300): String {
        val text = result.stderr.ifBlank { result.stdout }.trim()
        val lastLines = text.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")
        return lastLines.take(maxLen).ifBlank { "(exit ${result.exitCode}, tidak ada output)" }
    }

    fun isSdkReady(): Boolean {
        val platformsDir = File("$sdkDir/platforms")
        val hasPlatform = platformsDir.isDirectory &&
            platformsDir.listFiles()?.any { it.isDirectory && File(it, "android.jar").exists() } == true
        return hasPlatform && File("$sdkDir/licenses/android-sdk-license").exists()
    }

    fun isNdkInstalled(): Boolean {
        return installedNdkVersions().any { v ->
            File("$sdkDir/ndk/$v/ndk-build").exists() || File("$sdkDir/ndk/$v/build/ndk-build").exists()
        }
    }

    fun setupToolchain(profile: HardwareProfile, progress: (String) -> Unit): Boolean {
        lastError = null
        val lineCb = object : ProcessExecutor.LineCallback {
            override fun onLine(line: String) {
                if (line.isNotBlank()) progress(line.take(400))
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

        progress("Memastikan akses storage...")
        ensureStorageAccess(lineCb)

        progress("Menginstall paket sistem (APT)...")
        if (!installSystemPackages(progress, lineCb)) {
            return false
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
            progress("Mendownload Android NDK (besar, mohon tunggu)...")
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

    private fun installSystemPackages(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback?): Boolean {
        val env = mapOf("DEBIAN_FRONTEND" to "noninteractive")

        // WAJIB: Pastikan folder partial untuk APT cache dibuat sebelum apt-get run!
        File("$sdkDir/pkg-cache/partial").mkdirs()

        progress("apt-get update...")
        // Tanpa pipe '| tail -n 5' agar tidak pemicu SIGPIPE di bash pipefail
        val update = executor.executeShellCommand(
            "apt-get update -y",
            environment = env,
            lineCallback = lineCb,
            timeoutSeconds = 600
        )
        if (!update.isSuccess) {
            return fail("apt-get update gagal (exit ${update.exitCode}): ${tailOf(update)}. Pastikan perangkat terhubung ke internet.")
        }

        val pkgList = APT_PACKAGES.joinToString(" ")
        progress("apt-get install $pkgList")
        val install = executor.executeShellCommand(
            "apt-get install -y --fix-missing -o Dir::Cache::archives=$sdkDir/pkg-cache $pkgList",
            environment = env,
            lineCallback = lineCb,
            timeoutSeconds = 1800
        )
        if (!install.isSuccess) {
            return fail("apt-get install gagal (exit ${install.exitCode}): ${tailOf(install)}")
        }
        return true
    }

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
        val gradleHome = File(BuilderPaths.DEFAULT_HOME_DIR, ".gradle")
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

    fun downloadPlatformSdk(apiLevel: Int, progress: (String) -> Unit = { _ -> }, lineCb: ProcessExecutor.LineCallback? = null): Boolean {
        val platformDir = File("$sdkDir/platforms/android-$apiLevel")
        val androidJar = File(platformDir, "android.jar")

        listOf("android-13", "android-14").forEach {
            File("$sdkDir/platforms/$it").deleteRecursively()
        }

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

        if (!executor.isExecutableAvailable("wget")) {
            return fail("Tidak bisa download platform SDK: binary 'wget' tidak ditemukan. Pastikan apt-get install wget sukses.")
        }

        var downloaded = false
        var lastDl: com.termux.builder.model.CommandResult? = null
        for (revision in 1..4) {
            val url = "https://dl.google.com/android/repository/platform-${apiLevel}_r${revision.toString().padStart(2, '0')}.zip"
            progress("Mencoba download platform-$apiLevel r$revision...")
            val download = executor.executeShellCommand(
                "wget -O '$tmpZip' '$url' && test -s '$tmpZip'",
                lineCallback = lineCb,
                timeoutSeconds = 600
            )
            lastDl = download
            if (download.isSuccess && tmpZip.exists() && tmpZip.length() > 0) {
                downloaded = true
                break
            }
        }

        if (downloaded) {
            val extract = executor.executeShellCommand(
                "unzip -o -q '$tmpZip' -d '$tmpExtract' 2>/dev/null",
                lineCallback = lineCb,
                timeoutSeconds = 300
            )
            if (extract.isSuccess) {
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

        Logger.logInfo(LOG_TAG, "Official download gagal, mencoba fallback AOSP platform...")
        progress("Official download gagal — fallback AOSP android.jar...")
        platformDir.mkdirs()
        val fb = executor.executeShellCommand(
            "wget -O '${File(platformDir, "android.jar").absolutePath}' " +
                "'https://github.com/Reginer/aosp-android-jar/raw/main/android-$apiLevel/android.jar'",
            lineCallback = lineCb,
            timeoutSeconds = 900
        )
        if (fb.isSuccess && File(platformDir, "android.jar").length() > 0) {
            writePlatformSourceProperties(platformDir, apiLevel)
            return true
        }
        return fail(
            "Download platform SDK android-$apiLevel gagal total (official r01-r04 maupun fallback AOSP). " +
                "Alasan official: ${lastDl?.let { tailOf(it) } ?: "-"}. Alasan fallback: ${tailOf(fb)}."
        )
    }

    private fun writePlatformSourceProperties(platformDir: File, apiLevel: Int) {
        val sp = File(platformDir, "source.properties")
        sp.writeText("Pkg.Revision=1\nAndroidVersion.ApiLevel=$apiLevel\n")
        val aidl = File(platformDir, "framework.aidl")
        if (!aidl.exists() || aidl.length() == 0L) {
            aidl.writeText(DEFAULT_FRAMEWORK_AIDL)
        }
    }

    fun setupDummyBuildTools(version: String) {
        val btDir = File("$sdkDir/build-tools/$version")
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

    private fun installNdk(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback?): Boolean {
        val downloadUrl: String
        val actualVersion: String
        if (ndkVersion == BuilderPaths.DEFAULT_NDK_VERSION) {
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
        var dl = executor.executeShellCommand(
            "wget --show-progress -O '$ndkZip' '$downloadUrl'",
            lineCallback = lineCb,
            timeoutSeconds = 2400
        )
        if (!dl.isSuccess || !ndkZip.exists() || ndkZip.length() == 0L) {
            progress("Gagal mendownload NDK (wget). Mencoba curl...")
            dl = executor.executeShellCommand(
                "curl -fsSL --retry 2 -o '$ndkZip' '$downloadUrl'",
                lineCallback = lineCb,
                timeoutSeconds = 2400
            )
            if (!dl.isSuccess || !ndkZip.exists() || ndkZip.length() == 0L) {
                return fail("Download NDK gagal (wget & curl): ${tailOf(dl)}")
            }
        }

        progress("Mengekstrak NDK (7z)...")
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

    fun ensureWrapperTemplate(progress: (String) -> Unit, lineCb: ProcessExecutor.LineCallback? = null) {
        val wrapperDir = File(BuilderPaths.DEFAULT_WRAPPER_DIR)
        val wrapperSub = File(wrapperDir, "gradle/wrapper")
        wrapperSub.mkdirs()

        val jar = File(wrapperSub, "gradle-wrapper.jar")
        if (!jar.exists() || jar.length() < 10_000) {
            progress("Mendownload gradle-wrapper.jar template...")
            executor.executeShellCommand(
                "wget -q -O '${jar.absolutePath}' 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' || " +
                    "curl -fsSL -o '${jar.absolutePath}' 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' || true",
                lineCallback = lineCb,
                timeoutSeconds = 300
            )
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
                    "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.13-all.zip\n" +
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

    fun installedNdkVersions(): List<String> {
        val ndkRoot = File("$sdkDir/ndk")
        if (!ndkRoot.isDirectory) return emptyList()
        return ndkRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
    }

    fun installedNdkVersion(): String? = installedNdkVersions().firstOrNull()
}