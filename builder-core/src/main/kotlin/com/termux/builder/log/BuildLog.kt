package com.termux.builder.log

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Format log terstruktur & konsisten untuk seluruh builder.
 *
 * Masalah di versi lama: pesan progress dikirim sebagai string polos dari banyak
 * tempat (ToolchainManager, BackupManager, BuildOrchestrator, subprocess) tanpa
 * struktur — hasilnya log "naik-turun", sulit dibedakan mana fase mana, tanpa
 * timestamp, tanpa section header.
 *
 * Solusi: semua pesan lewat [BuildLog] dan diberi [LogLevel]. Line yang dikirim
 * ke UI memakai format:  `@@LEVEL@@text`  sehingga [LogLevel] bisa di-parse
 * fragment untuk pewarnaan & gaya (section header, indent, timestamp).
 *
 * Format teks yang ditampilkan (mis. pada error summary):
 *   [12:04:33] === MEMULAI BUILD ===
 *   [12:04:34]   (1/7) Setup toolchain
 *   [12:04:35]   OK  NDK sudah terpasang (29.0.14206865)
 */
object BuildLog {

    private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)

    /** Prefix yang dipakai pada detail log untuk UI (parsed di fragment). */
    const val PREFIX_SEPARATOR = "@@"

    /** Waktu sekarang dalam format HH:mm:ss. */
    fun timestamp(): String = TIME_FORMAT.format(Date())

    /**
     * Bangun line log ber-prefix yang siap dikirim via BuildProgress.detail.
     * @param level level log (mementukan warna/gaya di UI)
     * @param text isi pesan
     * @param indent berapa level indent (tiap level = 2 spasi)
     */
    fun line(level: LogLevel, text: String, indent: Int = 0): String {
        val pad = "  ".repeat(indent.coerceIn(0, 6))
        return "$PREFIX_SEPARATOR${level.name}$PREFIX_SEPARATOR$pad$text"
    }

    /** Section header — garis pemisah tebal. */
    fun section(title: String): String {
        val bar = "=".repeat(64)
        return line(LogLevel.SECTION, "$bar\n$title\n$bar", indent = 0)
    }

    /** Header fase build (dipakai BuildOrchestrator tiap pindah fase). */
    fun phase(title: String, stepInfo: String? = null): String {
        val sub = if (stepInfo != null) "\n    $stepInfo" else ""
        return line(LogLevel.SECTION, "\u25B6 $title$sub")
    }

    /** Langkah bernomor (mis. "1/7"). */
    fun step(stepNo: Int, total: Int, title: String): String =
        line(LogLevel.STEP, "($stepNo/$total) $title")

    /** Info biasa. */
    fun info(text: String, indent: Int = 0): String =
        line(LogLevel.INFO, text, indent)

    /** Pesan sukses. */
    fun ok(text: String, indent: Int = 0): String =
        line(LogLevel.OK, text, indent)

    /** Peringatan. */
    fun warn(text: String, indent: Int = 0): String =
        line(LogLevel.WARN, text, indent)

    /** Error. */
    fun error(text: String, indent: Int = 0): String =
        line(LogLevel.ERROR, text, indent)

    /** Baris mentah dari subprocess (tanpa prefix — diwarnai heuristik di UI). */
    fun raw(text: String): String = text

    /** Format durasi dalam detik (untuk ringkasan akhir). */
    fun duration(seconds: Long): String {
        if (seconds < 60) return "${seconds}s"
        val m = seconds / 60
        val s = seconds % 60
        return if (m < 60) "${m}m ${s}s" else "${m / 60}h ${m % 60}m ${s}s"
    }
}

/**
 * Level log. Dikirim sebagai prefix `@@LEVEL@@` pada BuildProgress.detail.
 * UI (BuildDashboardFragment) memetakan level -> warna & gaya.
 */
enum class LogLevel {
    /** Section header (garis tebal, aksen). */
    SECTION,

    /** Langkah bernomor. */
    STEP,

    /** Info biasa. */
    INFO,

    /** Sukses. */
    OK,

    /** Peringatan. */
    WARN,

    /** Error. */
    ERROR
}
