package com.termux.app.builder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.builder.model.BuildMode
import com.termux.builder.model.BuildPhase
import com.termux.builder.scan.ProjectScanner
import java.io.File

/**
 * Dashboard build native (bukan terminal).
 *
 * v3 — fitur lengkap & UX profesional:
 *  - Card Material + dark theme konsisten
 *  - Mode build via MaterialButtonToggleGroup (Debug / Release / Clean)
 *  - Panel log LIVE seperti terminal (monospace, warna: success/error/warning/info,
 *    auto-scroll, tombol clear)
 *  - Import Backup via SAF (Storage Access Framework) — restore environment
 *  - Export Backup ke /sdcard/BuildOutputs
 *  - Elapsed timer + progress bar
 *  - Snackbar untuk pesan sekali-kali
 */
class BuildDashboardFragment : Fragment() {

    private lateinit var viewModel: BuilderViewModel

    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logClear: ImageButton
    private lateinit var projectSpinner: Spinner
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var startButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var exportButton: MaterialButton

    private val projects = ArrayList<String>()
    private var currentMode: BuildMode = BuildMode.DEBUG_FAST

    private val logSb = SpannableStringBuilder()

    /**
     * v4: auto-scroll log TERKUNCI ke bawah (follow bottom) secara default.
     *  - true  = setiap log baru -> scroll ke bawah (tidak pernah lompat ke atas)
     *  - false = user sedang scroll ke atas manual -> jangan paksa scroll,
     *            dan JANGAN lompat ke atas saat log baru masuk.
     *  Deteksi posisi scroll via OnScrollChangeListener: jika user scroll ke atas
     *  (bukan di bottom), kunci dilepas; begitu kembali ke bottom, kunci aktif lagi.
     */
    private var followBottom = true

    /**
     * v4: posisi awal baris TERAKHIR di logSb — dipakai untuk REPLACE baris
     * progress (mis. "Download 45%") alih-alih menumpuk baris baru terus-menerus
     * (penyebab log "naik-turun" saat progress bar Gradle/Ninja berjalan).
     */
    private var lastLineStart = 0

    private val progressLineRegex = Regex("""\d{1,3}\s*%""")

    private val pickBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val filePath = copyUriToCache(uri)
            if (filePath != null) {
                Toast.makeText(requireContext(), R.string.builder_import_running, Toast.LENGTH_SHORT).show()
                viewModel.importBackup(filePath)
            } else {
                Toast.makeText(requireContext(), R.string.builder_import_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_build_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.builder_status_text)
        messageText = view.findViewById(R.id.builder_message_text)
        elapsedText = view.findViewById(R.id.builder_elapsed_text)
        progressBar = view.findViewById(R.id.builder_progress_bar)
        logText = view.findViewById(R.id.builder_log_text)
        logScroll = view.findViewById(R.id.builder_log_scroll)
        logClear = view.findViewById(R.id.builder_log_clear)

        // v4: deteksi posisi scroll — auto-scroll terkunci ke bawah selama user
        // tidak scroll ke atas. Begitu user kembali ke bottom, kunci aktif lagi.
        logScroll.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val isAtBottom = !logScroll.canScrollVertically(1)
            when {
                // User scroll ke atas (manual) -> lepas kunci follow-bottom
                scrollY < oldScrollY && !isAtBottom -> followBottom = false
                // Kembali ke bottom -> kunci aktif lagi
                isAtBottom -> followBottom = true
            }
        }
        projectSpinner = view.findViewById(R.id.builder_project_spinner)
        modeToggle = view.findViewById(R.id.builder_mode_toggle)
        startButton = view.findViewById(R.id.builder_start_button)
        cancelButton = view.findViewById(R.id.builder_cancel_button)
        importButton = view.findViewById(R.id.builder_import_button)
        exportButton = view.findViewById(R.id.builder_export_button)

        viewModel = ViewModelProvider(this)[BuilderViewModel::class.java]

        // ---- Mode toggle ----
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                currentMode = when (checkedId) {
                    R.id.builder_mode_release -> BuildMode.RELEASE_FAST
                    R.id.builder_mode_clean -> BuildMode.CLEAN_REBUILD_DEBUG
                    else -> BuildMode.DEBUG_FAST
                }
            }
        }
        modeToggle.check(R.id.builder_mode_debug)

        // ---- Scan project di background thread ----
        statusText.text = getString(R.string.builder_scanning)
        Thread {
            val scanResult = ProjectScanner("/sdcard", 4).scan()
            val paths = scanResult.androidProjects.map { it.path }
            activity?.runOnUiThread {
                projects.clear()
                projects.addAll(paths)
                if (projects.isEmpty()) {
                    viewModel.lastProject.value?.let { projects.add(it) }
                }
                projectSpinner.setAdapter(
                    ArrayAdapter(
                        requireContext(), android.R.layout.simple_spinner_dropdown_item, projects
                    )
                )
                if (projects.isEmpty()) {
                    statusText.text = getString(R.string.builder_status_idle)
                    messageText.text = getString(R.string.builder_no_projects)
                }
            }
        }.start()

        // ---- Observasi progress ----
        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            statusText.text = progress.phase.name.replace('_', ' ')
            statusText.setTextColor(statusColor(progress.phase))
            messageText.text = progress.message
            progressBar.setProgress(progress.percent)
        }

        // ---- Observasi log live ----
        viewModel.logLine.observe(viewLifecycleOwner) { line ->
            if (line.isNullOrBlank()) {
                if (line == "") logSb.clear()
                return@observe
            }
            appendLogLine(line)
        }

        viewModel.isBuilding.observe(viewLifecycleOwner) { building ->
            startButton.isEnabled = !building
            cancelButton.isEnabled = building
            importButton.isEnabled = !building
            exportButton.isEnabled = !building
            projectSpinner.isEnabled = !building
            modeToggle.isEnabled = !building
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                // Tampilkan ringkasan hasil di log (warna sesuai status)
                val summary = result.message
                if (result.success) {
                    appendLogLine("\n✔ $summary", success = true)
                    statusText.text = getString(R.string.builder_status_idle)
                } else {
                    appendLogLine("\n✘ $summary", error = true)
                    if (result.errorSummary.isNotBlank()) {
                        appendLogLine(result.errorSummary.take(600), error = true)
                    }
                }
                // Set status final
                val phase = if (result.success) BuildPhase.SUCCESS else
                    if (result.phase == BuildPhase.CANCELLED) BuildPhase.CANCELLED else BuildPhase.FAILED
                statusText.text = phase.name.replace('_', ' ')
                statusText.setTextColor(statusColor(phase))
            }
        }

        // ---- Event message (snackbar) ----
        viewModel.eventMessage.observe(viewLifecycleOwner) { event ->
            if (event != null) {
                Snackbar.make(view, event.first, Snackbar.LENGTH_LONG).show()
            }
        }

        // ---- Elapsed timer ----
        val timer = object : Thread() {
            override fun run() {
                while (!isInterrupted) {
                    activity?.runOnUiThread {
                        elapsedText.text = "${viewModel.elapsedSeconds}s"
                    }
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        }
        timer.start()

        // ---- Actions ----
        startButton.setOnClickListener {
            val projectPath = projectSpinner.selectedItem?.toString()
                ?: viewModel.lastProject.value
            if (projectPath.isNullOrBlank()) {
                Toast.makeText(requireContext(), R.string.builder_start_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            logSb.clear()
            logText.text = ""
            viewModel.resetTimer()
            viewModel.startBuild(projectPath, currentMode)
        }

        cancelButton.setOnClickListener {
            Toast.makeText(requireContext(), R.string.builder_cancel_toast, Toast.LENGTH_SHORT).show()
            viewModel.cancelBuild()
        }

        importButton.setOnClickListener {
            pickBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        exportButton.setOnClickListener {
            Toast.makeText(requireContext(), R.string.builder_export_running, Toast.LENGTH_SHORT).show()
            viewModel.exportBackup()
        }

        logClear.setOnClickListener {
            logSb.clear()
            logText.text = ""
        }

        // Tampilkan last project bila ada
        viewModel.lastProject.observe(viewLifecycleOwner) { last ->
            if (last != null && projects.contains(last)) {
                projectSpinner.setSelection(projects.indexOf(last))
            }
        }
    }

    /** Copy URI hasil SAF ke cache dir agar bisa dibaca sebagai File. */
    private fun copyUriToCache(uri: Uri): String? {
        return try {
            val cacheDir = File(requireContext().cacheDir, "builder-imports")
            cacheDir.mkdirs()
            val dest = File(cacheDir, "backup-${System.currentTimeMillis()}.zip")
            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Append satu baris ke log dengan pewarnaan. */
    private fun appendLogLine(line: String, success: Boolean = false, error: Boolean = false, warning: Boolean = false) {
        // v2: parse prefix terstruktur @@LEVEL@@ (dari BuildLog)
        val parsed = parseLogLevel(line)
        val color = when {
            error -> ContextCompat.getColor(requireContext(), R.color.builder_log_error)
            success -> ContextCompat.getColor(requireContext(), R.color.builder_log_success)
            warning -> ContextCompat.getColor(requireContext(), R.color.builder_log_warning)
            parsed.level == "ERROR" -> ContextCompat.getColor(requireContext(), R.color.builder_log_error)
            parsed.level == "WARN" -> ContextCompat.getColor(requireContext(), R.color.builder_log_warning)
            parsed.level == "OK" -> ContextCompat.getColor(requireContext(), R.color.builder_log_success)
            parsed.level == "SECTION" -> ContextCompat.getColor(requireContext(), R.color.builder_log_section)
            parsed.level == "STEP" -> ContextCompat.getColor(requireContext(), R.color.builder_log_step)
            isErrorLine(line) -> ContextCompat.getColor(requireContext(), R.color.builder_log_error)
            isWarningLine(line) -> ContextCompat.getColor(requireContext(), R.color.builder_log_warning)
            isSuccessLine(line) -> ContextCompat.getColor(requireContext(), R.color.builder_log_success)
            else -> ContextCompat.getColor(requireContext(), R.color.builder_log_text)
        }

        // Section header: teks lebih besar & tebal
        val style = if (parsed.level == "SECTION") android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL

        val start = logSb.length
        logSb.append(parsed.text).append('\n')
        logSb.setSpan(ForegroundColorSpan(color), start, logSb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (style == android.graphics.Typeface.BOLD) {
            logSb.setSpan(android.text.style.StyleSpan(style), start, logSb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        // v4: baris progress (mengandung % / berakhiran %) MENGGANTI baris
        // terakhir, tidak menumpuk — mencegah log "naik-turun" saat progress
        // bar Gradle/Ninja berjalan (mis. "> Task :app:build 45%").
        if (lastLineStart > 0 && isProgressLine(parsed.text)) {
            logSb.replace(lastLineStart, logSb.length, parsed.text + "\n")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                logSb.removeSpan(android.text.style.StyleSpan::class.java)
            }
        } else {
            lastLineStart = start
        }

        // Batasi ukuran log (jaga memori)
        if (logSb.length > 200_000) {
            logSb.delete(0, logSb.length - 150_000)
            lastLineStart = (lastLineStart - (logSb.length - 150_000)).coerceAtLeast(0)
        }

        logText.text = logSb

        // v4: auto-scroll TERKUNCI ke bawah. Selama followBottom=true, setiap
        // log baru menggulir ke bawah — TIDAK PERNAH lompat ke atas. Jika user
        // scroll ke atas (followBottom=false), log baru TIDAK menggerakkan
        // tampilan sama sekali (tidak ada lompatan). Scroll dilakukan via post
        // agar terjadi SETELAH teks di-set.
        if (followBottom) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    /** Parse prefix log terstruktur `@@LEVEL@@text` dari BuildLog. */
    private data class ParsedLogLine(val level: String, val text: String)

    private fun parseLogLevel(line: String): ParsedLogLine {
        if (line.startsWith("@@")) {
            val endIdx = line.indexOf("@@", 2)
            if (endIdx > 2) {
                val level = line.substring(2, endIdx)
                val text = line.substring(endIdx + 2)
                if (level in setOf("SECTION", "STEP", "INFO", "OK", "WARN", "ERROR")) {
                    return ParsedLogLine(level, text)
                }
            }
        }
        return ParsedLogLine("", line)
    }

    private fun isErrorLine(line: String): Boolean =
        line.contains("error:", ignoreCase = true) ||
            line.contains("FAILED", ignoreCase = true) ||
            line.contains("BUILD FAILED") ||
            line.contains("Exception", ignoreCase = true)

    /** true bila baris adalah progress (mengandung persen) — akan REPLACE baris sebelumnya. */
    private fun isProgressLine(line: String): Boolean = progressLineRegex.containsMatchIn(line)

    private fun isWarningLine(line: String): Boolean =
        line.contains("warning:", ignoreCase = true) ||
            line.contains("WARNING", ignoreCase = true)

    private fun isSuccessLine(line: String): Boolean =
        line.contains("BUILD SUCCESSFUL") || line.startsWith("✔")

    private fun statusColor(phase: BuildPhase): Int = when (phase) {
        BuildPhase.SUCCESS -> ContextCompat.getColor(requireContext(), R.color.builder_status_success)
        BuildPhase.FAILED, BuildPhase.CANCELLED -> ContextCompat.getColor(requireContext(), R.color.builder_status_error)
        else -> ContextCompat.getColor(requireContext(), R.color.builder_status_running)
    }
}