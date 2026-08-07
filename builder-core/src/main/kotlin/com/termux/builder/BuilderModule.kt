package com.termux.builder

import android.content.Context
import com.termux.builder.model.BuilderPaths
import java.io.File

/**
 * Inisialisasi modul builder (dipanggil dari TermuxApplication.onCreate).
 *
 * Membuat direktori state engine dan memastikan path dasar tersedia.
 */
object BuilderModule {

    private const val LOG_TAG = "BuilderModule"

    /**
     * Init modul builder.
     * @param context application context
     * @param filesAccessible true bila direktori files Termux dapat diakses
     */
    @JvmStatic
    fun init(context: Context, filesAccessible: Boolean) {
        if (!filesAccessible) return

        try {
            // State dir builder
            File(BuilderPaths.APP_STATE_DIR).mkdirs()
            File(BuilderPaths.PATCH_BACKUP_DIR).mkdirs()

            // Workspace & output
            File(BuilderPaths.DEFAULT_WORKSPACE_DIR).mkdirs()
            File(BuilderPaths.DEFAULT_OUTPUT_DIR).mkdirs()
        } catch (e: Exception) {
            com.termux.shared.logger.Logger.logError(LOG_TAG, "Failed to init builder state dirs: " + e.message)
        }
    }
}
