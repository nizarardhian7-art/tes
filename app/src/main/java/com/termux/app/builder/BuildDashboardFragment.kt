package com.termux.app.builder

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.termux.app.R
import com.termux.builder.model.BuildMode
import com.termux.builder.model.BuildPhase
import com.termux.builder.scan.ProjectScanner

/**
 * Dashboard build native (bukan terminal).
 *
 * Fitur:
 *  - Pilih project dari hasil scan /sdcard (atau last project)
 *  - Pilih mode build (Debug / Release / Clean)
 *  - Progress bar + status + log ringkas
 *  - Tombol Start / Cancel
 *  - Tampilkan hasil build terakhir (path APK)
 */
class BuildDashboardFragment : Fragment() {

    private lateinit var viewModel: BuilderViewModel

    private lateinit var statusText: TextView
    private lateinit var messageText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var logText: TextView
    private lateinit var projectSpinner: android.widget.Spinner
    private lateinit var modeSpinner: android.widget.Spinner
    private lateinit var startButton: Button
    private lateinit var cancelButton: Button

    private val projects = ArrayList<String>()
    private val modes = BuildMode.entries.map { it.name }.toMutableList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_build_dashboard, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.builder_status_text)
        messageText = view.findViewById(R.id.builder_message_text)
        progressBar = view.findViewById(R.id.builder_progress_bar)
        logText = view.findViewById(R.id.builder_log_text)
        projectSpinner = view.findViewById(R.id.builder_project_spinner)
        modeSpinner = view.findViewById(R.id.builder_mode_spinner)
        startButton = view.findViewById(R.id.builder_start_button)
        cancelButton = view.findViewById(R.id.builder_cancel_button)

        viewModel = ViewModelProvider(this)[BuilderViewModel::class.java]

        // Mode spinner
        modeSpinner.adapter = ArrayAdapter(
            requireContext(), android.R.layout.simple_spinner_dropdown_item, modes
        )

        // Scan project di background thread
        Thread {
            val scanResult = ProjectScanner("/sdcard", 4).scan()
            val paths = scanResult.androidProjects.map { it.path }
            activity?.runOnUiThread {
                projects.clear()
                projects.addAll(paths)
                if (projects.isEmpty()) {
                    viewModel.lastProject.value?.let { projects.add(it) }
                }
                projectSpinner.adapter = ArrayAdapter(
                    requireContext(), android.R.layout.simple_spinner_dropdown_item, projects
                )
                if (projects.isEmpty()) {
                    messageText.text = "Tidak ada project Android ditemukan di /sdcard. " +
                        "Salin project ke /sdcard lalu buka lagi."
                }
            }
        }.start()

        // Observasi progress
        viewModel.progress.observe(viewLifecycleOwner) { progress ->
            statusText.text = progress.phase.name.replace('_', ' ')
            messageText.text = progress.message
            progressBar.progress = progress.percent
            if (progress.detail.isNotBlank()) {
                logText.text = progress.detail
            }
        }

        viewModel.isBuilding.observe(viewLifecycleOwner) { building ->
            startButton.isEnabled = !building
            cancelButton.isEnabled = building
        }

        viewModel.result.observe(viewLifecycleOwner) { result ->
            if (result != null) {
                logText.text = result.message + "\n\n" + result.errorSummary.take(400)
            }
        }

        startButton.setOnClickListener {
            val projectPath = projectSpinner.selectedItem?.toString()
                ?: viewModel.lastProject.value
            if (projectPath.isNullOrBlank()) {
                Toast.makeText(requireContext(), "Pilih project dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val mode = try {
                BuildMode.valueOf(modeSpinner.selectedItem.toString())
            } catch (e: Exception) {
                BuildMode.DEBUG_FAST
            }
            logText.text = ""
            viewModel.startBuild(projectPath, mode)
        }

        cancelButton.setOnClickListener {
            viewModel.cancelBuild()
        }

        // Tampilkan last project bila ada
        viewModel.lastProject.observe(viewLifecycleOwner) { last ->
            if (last != null && projects.contains(last)) {
                projectSpinner.setSelection(projects.indexOf(last))
            }
        }
    }
}
