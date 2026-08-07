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
    private var logAppendedSinceScroll = 0

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
        val color = when {
            error -> ContextCompat.getColor(requireContext(), R.color.builder_log_error)
            success -> ContextCompat.getColor(requireContext(), R.color.builder_log_success)
            warning -> ContextCompat.getColor(requireContext(), R.color.builder_log_warning)
            isErrorLine(line) -> ContextCompat.getColor(requireContext(), R.color.builder_log_error)
            isWarningLine(line) -> ContextCompat.getColor(requireContext(), R.color.builder_log_warning)
            isSuccessLine(line) -> ContextCompat.getColor(requireContext(), R.color.builder_log_success)
            else -> ContextCompat.getColor(requireContext(), R.color.builder_log_text)
        }

        val start = logSb.length
        logSb.append(line).append('\n')
        logSb.setSpan(ForegroundColorSpan(color), start, logSb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Batasi ukuran log (jaga memori)
        if (logSb.length > 200_000) {
            logSb.delete(0, logSb.length - 150_000)
        }

        logText.text = logSb

        // Auto-scroll ke bawah
        logAppendedSinceScroll++
        if (logAppendedSinceScroll >= 5) {
            logAppendedSinceScroll = 0
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun isErrorLine(line: String): Boolean =
        line.contains("error:", ignoreCase = true) ||
            line.contains("FAILED", ignoreCase = true) ||
            line.contains("BUILD FAILED") ||
            line.contains("Exception", ignoreCase = true)

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