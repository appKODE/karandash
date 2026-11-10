package io.karandash.diagnostics

/**
 * Platform-specific resource capture and identification.
 *
 * Each platform provides an `actual` implementation using native APIs:
 * - JVM: `Runtime.getRuntime()` + `ManagementFactory` MXBeans
 * - macOS/Linux: `getrusage(RUSAGE_SELF)` / procfs
 * - Windows: stub (returns zeros)
 */
expect object PlatformDiagnostics {
    /**
     * Capture a point-in-time resource snapshot.
     *
     * @param elapsedMs milliseconds since the collector started,
     *                  used as [ResourceSnapshot.timestampMs].
     */
    fun captureSnapshot(elapsedMs: Long): ResourceSnapshot

    /** Build variant: `"native"` or `"jvm"`. */
    val buildVariant: String

    /** OS and architecture identifier, e.g. `"linux-x64"`, `"macos-arm64"`, `"jvm"`. */
    val osName: String
}
