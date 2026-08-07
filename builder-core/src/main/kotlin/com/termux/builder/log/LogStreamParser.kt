package com.termux.builder.log

import com.termux.builder.model.ErrorSummary

/**
 * Parser output build (Gradle / CMake / Ninja).
 *
 * Menangkap sinyal penting dari aliran output teks:
 *  - Error (error:, FAILED, exception, failure)
 *  - Warning (warning:, WARNING)
 *  - Task Gradle selesai (> Task :app:assembleDebug)
 *  - Progress build (persen untuk CMake/Ninja)
 */
class LogStreamParser {

    /** Callback untuk setiap line yang diproses. */
    interface LineHandler {
        fun onLine(line: String)
    }

    companion object {
        private val ERROR_RE = Regex("(?i).*(error:|\\bfailed\\b|\\bfailure\\b|exception|FAILED|BUILD FAILED).*")
        private val WARNING_RE = Regex("(?i).*(warning:|WARNING).*")
        private val GRADLE_TASK_RE = Regex("^>\\s*Task\\s+([^\\s]+)")
        private val GRADLE_PROGRESS_RE = Regex("^>\\s+([^\\n]+)$")
        private val PERCENT_RE = Regex("(\\d{1,3})%")

        private const val MAX_ERROR_LINES = 20
        private const val MAX_TAIL_LENGTH = 4000
    }

    private val errorLines = ArrayDeque<String>()
    private val logTail = StringBuilder()
    private var lastTask: String? = null
    private var lastProgressPercent = 0

    /** Proses satu baris output; mengembalikan ringkasan bila baris adalah error. */
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

    fun classify(line: String): LineKind {
        return when {
            line.isBlank() -> LineKind.EMPTY
            GRADLE_TASK_RE.matches(line.trim()) -> LineKind.TASK
            line.contains("BUILD SUCCESSFUL") -> LineKind.SUCCESS
            line.contains("BUILD FAILED") -> LineKind.FAILED
            ERROR_RE.matches(line) -> LineKind.ERROR
            WARNING_RE.matches(line) -> LineKind.WARNING
            PERCENT_RE.containsMatchIn(line) -> LineKind.PROGRESS
            else -> LineKind.INFO
        }
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
    ERROR, WARNING, INFO, TASK, PROGRESS, SUCCESS, FAILED, EMPTY
}

data class ParsedLine(
    val kind: LineKind,
    val text: String
)
