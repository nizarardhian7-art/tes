package com.termux.builder.toolchain

import android.util.Base64
import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuilderPaths
import com.termux.builder.model.HardwareProfile
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
 */
class ToolchainManager(
    private val context: android.content.Context,
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

        /** Konten AIDL dummy (python script dari build.sh). */
        private val DUMMY_AIDL_SCRIPT = """#!/usr/bin/env python3
import sys, os, re
args = sys.argv[1:]
out_dir, input_files = None, []
i = 0
while i < len(args):
    arg = args[i]
    if arg.startswith('-o'):
        out_dir = arg[2:] if len(arg) > 2 else (args[i+1] if i+1 < len(args) else None)
        if len(arg) <= 2: i += 1
    elif arg.endswith('.aidl'): input_files.append(arg)
    i += 1
if out_dir:
    for aidl_file in input_files:
        if not os.path.exists(aidl_file): continue
        try:
            with open(aidl_file, 'r', encoding='utf-8', errors='ignore') as f: content = f.read()
            pkg_m = re.search(r'package\s+([\w.]+)\s*;', content)
            pkg = pkg_m.group(1) if pkg_m else ''
            iface_m = re.search(r'(interface|parcelable)\s+(\w+)', content)
            iface = iface_m.group(2) if iface_m else None
            if iface:
                tdir = os.path.join(out_dir, *pkg.split('.')) if pkg else out_dir
                os.makedirs(tdir, exist_ok=True)
                tjava = os.path.join(tdir, f"{iface}.java")
                jcode = f"package {pkg};\npublic interface {iface} extends android.os.IInterface {{\n    public static abstract class Stub extends android.os.Binder implements {pkg}.{iface} {{\n        private static final java.lang.String DESCRIPTOR = \"{pkg}.{iface}\";\n        public Stub() {{ this.attachInterface(this, DESCRIPTOR); }}\n        public static {pkg}.{iface} asInterface(android.os.IBinder obj) {{\n            if (obj == null) return null;\n            android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);\n            if (iin != null && iin instanceof {pkg}.{iface}) return ({pkg}.{iface}) iin;\n            return new {pkg}.{iface}.Stub.Proxy(obj);\n        }}\n        @Override public android.os.IBinder asBinder() {{ return this; }}\n        private static class Proxy implements {pkg}.{iface} {{\n            private android.os.IBinder mRemote;\n            Proxy(android.os.IBinder remote) {{ mRemote = remote; }}\n            @Override public android.os.IBinder asBinder() {{ return mRemote; }}\n        }}\n    }}\n}}\n"""
                with open(tjava, 'w', encoding='utf-8') as f: f.write(jcode)
        except Exception: pass
sys.exit(0)
"""

        /** Template ~/.gradle/gradle.properties (dari build.sh auto_setup). */
        private fun gradlePropertiesTemplate(profile: HardwareProfile): String = """
android.aapt2FromMavenOverride=${BuilderPaths.PREFIX_BIN_DIR}/aapt2
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=${profile.gradleJvmArgs}
org.gradle.daemon=false
org.gradle.parallel=false
org.gradle.caching=true
org.gradle.daemon.performance.disable-logging=true
org.gradle.java.installations.auto-detect=false
org.gradle.java.installations.auto-download=false
org.gradle.java.installations.paths=${BuilderPaths.PREFIX_BIN_DIR}/../lib/jvm/java-17-openjdk
org.gradle.native=false
kotlin.compiler.execution.strategy=in-process
kotlin.incremental=true
android.builder.sdkDownload=false
org.gradle.workers.max=${profile.maxWorkers}
""".trimIndent() + "\n"
    }

    val ndkDir: String get() = "$sdkDir/ndk/$ndkVersion"

    /** True bila SDK sudah siap (directory + license + platform 34). */
    fun isSdkReady(): Boolean {
        return File("$sdkDir/platforms/android-34/android.jar").exists() &&
            File("$sdkDir/licenses/android-sdk-license").exists()
    }

    /** True bila NDK sudah terpasang. */
    fun isNdkInstalled(): Boolean {
        return File("$ndkDir/ndk-build").exists() || File("$ndkDir/build/ndk-build").exists()
    }

    /**
     * Setup lengkap toolchain (auto_setup).
     * @param profile profil hardware untuk jvm args
     * @param progress callback pesan progress
     */
    fun setupToolchain(profile: HardwareProfile, progress: (String) -> Unit): Boolean {
        progress("Memastikan akses storage...")
        ensureStorageAccess()

        progress("Menginstall paket sistem (APT)...")
        if (!installSystemPackages(progress)) {
            return false
        }

        progress("Membangun direktori SDK & target dummy...")
        createSdkLayout()

        DUMMY_BUILD_TOOLS.forEach { setupDummyBuildTools(it) }
        DUMMY_CMAKE.forEach { setupDummyCmake(it) }

        progress("Mendownload platform SDK 34...")
        if (!downloadPlatformSdk(34)) {
            return false
        }

        writeSdkLicense()
        writeGlobalGradleProperties(profile)

        if (!isNdkInstalled()) {
            progress("Mendownload Android NDK r25c (besar, mohon tunggu)...")
            if (!installNdk(progress)) {
                return false
            }
        } else {
            progress("NDK sudah terpasang.")
        }

        fixNdkPermissions(progress)
        ensureWrapperTemplate(progress)

        progress("Toolchain setup selesai.")
        return true
    }

    private fun ensureStorageAccess() {
        // termux-setup-storage: membuat symlink ~/storage ke /sdcard
        val storageLink = File("${BuilderPaths.DEFAULT_WORKSPACE_DIR}/../storage")
        if (!storageLink.exists()) {
            executor.executeShellCommand("termux-setup-storage -y 2>/dev/null || true")
        }
    }

    private fun installSystemPackages(progress: (String) -> Unit): Boolean {
        val update = executor.executeShellCommand(
            "apt-get update -y 2>&1 | tail -n 5",
            timeoutSeconds = 600
        )
        if (!update.isSuccess) {
            progress("apt-get update gagal (${update.exitCode})")
        }

        val pkgList = APT_PACKAGES.joinToString(" ")
        val install = executor.executeShellCommand(
            "apt-get install -y -o Dir::Cache::archives=$sdkDir/pkg-cache $pkgList 2>&1 | tail -n 10",
            timeoutSeconds = 1200
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
        val gradleHome = File("${BuilderPaths.PREFIX_BIN_DIR}/../../.gradle")
        gradleHome.mkdirs()
        File(gradleHome, "gradle.properties").writeText(gradlePropertiesTemplate(profile))
    }

    /** Download & ekstrak platform SDK untuk API level. */
    fun downloadPlatformSdk(apiLevel: Int): Boolean {
        val platformDir = File("$sdkDir/platforms/android-$apiLevel")
        val androidJar = File(platformDir, "android.jar")

        // Bersihkan platform lama yang tidak dipakai (build.sh menghapus 13 & 14)
        listOf("android-13", "android-14").forEach {
            File("$sdkDir/platforms/$it").deleteRecursively()
        }

        // Validasi platform yang sudah ada
        if (androidJar.exists() && File(platformDir, "core-for-system-modules.jar").exists() &&
            androidJar.length() != File(platformDir, "core-for-system-modules.jar").length()
        ) {
            platformDir.deleteRecursively()
        } else if (androidJar.exists()) {
            return true
        }

        val tmpZip = File("$sdkDir/platform-$apiLevel.zip")
        val tmpExtract = File("$sdkDir/platforms/tmp_extract")
        tmpZip.deleteRecursively(); tmpExtract.deleteRecursively()
        tmpExtract.mkdirs()

        // Download official
        val url = "https://dl.google.com/android/repository/platform-${apiLevel}_r01.zip"
        val download = executor.executeShellCommand(
            "wget -q -O '$tmpZip' '$url' 2>/dev/null && test -s '$tmpZip' && echo OK || echo FAIL",
            timeoutSeconds = 600
        )
        if (download.isSuccess && download.stdout.contains("OK")) {
            val extract = executor.executeShellCommand(
                "unzip -o -q '$tmpZip' -d '$tmpExtract' 2>/dev/null && echo OK || echo FAIL",
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

        tmpExtract.deleteRecursively(); tmpZip.deleteRecursively()

        // Fallback: AOSP android.jar dari Reginer
        progressOnly("Official download gagal, mencoba fallback AOSP platform...")
        val fallbackDir = platformDir
        fallbackDir.mkdirs()
        val fb = executor.executeShellCommand(
            "wget -q -O '${File(fallbackDir, "android.jar").absolutePath}' " +
                "'https://github.com/Reginer/aosp-android-jar/raw/main/android-$apiLevel/android.jar' && echo OK || echo FAIL",
            timeoutSeconds = 600
        )
        if (fb.isSuccess && fb.stdout.contains("OK")) {
            writePlatformSourceProperties(fallbackDir, apiLevel)
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
            executor.executeShellCommand("ln -sf '${BuilderPaths.PREFIX_BIN_DIR}/d8' '${File(btDir, "dx").absolutePath}'")
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

    /** Download & ekstrak NDK r25c (aarch64) bila belum ada. */
    private fun installNdk(progress: (String) -> Unit): Boolean {
        val ndkZip = File("$sdkDir/ndk.zip")
        val tmpDir = File("$sdkDir/ndk/tmp")
        tmpDir.mkdirs()

        val url = "https://github.com/Lzhiyong/termux-ndk/releases/download/android-ndk/android-ndk-r25c-aarch64.zip"
        progress("Mendownload NDK r25c (sekitar 500 MB)...")
        val dl = executor.executeShellCommand(
            "wget -q --show-progress -O '$ndkZip' '$url' 2>/dev/null && echo OK || echo FAIL",
            timeoutSeconds = 1800
        )
        if (!dl.isSuccess || !dl.stdout.contains("OK")) {
            progress("Gagal mendownload NDK.")
            return false
        }

        progress("Mengekstrak NDK...")
        val ex = executor.executeShellCommand(
            "unzip -q '$ndkZip' -d '$tmpDir' 2>/dev/null && echo OK || echo FAIL",
            timeoutSeconds = 900
        )
        if (!ex.isSuccess || !ex.stdout.contains("OK")) {
            progress("Gagal mengekstrak NDK.")
            ndkZip.deleteRecursively()
            return false
        }

        val extracted = tmpDir.listFiles()?.firstOrNull { it.isDirectory }
        if (extracted == null) {
            ndkZip.deleteRecursively(); tmpDir.deleteRecursively()
            return false
        }

        File("$sdkDir/ndk").mkdirs()
        val target = File(ndkDir)
        if (target.exists()) target.deleteRecursively()
        extracted.renameTo(target)
        tmpDir.deleteRecursively()
        ndkZip.deleteRecursively()
        return true
    }

    /** Perbaiki permission NDK + symlink make/python3 + fix shebang (dari build.sh). */
    fun fixNdkPermissions(progress: (String) -> Unit) {
        if (!File(ndkDir).exists()) return

        // chmod -R +x
        executor.executeShellCommand("chmod -R +x '$ndkDir' 2>/dev/null || true", timeoutSeconds = 300)

        // prebuilt/linux-aarch64/bin dengan symlink make & python3
        val prebuiltBin = File("$ndkDir/prebuilt/linux-aarch64/bin")
        prebuiltBin.mkdirs()
        val prefixBin = BuilderPaths.PREFIX_BIN_DIR
        if (File("$prefixBin/make").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/make' '${File(prebuiltBin, "make").absolutePath}' 2>/dev/null || true")
        }
        if (File("$prefixBin/python3").exists()) {
            executor.executeShellCommand("ln -sf '$prefixBin/python3' '${File(prebuiltBin, "python3").absolutePath}' 2>/dev/null || true")
        }

        // termux-fix-shebang untuk semua script NDK
        executor.executeShellCommand(
            "command -v termux-fix-shebang >/dev/null 2>&1 && " +
                "find '$ndkDir' -type f \\( -name '*.sh' -o -name 'ndk-build' \\) -exec termux-fix-shebang {} \\; 2>/dev/null || true",
            timeoutSeconds = 600
        )
    }

    /** Siapkan wrapper template (gradlew + gradle-wrapper.jar + properties). */
    fun ensureWrapperTemplate(progress: (String) -> Unit) {
        val wrapperDir = File(BuilderPaths.DEFAULT_WRAPPER_DIR)
        val wrapperSub = File(wrapperDir, "gradle/wrapper")
        wrapperSub.mkdirs()

        val jar = File(wrapperSub, "gradle-wrapper.jar")
        if (!jar.exists() || jar.length() < 10_000) {
            progress("Mendownload gradle-wrapper.jar template...")
            executor.executeShellCommand(
                "wget -q -O '${jar.absolutePath}' " +
                    "'https://raw.githubusercontent.com/gradle/gradle/v8.7.0/gradle/wrapper/gradle-wrapper.jar' 2>/dev/null || true",
                timeoutSeconds = 300
            )
        }

        // Buat settings.gradle minimal untuk wrapper template
        File(wrapperDir, "settings.gradle").writeText("rootProject.name='wrapper-template'\n")

        // Jalankan gradle wrapper bila gradle CLI tersedia
        if (File("${BuilderPaths.PREFIX_BIN_DIR}/gradle").exists()) {
            executor.executeShellCommand(
                "cd '${wrapperDir.absolutePath}' && gradle wrapper --gradle-version 8.7 --no-daemon -q 2>/dev/null || true",
                timeoutSeconds = 600
            )
        }
    }

    /** Helper: kirim pesan progress tanpa mengubah status phase. */
    private fun progressOnly(msg: String) {
        com.termux.shared.logger.Logger.logInfo(LOG_TAG, msg)
    }

    companion object {
        private const val DEFAULT_FRAMEWORK_AIDL = """interface java.lang.CharSequence;
interface java.lang.String;
parcelable android.accounts.Account;
parcelable android.app.PendingIntent;
parcelable android.content.ComponentName;
parcelable android.content.Intent;
parcelable android.content.IntentFilter;
parcelable android.graphics.Bitmap;
parcelable android.graphics.Rect;
parcelable android.net.Uri;
parcelable android.os.Bundle;
parcelable android.os.ParcelFileDescriptor;
parcelable android.os.ParcelUuid;
parcelable android.os.PersistableBundle;
parcelable android.view.KeyEvent;
parcelable android.view.MotionEvent;
"""
    }
}
