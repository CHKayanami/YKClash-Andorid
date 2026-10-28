package com.yk.clash.ui

import android.os.Debug
import android.os.SystemClock
import java.io.File

internal data class MemorySnapshot(
    val pss: Long?, val rss: Long?, val nativeHeap: Long?, val javaHeap: Long?, val graphics: Long?, val code: Long?,
)

internal object ProcessMemory {
    private var measuredAt = 0L
    private var cached: MemorySnapshot? = null

    @Synchronized fun snapshot(): MemorySnapshot {
        val now = SystemClock.elapsedRealtime()
        cached?.let { if (now - measuredAt < 5000) return it }
        val info = runCatching { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) } }.getOrNull()
        fun stat(name: String) = info?.getMemoryStat(name)?.toLongOrNull()?.times(1024)
        val rss = runCatching {
            File("/proc/self/status").useLines { lines ->
                lines.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()
                    ?.substringBefore(' ')?.toLongOrNull()?.times(1024)
            }
        }.getOrNull()
        return MemorySnapshot(info?.totalPss?.toLong()?.times(1024)?.takeIf { it > 0 }, rss,
            stat("summary.native-heap"), stat("summary.java-heap"), stat("summary.graphics"), stat("summary.code"))
            .also { cached = it; measuredAt = now }
    }
}
