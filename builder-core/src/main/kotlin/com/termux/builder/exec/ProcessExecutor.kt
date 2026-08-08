package com.termux.builder.exec

import android.content.Context
import com.termux.builder.model.CommandResult
import com.termux.builder.model.BuilderPaths
import com.termux.shared.logger.Logger
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.shell.command.runner.app.AppShell
import com.termux.shared.shell.command.runner.app.AppShell.AppShellClient
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Eksekutor perintah shell programatik berbasis AppShell (Runner.APP_SHELL) dari termux-shared.
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

        val appShell = AppShell.execute(
            context, executionCommand, client,
            TermuxShellEnvironment(), extraEnv, false
        ) ?: run {
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
                Pair("", fromPos)
            }
        }

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
            val deadline = if (timeoutSeconds > 0) System.currentTimeMillis() + timeoutSeconds * 1000 else Long.MAX_VALUE
            var finished = false
            var executeEnded = false
            while (!finished) {
                val (outDelta, outPos) = readDelta(executionCommand.resultData.stdout, stdoutPos)
                stdoutPos = outPos
                emitDelta(outDelta, toStderr = false)

                val (errDelta, errPos) = readDelta(executionCommand.resultData.stderr, stderrPos)
                stderrPos = errPos
                emitDelta(errDelta, toStderr = true)

                if (done.await(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    executeEnded = true
                    finished = true
                } else if (cancelledFlag.get() || executionCommand.isStateFailed() || executionCommand.hasExecuted()) {
                    executeEnded = true
                    finished = true
                } else if (System.currentTimeMillis() >= deadline) {
                    finished = true
                }
            }

            // v5 FIX: jika process sudah selesai tapi tidak ada trailing newline,
            // sisa buffer partialLine (mis. "error: ..." di akhir tanpa '\n') tidak
            // pernah di-flush oleh loop polling -> output di-swallow (build exit 1
            // tanpa output). Flush ulang sampai partialLine kosong.
            if (executeEnded) {
                while (partialLine.isNotEmpty()) {
                    val (outDelta2, outPos2) = readDelta(executionCommand.resultData.stdout, stdoutPos)
                    stdoutPos = outPos2
                    emitDelta(outDelta2, toStderr = false)
                    val (errDelta2, errPos2) = readDelta(executionCommand.resultData.stderr, stderrPos)
                    stderrPos = errPos2
                    emitDelta(errDelta2, toStderr = true)
                }
            }

            if (partialLine.isNotEmpty()) {
                val line = partialLine.toString()
                if (line.isNotBlank()) lineCallback?.onLine(line)
                stdout.append(line).append('\n')
                partialLine.setLength(0)
            }

            if (stdout.length > MAX_CAPTURE_LENGTH) stdout.delete(0, stdout.length - MAX_CAPTURE_LENGTH)
            if (stderr.length > MAX_CAPTURE_LENGTH) stderr.delete(0, stderr.length - MAX_CAPTURE_LENGTH)

            if (cancelledFlag.get() || executionCommand.isStateFailed()) {
                return CommandResult(
                    -1, stdout.toString(), stderr.toString(),
                    cancelled = cancelledFlag.get(),
                    durationMs = System.currentTimeMillis() - startTime
                )
            }

            if (System.currentTimeMillis() >= deadline && !executionCommand.hasExecuted()) {
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
     * Jalankan command via shell (bash -c).
     * v2: tambah `export PATH` default Termux bila environment tidak mensetnya,
     * dan jangan pakai `set -o pipefail` pada command yang sengaja `|| true`
     * (pipefail mengubah exit code command yang sudah di-guard).
     */
    fun executeShellCommand(
        command: String,
        workingDirectory: String? = null,
        environment: Map<String, String> = emptyMap(),
        lineCallback: LineCallback? = null,
        timeoutSeconds: Long = 0
    ): CommandResult {
        val bashPath = TermuxShellUtils.getShellExecutablePath() ?: "/data/data/com.termux/files/usr/bin/bash"
        // Jangan override PATH bila caller sudah mensetnya (mis. build gradle)
        val safeEnv = if (environment.containsKey("PATH")) {
            environment
        } else {
            val defaultPath = "${BuilderPaths.PREFIX_BIN_DIR}:/data/data/com.termux/files/usr/bin:/usr/bin:/bin"
            environment + ("PATH" to defaultPath)
        }
        val safeCommand = command.trimStart()
        return execute(
            bashPath,
            arrayOf("-c", safeCommand),
            workingDirectory,
            environment = safeEnv,
            lineCallback = lineCallback,
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Cek keberadaan executable langsung ke filesystem Android (bebas bug subshell hash).
     * v2 FIX: cache hasil pengecekan agar tidak menjalankan shell berkali-kali
     * (versi lama memanggil `command -v` untuk tiap binary tiap kali -> lambat
     * dan memicu "download ulang" karena isExecutableAvailable kadang false-negative
     * saat PATH belum di-export).
     */
    private val executableCache = HashMap<String, Boolean>()

    fun isExecutableAvailable(binary: String): Boolean {
        executableCache[binary]?.let { return it }

        val result = if (binary.startsWith("/")) {
            val f = File(binary)
            f.exists() && (f.canExecute() || f.isDirectory)
        } else {
            val prefixFile = File(BuilderPaths.PREFIX_BIN_DIR, binary)
            if (prefixFile.exists()) {
                true
            } else {
                val r = executeShellCommand("command -v $binary 2>/dev/null")
                r.isSuccess && r.stdout.trim().isNotBlank()
            }
        }
        executableCache[binary] = result
        return result
    }

    /** Cek daftar binary sekaligus, kembalikan yang HILANG saja. */
    fun findMissingBinaries(binaries: List<String>): List<String> {
        return binaries.filter { !isExecutableAvailable(it) }
    }

    fun readFile(path: String): String? {
        val result = executeShellCommand("cat \"$path\" 2>/dev/null")
        return if (result.isSuccess) result.stdout else null
    }

    fun writeFile(path: String, content: String) {
        executeShellCommand("mkdir -p \"$(dirname '$path')\" && printf '%s' '${content.replace("'", "'\\''")}' > \"$path\"")
    }
}

private object TermuxShellUtils {
    fun getShellExecutablePath(): String? {
        val candidates = listOf(
            "/data/data/com.termux/files/usr/bin/bash",
            "/data/data/com.termux/files/usr/bin/sh"
        )
        for (c in candidates) {
            if (File(c).exists()) return c
        }
        return null
    }
}