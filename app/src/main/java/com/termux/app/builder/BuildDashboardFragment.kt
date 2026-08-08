package com.termux.app.builder

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.snackbar.Snackbar
import com.termux.R
import com.termux.builder.model.BuildMode
import com.termux.builder.scan.ProjectScanner
import java.io.File

/**
 * Dashboard build (v5) — UI 100% Kotlin, TANPA log live di app.
 *
 * Perubahan arsitektur v5:
 *  - Panel log live + progress streaming DIHAPUS. Saat user menekan
 *    Build/Import/Export/Setup/Native, app meluncurkan `builder_core.sh`
 *    di terminal Termux asli (Runner TERMINAL_SESSION). Output terminal asli
 *    yang tampil (auto-scroll bawaan terminal, tanpa lompatan).
 *  - Setelah script selesai, TermuxActivity otomatis finish() (session
 *    terakhir selesai) -> kembali ke app ini.
 *  - Tombol Batal tidak lagi menghentikan proses via service; user bisa
 *    menghentikan session langsung di terminal (Ctrl-C) atau menutupnya.
 */
class BuildDashboardFragment : Fragment() {

    private lateinit var viewModel: BuilderViewModel

    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private lateinit var projectSpinner: Spinner
    private lateinit var modeToggle: MaterialButtonToggleGroup
    private lateinit var startButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var importButton: MaterialButton
    private lateinit var exportButton: MaterialButton

    private val projects = ArrayList<String>()
    private var currentMode: BuildMode = BuildMode.DEBUG_FAST

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

        // ---- Observasi status running ----
        viewModel.isRunning.observe(viewLifecycleOwner) { running ->
            startButton.isEnabled = !running
            cancelButton.isEnabled = running
            importButton.isEnabled = !running
            exportButton.isEnabled = !running
            projectSpinner.isEnabled = !running
            modeToggle.isEnabled = !running
            if (running) {
                statusText.text = getString(R.string.builder_status_running)
                messageText.text = getString(R.string.builder_message_terminal)
            }
        }

        // ---- Event message (snackbar) ----
        viewModel.eventMessage.observe(viewLifecycleOwner) { event ->
            if (event != null) {
                Snackbar.make(view, event.first, Snackbar.LENGTH_LONG).show()
            }
        }

        // ---- Actions ----
        startButton.setOnClickListener {
            val projectPath = projectSpinner.selectedItem?.toString()
                ?: viewModel.lastProject.value
            if (projectPath.isNullOrBlank()) {
                Toast.makeText(requireContext(), R.string.builder_start_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(requireContext(), R.string.builder_terminal_launch, Toast.LENGTH_SHORT).show()
            viewModel.startBuild(projectPath, currentMode)
        }

        cancelButton.setOnClickListener {
            // Operasi berjalan di terminal Termux; user menghentikannya di sana
            // (Ctrl-C / tutup session). Tombol ini memberi petunjuk.
            Toast.makeText(requireContext(), R.string.builder_cancel_hint, Toast.LENGTH_LONG).show()
        }

        importButton.setOnClickListener {
            pickBackupLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }

        exportButton.setOnClickListener {
            Toast.makeText(requireContext(), R.string.builder_export_running, Toast.LENGTH_SHORT).show()
            viewModel.exportBackup()
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
}
