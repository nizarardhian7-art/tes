package com.termux.app.builder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.termux.app.R
import com.termux.builder.exec.ProcessExecutor
import com.termux.builder.model.BuildConfig
import com.termux.builder.model.BuildMode
import com.termux.builder.model.BuildPhase
import com.termux.builder.model.BuildProgress
import com.termux.builder.model.BuildResult
import com.termux.builder.orchestrator.BuildOrchestrator
import com.termux.builder.repo.BuildRepository
import java.util.concurrent.Executors

/**
 * Foreground Service yang menjalankan BuildOrchestrator di background.
 *
 * - Memakai WAKE_LOCK agar device tidak tidur selama build (build.sh memakai termux-wake-lock)
 * - Notifikasi progress real-time
 * - Aman terhadap rotation (build tetap jalan walau Activity di-destroy)
 */
class BuildForegroundService : Service() {

    companion object {
        private const val LOG_TAG = "BuildForegroundService"

        const val CHANNEL_ID = "termux_builder_build"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START_BUILD = "com.termux.builder.action.START_BUILD"
        const val ACTION_CANCEL_BUILD = "com.termux.builder.action.CANCEL_BUILD"
        const val ACTION_STOP = "com.termux.builder.action.STOP"

        const val EXTRA_PROJECT_PATH = "project_path"
        const val EXTRA_BUILD_MODE = "build_mode"

        /** Event bus sederhana: listener mendengarkan progress build. */
        @Volatile
        var listener: ((BuildProgress) -> Unit)? = null

        @Volatile
        var lastResult: BuildResult? = null

        fun startBuild(context: Context, projectPath: String, mode: BuildMode) {
            val intent = Intent(context, BuildForegroundService::class.java).apply {
                action = ACTION_START_BUILD
                putExtra(EXTRA_PROJECT_PATH, projectPath)
                putExtra(EXTRA_BUILD_MODE, mode.name)
            }
            context.startForegroundService(intent)
        }

        fun cancelBuild(context: Context) {
            val intent = Intent(context, BuildForegroundService::class.java).apply {
                action = ACTION_CANCEL_BUILD
            }
            context.startService(intent)
        }

        fun isBuilding(): Boolean {
            return lastResult == null
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executorService = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    private var orchestrator: BuildOrchestrator? = null
    private var notificationManager: NotificationManager? = null
    private var currentProgress = BuildProgress(BuildPhase.IDLE, "Idle")

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()

        // Wake lock (termux-wake-lock equivalent)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TermuxBuilder:Build").apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // max 12 jam
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BUILD -> {
                val projectPath = intent.getStringExtra(EXTRA_PROJECT_PATH)
                val modeName = intent.getStringExtra(EXTRA_BUILD_MODE) ?: BuildMode.DEBUG_FAST.name
                val mode = try { BuildMode.valueOf(modeName) } catch (e: Exception) { BuildMode.DEBUG_FAST }

                if (projectPath != null) {
                    startForeground(NOTIFICATION_ID, buildNotification(currentProgress))
                    startBuildInternal(projectPath, mode)
                }
            }
            ACTION_CANCEL_BUILD -> {
                orchestrator?.cancel()
                updateNotification(BuildProgress(BuildPhase.CANCELLED, "Membatalkan build...", 0))
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startBuildInternal(projectPath: String, mode: BuildMode) {
        // Simpan last project
        BuildRepository(this).saveLastProject(projectPath)

        val config = BuildConfig(projectPath = projectPath, mode = mode)
        lastResult = null

        executorService.execute {
            val executor = ProcessExecutor(this)
            orchestrator = BuildOrchestrator(this, executor) { progress ->
                currentProgress = progress
                updateNotification(progress)
                mainHandler.post {
                    listener?.invoke(progress)
                }
            }

            val result = orchestrator!!.buildApk(config)
            lastResult = result
            currentProgress = BuildProgress(
                if (result.success) BuildPhase.SUCCESS else
                    if (result.phase == BuildPhase.CANCELLED) BuildPhase.CANCELLED else BuildPhase.FAILED,
                result.message,
                if (result.success) 100 else 0
            )
            updateNotification(currentProgress)
            mainHandler.post {
                listener?.invoke(currentProgress)
                listener = null
            }
            // Selesai — berhenti service setelah notifikasi final
            mainHandler.postDelayed({
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }, 3000)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Build APK",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Progress build APK TermuxMod"
                setShowBadge(false)
            }
            notificationManager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(progress: BuildProgress): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, BuilderMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, BuildForegroundService::class.java).apply { action = ACTION_CANCEL_BUILD },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val phaseLabel = when (progress.phase) {
            BuildPhase.SCANNING -> "Scanning project"
            BuildPhase.TOOLCHAIN_SETUP -> "Setup toolchain"
            BuildPhase.SYNCING -> "Sinkronisasi workspace"
            BuildPhase.PATCHING -> "Patch Gradle"
            BuildPhase.BUILDING -> "Membangun APK"
            BuildPhase.COPYING -> "Menyalin APK"
            BuildPhase.SUCCESS -> "Build sukses"
            BuildPhase.FAILED -> "Build gagal"
            BuildPhase.CANCELLED -> "Dibatalkan"
            else -> "Idle"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("TermuxMod Builder")
            .setContentText(phaseLabel + (if (progress.message.isNotBlank()) "\n${progress.message.take(80)}" else ""))
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(openIntent)
            .setOngoing(progress.phase !in listOf(BuildPhase.SUCCESS, BuildPhase.FAILED, BuildPhase.CANCELLED))
            .setProgress(100, progress.percent, progress.percent == 0)
            .addAction(0, "Batal", cancelIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(progress: BuildProgress) {
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(progress))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        orchestrator?.cancel()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        executorService.shutdownNow()
        listener = null
    }
}
