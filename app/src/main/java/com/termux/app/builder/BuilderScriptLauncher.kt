package com.termux.app.builder

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.termux.R
import com.termux.builder.model.BuildMode
import java.io.File

/**
 * Launcher script `builder_core.sh` di terminal Termux asli (v5).
 *
 * Arsitektur v5:
 *  - UI 100% Kotlin, TIDAK ada log live di app.
 *  - Semua logic berat (build/import/export/setup/native) dijalankan oleh
 *    `builder_core.sh` — non-interaktif wrapper dari build_ref.sh (build.sh
 *    v12.0 yang sudah terbukti berhasil di device user). App TIDAK meniru
 *    logikanya di Kotlin; cukup memanggil script lewat RUN_COMMAND intent.
 *  - Script diekstrak sekali ke `filesDir/scripts/builder_core.sh` dari
 *    res/raw (sumber tunggal yang sama dengan yang dibundel di APK).
 *  - Runner = TERMINAL_SESSION (foreground) -> output tampil di terminal
 *    Termux asli. TermuxActivity otomatis finish() saat session terakhir
 *    selesai -> kembali ke APK Builder.
 *
 * Intent yang dibangun mengikuti kontrak ACTION_SERVICE_EXECUTE pada
 * TermuxConstants (lihat juga TermuxService.actionServiceExecute).
 */
object BuilderScriptLauncher {

    private const val LOG_TAG = "BuilderScriptLauncher"

    /** Nama file script di res/raw dan di filesDir. */
    const val SCRIPT_NAME = "builder_core.sh"

    /** Action untuk meminta TermuxService menjalankan command (RUN_COMMAND). */
    private const val ACTION_SERVICE_EXECUTE = "com.termux.service_execute"

    /** Runner foreground (output di terminal session, bukan background task). */
    private const val RUNNER_TERMINAL_SESSION = "terminal-session"

    /** Value session action: switch ke session baru & buka TermuxActivity. */
    private const val VALUE_SESSION_ACTION_SWITCH_AND_OPEN = 0

    /** Paket Termux (constanta di TermuxConstants). */
    private const val TERMUX_PACKAGE = "com.termux"

    /**
     * Ekstrak `builder_core.sh` dari res/raw ke filesDir bila belum ada
     * (atau versi asset lebih baru). Kembalikan path absolut script.
     */
    fun ensureScript(context: Context): String {
        val scriptFile = File(context.filesDir, "scripts/$SCRIPT_NAME")
        scriptFile.parentFile?.mkdirs()

        val assetLastModified = context.resources.openRawResource(R.raw.builder_core).use { }
        // openRawResource() di atas hanya memvalidasi resource ada. Tulis ulang
        // bila file belum ada. (Asset resource tidak punya timestamp lintas
        // versi yang reliabel; APK baru selalu punya file baru.)
        if (!scriptFile.exists() || scriptFile.length() == 0L) {
            context.resources.openRawResource(R.raw.builder_core).use { input ->
                scriptFile.outputStream().use { output -> input.copyTo(output) }
            }
            scriptFile.setExecutable(true, false)
        }
        return scriptFile.absolutePath
    }

    /** Hitung argumen command sesuai mode. */
    private fun buildArgs(cmd: String, arg1: String?, arg2: String?): Array<String> {
        return when (cmd) {
            "build" -> listOfNotNull("build", arg1, arg2 ?: "debug").toTypedArray()
            "import" -> listOfNotNull("import", arg1).toTypedArray()
            "export" -> listOfNotNull("export", arg1).toTypedArray()
            "setup" -> arrayOf("setup")
            "native" -> listOfNotNull("native", arg1).toTypedArray()
            else -> arrayOf(cmd)
        }
    }

    /** Launch command builder di terminal Termux (foreground session). */
    fun launch(
        context: Context,
        command: String,
        arg1: String? = null,
        arg2: String? = null,
        label: String = "APK Builder"
    ) {
        val scriptPath = ensureScript(context)
        val args = buildArgs(command, arg1, arg2)

        val intent = Intent(ACTION_SERVICE_EXECUTE).apply {
            data = Uri.parse("file://$scriptPath")
            putExtra("com.termux.execute.runner", RUNNER_TERMINAL_SESSION)
            putExtra("com.termux.execute.session_action", VALUE_SESSION_ACTION_SWITCH_AND_OPEN)
            putExtra("com.termux.execute.shell_name", SCRIPT_NAME)
            putExtra("com.termux.execute.command_label", label)
            putExtra("com.termux.execute.arguments", args)
        }

        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            // Fallback: TermuxService biasanya sudah jalan; startService biasa.
            try {
                context.startService(intent)
            } catch (e2: Exception) {
                com.termux.shared.logger.Logger.logError(LOG_TAG, "Failed to launch builder script: " + e2.message)
            }
        }
    }

    /** Build project (debug/release/clean). */
    fun launchBuild(context: Context, projectPath: String, mode: BuildMode) {
        val typeArg = when (mode) {
            BuildMode.RELEASE_FAST -> "release"
            BuildMode.CLEAN_REBUILD_DEBUG -> "clean"
            else -> "debug"
        }
        launch(context, "build", projectPath, typeArg, label = "Build ${File(projectPath).name}")
    }

    /** Import backup environment. */
    fun launchImport(context: Context, zipPath: String) {
        launch(context, "import", zipPath, label = "Import Environment Backup")
    }

    /** Export backup environment. */
    fun launchExport(context: Context) {
        launch(context, "export", label = "Export Environment Backup")
    }

    /** Auto-setup toolchain (idempotent, resume-safe). */
    fun launchSetup(context: Context) {
        launch(context, "setup", label = "Toolchain Setup")
    }

    /** Build project native (CMake/NDK). */
    fun launchNative(context: Context, projectPath: String) {
        launch(context, "native", projectPath, label = "Build Native ${File(projectPath).name}")
    }
}
