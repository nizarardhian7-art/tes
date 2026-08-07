package com.termux.builder.toolchain

import com.termux.builder.model.HardwareProfile
import java.io.File

/**
 * Deteksi hardware (RAM) -> profil JVM/workers/ninja jobs.
 *
 * Pemetaan identik dengan build.sh detect_device_hardware():
 *  - RAM <= 3500 MB  -> 640m heap, 1 worker, 1 ninja job  (3GB Low-Memory)
 *  - RAM <= 5200 MB  -> 896m heap, 2 worker, 2 ninja job  (4GB Balanced)
 *  - RAM >  5200 MB  -> 1280m heap, 3 worker, 3 ninja job (6GB+ High-Perf)
 */
class HardwareDetector {

    fun detect(): HardwareProfile {
        val ramMb = readTotalRamMb()
        return when {
            ramMb <= 3500 -> HardwareProfile(ramMb, "3GB Low-Memory Profile", "640m", 1, 1)
            ramMb <= 5200 -> HardwareProfile(ramMb, "4GB Balanced Profile", "896m", 2, 2)
            else -> HardwareProfile(ramMb, "6GB+ High-Perf Profile", "1280m", 3, 3)
        }
    }

    /** Baca total RAM dari /proc/meminfo (KB). Default 4 GB bila tidak terbaca. */
    private fun readTotalRamMb(): Long {
        return try {
            val memInfo = File("/proc/meminfo").readText()
            val match = Regex("MemTotal:\\s+(\\d+)").find(memInfo)
            val kb = match?.groupValues?.get(1)?.toLongOrNull() ?: 4_000_000L
            kb / 1024
        } catch (e: Exception) {
            4000L
        }
    }
}
