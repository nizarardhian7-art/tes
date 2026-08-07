package com.termux.builder.exec

import android.content.Context
import com.termux.builder.model.CommandResult
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.shell.command.runner.app.AppShell.AppShellClient
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import java.util.Collections
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
 * **Streaming log real-time:** karena StreamGobbler menulis ke
 * [ExecutionCommand.resultData].stdout/stderr selama proses berjalan, thread ini
 * melakukan polling delta setiap 100 ms dan meneruskannya per-baris ke [LineCallback].
 * UI dengan demikian melihat output (download progress, apt, gradle, dll) LIVE,
 * bukan hanya saat proses selesai.
 *
 * **Pembatalan yang benar:** [killIfExecuting] men-set state failed pada
 * ExecutionCommand sehingga `onAppShellExited` TIDAK dipanggil (lihat AppShell.run).
 * Karena itu loop polling TIDAK boleh hanya mengandalkan CountDownLatch — ia juga
 * memeriksa `isStateFailed()` / `hasExecuted()` agar tidak menggantung selamanya
 * setelah [cancel].
 */
class ProcessExecutor(private val context: Context) {

    companion object {
        private const val LOG_TAG = "ProcessExecutor"

        /** Batas buffer stdout/stderr agar tidak membengkak di memori (64 KB per stream). */
        private const val MAX_CAPTURE_LENGTH = 64 * 1024

        /** Interval polling untuk streaming log real-time (ms). */
        private const val POLL_INTERVAL_MS = 100L

        /** Environment tambahan yang selalu di-set (JVM toolchain). */
        val BASE_ENV: Map<String, String> = mapOf(
            "GRADLE_OPTS" to "-Xmx1280m -XX:MaxMetaspaceSize=384m -XX:+UseG1GC"
        )
    }

    private val cancelledFlag = AtomicBoolean(false)
    private val activeAppShells = Collections.synchronizedSet(
        Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<AppShell, Boolean>())
    )

    /** Interface callback untuk streaming output per baris (live). */
    interface LineCallback {
        fun onLine(line: String)
        fun onCancelled() {}
    }

    /** Batal semua eksekusi aktif (kirim SIGKILL ke process). */
    fun cancel() {
        cancelledFlag.set(true)
        synchronized(activeAppShells) {
            activeAppShells.forEach { it.killIfExecuting(context, false) }
            activeAppShells.clear()
        }
    }

    /** Reset flag cancel untuk sesi baru. */
    fun reset() {
        cancelledFlag.set(false)
    }

    val isCancelled: Boolean get() = cancelledFlag.get()

    /**
     * Jalankan perintah secara synchronous via AppShell (dengan streaming log live).
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

        val done = CountDownLatch(1)
        val startTime = System.currentTimeMillis()

        val client = object : AppShellClient {
            override fun onAppShellExited(appShell: AppShell) {
                done.countDown()
            }
        }

        // Jalankan asynchronous (AppShell memulai thread sendiri); kita polling.
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

        activeAppShells.add(appShell)

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var stdoutPos = 0
        var stderrPos = 0
        val partialLine = StringBuilder()

        // Reader aman: substring delta dari StringBuilder hasil gobbler.
        fun readDelta(builder: StringBuilder, fromPos: Int): Pair<String, Int> {
            return try {
                synchronized(builder) {
                    val len = builder.length
                    if (len > fromPos) {
                        Pair(builder.substring(fromPos, len), len)
                    } else {
                        Pair("", len)
                    }
                }
            } catch (e: Exception) {
                // Race dengan resize StringBuilder — coba lagi siklus berikutnya
                Pair("", fromPos)
            }
        }

        // Emit teks delta sebagai baris-baris (pecah \n, buffer partial line).
        fun emitDelta(text: String, toStderr: Boolean) {
            if (text.isEmpty()) return
            val merged = StringBuilder(partialLine)
            merged.append(text)
            val full = merged.toString()
            val lines = full.split('\n')
            partialLine.setLength(0)
            for (i in 0 until lines.size - 1) {
                val line = lines[i]
                if (toStderr) stderr.append(line).append('\n') else stdout.append(line).append('\n')
                if (line.isNotBlank()) lineCallback?.onLine(line)
            }
            val tail = lines.last()
            if (tail.isNotEmpty()) partialLine.append(tail)
        }

        try {
            // ---- Loop polling: tunggu selesai / timeout / cancel, sambil stream log ----
            val deadline = if (timeoutSeconds > 0) System.currentTimeMillis() + timeoutSeconds * 1000 else Long.MAX_VALUE
            var finished = false
            while (!finished) {
                // 1) Streaming delta stdout & stderr
                val (outDelta, outPos) = readDelta(executionCommand.resultData.stdout, stdoutPos)
                stdoutPos = outPos
                emitDelta(outDelta, toStderr = false)

                val (errDelta, errPos) = readDelta(executionCommand.resultData.stderr, stderrPos)
                stderrPos = errPos
                emitDelta(errDelta, toStderr = true)

                // 2) Cek kondisi selesai
                if (done.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    finished = true
                } else if (cancelledFlag.get() || executionCommand.isStateFailed() || executionCommand.hasExecuted()) {
                    // killIfExecuting men-set state failed tanpa memanggil onAppShellExited,
                    // jadi kita harus keluar dari loop sendiri.
                    finished = true
                } else if (System.currentTimeMillis() >= deadline) {
                    finished = true
                }
            }

            // Flush sisa partial line
            if (partialLine.isNotEmpty()) {
                val line = partialLine.toString()
                if (line.isNotBlank()) lineCallback?.onLine(line)
                stdout.append(line).append('\n')
                partialLine.setLength(0)
            }

            // Batasi buffer (jaga memori)
            if (stdout.length > MAX_CAPTURE_LENGTH) stdout.delete(0, stdout.length - MAX_CAPTURE_LENGTH)
            if (stderr.length > MAX_CAPTURE_LENGTH) stderr.delete(0, stderr.length - MAX_CAPTURE_LENGTH)

            // ---- Tentukan hasil ----
            if (cancelledFlag.get() || executionCommand.isStateFailed()) {
                return CommandResult(
                    -1, stdout.toString(), stderr.toString(),
                    cancelled = cancelledFlag.get(),
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            if (System.currentTimeMillis() >= deadline && !executionCommand.hasExecuted()) {
                // Timeout — batal paksa
                appShell.killIfExecuting(context, false)
                return CommandResult(
                    -1, stdout.toString(), stderr.toString() + "\n[timeout after ${timeoutSeconds}s]",
                    cancelled = true, durationMs = System.currentTimeMillis() - startTime
                )
            }

            val exitCode = executionCommand.resultData.exitCode ?: -1
            return CommandResult(
                exitCode, stdout.toString(), stderr.toString(),
                durationMs = System.currentTimeMillis() - startTime
            )
        } finally {
            activeAppShells.remove(appShell)
        }
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
