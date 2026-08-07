package com.termux.builder.log

import com.termux.builder.model.ErrorSummary

/**
 * Parser output build (Gradle / CMake / Ninja).
 *
 * v2 — perbaikan:
 *  - Paham prefix `@@LEVEL@@` dari [BuildLog] (section/step/ok/warn/error).
 *  - Handle `\r` (progress bar Gradle/Ninja): baris dengan carriage-return
 *    di-refresh (bukan ditumpuk), sehingga log tidak "naik-turun".
 *  - Klasifikasi lebih akurat: `error:` / `BUILD FAILED` / `FAILURE:` = ERROR;
 *    `warning:` = WARNING; task Gradle = TASK; `%` = PROGRESS.
 */
class LogStreamParser {

    /** Callback untuk setiap line yang diproses. */
    interface LineHandler {
        fun onLine(line: String)
    }

    companion object {
        // v2: tambah pola error compiler Kotlin: "e: file.kt:25: Unresolved reference: ..."
        // dan Groovy/Gradle: "What went wrong", "Execution failed for task"
        private val ERROR_RE = Regex("(?i).*(\\berror\\b|error:|\\bfailed\\b|\\bfailure\\b|BUILD FAILED|FAILURE:|exception|what went wrong|execution failed for task).*")
        private val KOTLIN_ERROR_RE = Regex("(?m)^e:\\s+.*")
        private val WARNING_RE = Regex("(?i).*(warning:|WARNING).*")
        private val KOTLIN_WARNING_RE = Regex("(?m)^w:\\s+.*")
        private val GRADLE_TASK_RE = Regex("^>\\s*Task\\s+([^\\s]+)")
        private val PERCENT_RE = Regex("(\\d{1,3})%")

        private val LEVEL_PREFIX_RE = Regex("^@@(SECTION|STEP|INFO|OK|WARN|ERROR)@@")

        private const val MAX_ERROR_LINES = 25
        private const val MAX_TAIL_LENGTH = 6000
    }

    private val errorLines = ArrayDeque<String>()
    private val logTail = StringBuilder()
    private var lastTask: String? = null
    private var lastProgressPercent = 0

    /** Proses satu baris output; mengembalikan klasifikasi. */
    fun processLine(line: String): ParsedLine {
        val kind = classify(line)

        if (kind == LineKind.ERROR) {
            if (errorLines.size >= MAX_ERROR_LINES) errorLines.removeFirst()
            errorLines.addLast(line)
        }

        if (line.isNotBlank()) {
            if (logTail.length > MAX_TAIL_LENGTH) {
                logTail.delete(0, logTail.length / 2)
            }
            logTail.append(line).append('\n')
        }

        when (kind) {
            LineKind.TASK -> {
                GRADLE_TASK_RE.find(line)?.let { lastTask = it.groupValues[1] }
            }
            LineKind.PROGRESS -> {
                PERCENT_RE.find(line)?.let {
                    lastProgressPercent = it.groupValues[1].toIntOrNull() ?: lastProgressPercent
                }
            }
            else -> {}
        }

        return ParsedLine(kind, line)
    }

    /**
     * Klasifikasi baris.
     * Prioritas: prefix level eksplisit (dari BuildLog) > pola Gradle/error/warning.
     */
    fun classify(line: String): LineKind {
        if (line.isBlank()) return LineKind.EMPTY

        // Prefix eksplisit dari BuildLog menang
        LEVEL_PREFIX_RE.find(line)?.let {
            return when (it.groupValues[1]) {
                "ERROR" -> LineKind.ERROR
                "WARN" -> LineKind.WARNING
                "OK" -> LineKind.SUCCESS
                "SECTION", "STEP" -> LineKind.SECTION
                else -> LineKind.INFO
            }
        }

        return when {
            KOTLIN_ERROR_RE.matches(line.trim()) -> LineKind.ERROR
            KOTLIN_WARNING_RE.matches(line.trim()) -> LineKind.WARNING
            GRADLE_TASK_RE.matches(line.trim()) -> LineKind.TASK
            line.contains("BUILD SUCCESSFUL") -> LineKind.SUCCESS
            line.contains("BUILD FAILED") -> LineKind.FAILED
            ERROR_RE.matches(line) -> LineKind.ERROR
            WARNING_RE.matches(line) -> LineKind.WARNING
            PERCENT_RE.containsMatchIn(line) -> LineKind.PROGRESS
            else -> LineKind.INFO
        }
    }

    /**
     * Bersihkan baris dari carriage-return progress (mis.
     * "\rDownload 45%" -> "Download 45%").
     */
    fun cleanLine(raw: String): String {
        if (raw.isEmpty()) return raw
        // Jika ada \r, ambil segmen terakhir (state terbaru progress bar)
        val lastCr = raw.lastIndexOf('\r')
        return if (lastCr >= 0) raw.substring(lastCr + 1).trim() else raw.trim()
    }

    fun getLastTask(): String? = lastTask

    fun getLastProgressPercent(): Int = lastProgressPercent

    fun buildErrorSummary(): ErrorSummary {
        val rawTail = logTail.toString().takeLast(MAX_TAIL_LENGTH)
        return ErrorSummary(errorLines.toList(), rawTail)
    }

    fun reset() {
        errorLines.clear()
        logTail.setLength(0)
        lastTask = null
        lastProgressPercent = 0
    }
}

enum class LineKind {
    ERROR, WARNING, INFO, TASK, PROGRESS, SUCCESS, FAILED, SECTION, EMPTY
}

data class ParsedLine(
    val kind: LineKind,
    val text: String
)