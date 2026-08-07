package com.termux.builder.exec

import android.content.Context
import com.termux.builder.model.CommandResult
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Eksekutor perintah shell programatik berbasis AppShell (Runner.APP_SHELL) dari termux-shared.
 *
 * Tidak menggunakan ProcessBuilder/Runtime.exec langsung: seluruh eksekusi memakai
 * [AppShell.execute] dengan [TermuxShellEnvironment], sehingga environment Termux
 * (PREFIX, PATH, HOME, TMPDIR) ter-setup otomatis dan output ditangkap via StreamGobbler.
 *
 * Semua pemanggilan bersifat blocking (synchronous) dari thread worker, dengan dukungan
 * pembatalan (cancel) via sinyal SIGKILL ke process.
 */
class ProcessExecutor(private val context: Context) {

    companion object {
        private const val LOG_TAG = "ProcessExecutor"

        /** Batas buffer stdout/stderr agar tidak membengkak di memori (64 KB per stream). */
        private const val MAX_CAPTURE_LENGTH = 64 * 1024

        /** Environment tambahan yang selalu di-set (JVM toolchain). */
        val BASE_ENV: Map<String, String> = mapOf(
            "GRADLE_OPTS" to "-Xmx1280m -XX:MaxMetaspaceSize=384m -XX:+UseG1GC"
        )
    }

    private val cancelledFlag = AtomicBoolean(false)
    private var activeAppShell: AppShell? = null

    /** Interface callback untuk streaming output per baris. */
    interface LineCallback {
        fun onLine(line: String)
        fun onCancelled() {}
    }

    /** Batal semua eksekusi aktif (kirim SIGKILL ke process). */
    fun cancel() {
        cancelledFlag.set(true)
        activeAppShell?.killIfExecuting(context, false)
    }

    /** Reset flag cancel untuk sesi baru. */
    fun reset() {
        cancelledFlag.set(false)
    }

    val isCancelled: Boolean get() = cancelledFlag.get()

    /**
     * Jalankan perintah secara synchronous via AppShell.
     *
     * @param executable path absolut executable (mis. /data/data/com.termux/files/usr/bin/bash)
     * @param arguments argumen (tanpa shell wrapper — AppShell menjalankan executable langsung)
     * @param workingDirectory cwd; null -> default "/"
     * @param stdin stdin opsional (dikirim ke process)
     * @param environment map environment tambahan
     * @param lineCallback callback per baris output (stdout+stderr digabung, live)
     */
    fun execute(
        executable: String,
        arguments: Array<String> = emptyArray(),
        workingDirectory: String? = null,
        stdin: String? = null,
        environment: Map<String, String> = emptyMap(),
        lineCallback: LineCallback? = null,
        timeoutSeconds: Long = 0
    ): CommandResult {
        if (cancelledFlag.get()) {
            return CommandResult(-1, "", "Cancelled before execution", cancelled = true)
        }

        val executionCommand = ExecutionCommand(
            -1, executable, arguments, stdin, workingDirectory ?: "/",
            ExecutionCommand.Runner.APP_SHELL.getName(), false
        )
        executionCommand.commandLabel = executable.substringAfterLast('/')

        // Environment tambahan
        val extraEnv = HashMap<String, String>()
        extraEnv.putAll(BASE_ENV)
        extraEnv.putAll(environment)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val done = CountDownLatch(1)
        val startTime = System.currentTimeMillis()

        val client = object : AppShell.AppShellClient {
            override fun onAppShellExited(appShell: AppShell) {
                val ec = appShell.executionCommand
                val data = ec.resultData
                if (data.stdout.length > 0) stdout.append(data.stdout)
                if (data.stderr.length > 0) stderr.append(data.stderr)
                // Ekstrak baris live dari stdout/stderr
                val merged = buildString {
                    if (data.stdout.length > 0) append(data.stdout)
                    if (data.stderr.length > 0) append(data.stderr)
                }
                if (merged.isNotBlank()) {
                    merged.lines().forEach { line ->
                        if (line.isNotBlank()) lineCallback?.onLine(line)
                    }
                }
                done.countDown()
            }
        }

        // Jalankan synchronous di thread ini (AppShell.execute dengan isSynchronous=false
        // memulai thread sendiri; kita tunggu CountDownLatch agar bisa timeout & cancel).
        val appShell = AppShell.execute(
            context, executionCommand, client,
            TermuxShellEnvironment(), extraEnv, false
        ) ?: run {
            // Gagal start — ambil error dari resultData
            val errMsg = executionCommand.resultData.stderr.toString()
                .ifBlank { executionCommand.resultData.stdout.toString() }
                .ifBlank { "Failed to start process" }
            return CommandResult(-1, "", errMsg)
        }

        activeAppShell = appShell

        // Tunggu selesai (dengan timeout opsional)
        val finished = if (timeoutSeconds > 0) {
            done.await(timeoutSeconds, TimeUnit.SECONDS)
        } else {
            done.await()
        }

        val duration = System.currentTimeMillis() - startTime

        if (!finished) {
            // Timeout — batal
            cancelledFlag.set(true)
            appShell.killIfExecuting(context, false)
            return CommandResult(-1, stdout.toString(), stderr.toString() + "\n[timeout after ${timeoutSeconds}s]",
                cancelled = true, durationMs = duration)
        }

        if (cancelledFlag.get()) {
            return CommandResult(-1, stdout.toString(), stderr.toString(), cancelled = true, durationMs = duration)
        }

        val exitCode = executionCommand.resultData.exitCode ?: -1
        activeAppShell = null
        return CommandResult(exitCode, stdout.toString(), stderr.toString(), durationMs = duration)
    }

    /**
     * Jalankan command via shell (bash -c) agar bisa pakai pipe, redirect, dsb.
     * Ini tetap lewat AppShell (executable = bash, argumen = -c, command).
     */
    fun executeShellCommand(
        command: String,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        lineCallback: LineCallback? = null,
        timeoutSeconds: Long = 0
    ): CommandResult {
        val bashPath = TermuxShellUtils.getShellExecutablePath() ?: "/data/data/com.termux/files/usr/bin/bash"
        return execute(
            bashPath,
            arrayOf("-c", command),
            workingDirectory,
            environment = environment,
            lineCallback = lineCallback,
            timeoutSeconds = timeoutSeconds
        )
    }

    /** Cek keberadaan executable di PREFIX/bin (via test -x). */
    fun isExecutableAvailable(binary: String): Boolean {
        val result = executeShellCommand("command -v $binary >/dev/null 2>&1 && echo FOUND || echo MISSING")
        return result.isSuccess && result.stdout.contains("FOUND")
    }

    /** Baca file teks via shell (cat). */
    fun readFile(path: String): String? {
        val result = executeShellCommand("cat \"$path\" 2>/dev/null")
        return if (result.isSuccess) result.stdout else null
    }

    /** Tulis string ke file (pakai printf agar aman dari karakter spesial). */
    fun writeFile(path: String, content: String) {
        executeShellCommand("mkdir -p \"$(dirname '$path')\" && printf '%s' '${content.replace("'", "'\\''")}' > \"$path\"")
    }
}

/** Helper internal untuk lokasi shell Termux. */
private object TermuxShellUtils {
    fun getShellExecutablePath(): String? {
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/bash",
            "/data/data/com.termux/files/usr/bin/sh"
        )
        for (c in candidates) {
            if (java.io.File(c).exists()) return c
        }
        return null
    }
}
