package com.ekstraktranskrip.core

import android.app.ActivityManager
import android.content.Context

/**
 * Per-device tuning. The target is 3-4 GB low-end hardware, where threads and
 * chunk size matter more than raw speed.
 */
object DeviceProfile {

    fun isLowRam(context: Context): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
    }.getOrDefault(false)

    /**
     * 2 threads on low-RAM devices: 4 threads on a 2-core-class SoC mostly
     * creates heat and throttling instead of throughput.
     */
    fun threads(context: Context): Int = if (isLowRam(context)) 2 else 4

    /** 5 min chunks on low-RAM, 10 min otherwise. Bounds peak float memory. */
    fun chunkSeconds(context: Context): Int = if (isLowRam(context)) 300 else 600

    fun totalRamMb(context: Context): Int = runCatching {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        (info.totalMem / (1024 * 1024)).toInt()
    }.getOrDefault(0)

    fun cores(): Int = Runtime.getRuntime().availableProcessors()
}
