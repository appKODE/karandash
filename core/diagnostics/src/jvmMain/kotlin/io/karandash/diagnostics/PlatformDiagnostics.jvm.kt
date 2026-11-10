package io.karandash.diagnostics

import java.lang.management.ManagementFactory

/**
 * JVM implementation using `Runtime` heap stats and MXBeans for GC metrics.
 */
actual object PlatformDiagnostics {
    actual fun captureSnapshot(elapsedMs: Long): ResourceSnapshot {
        val runtime = Runtime.getRuntime()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val usedMemory = totalMemory - freeMemory

        val gcBeans = ManagementFactory.getGarbageCollectorMXBeans()
        val gcCount = gcBeans.sumOf { it.collectionCount.coerceAtLeast(0) }
        val gcTime = gcBeans.sumOf { it.collectionTime.coerceAtLeast(0) }

        val threadBean = ManagementFactory.getThreadMXBean()
        // Whole-process CPU; the sampler thread's own CPU time would stay near zero.
        val cpuTimeNs = (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)
            ?.processCpuTime
            ?.coerceAtLeast(0)
            ?: if (threadBean.isCurrentThreadCpuTimeSupported) threadBean.currentThreadCpuTime.coerceAtLeast(0) else 0L

        return ResourceSnapshot(
            timestampMs = elapsedMs,
            peakRssBytes = usedMemory,
            currentRssBytes = usedMemory,
            memoryTotalBytes = totalMemory,
            memoryMaxBytes = maxMemory,
            cpuUserTimeMs = cpuTimeNs / NS_PER_MS,
            gcCount = gcCount,
            gcTimeMs = gcTime,
            threadCount = threadBean.threadCount,
        )
    }

    actual val buildVariant: String = "jvm"
    actual val osName: String = "jvm"

    private const val NS_PER_MS = 1_000_000L
}
