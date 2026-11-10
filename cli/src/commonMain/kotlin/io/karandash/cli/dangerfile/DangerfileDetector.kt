package io.karandash.cli.dangerfile

import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.isAbsolutePath
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Detects a Dangerfile in the working directory or at an explicit path. */
class DangerfileDetector(
    private val jsCandidates: List<String> = JS_CANDIDATES,
    private val ktsCandidates: List<String> = KTS_CANDIDATES,
) {
    companion object {
        val JS_CANDIDATES = listOf("Dangerfile.js", "Dangerfile", "Dangerfile.mjs")
        val KTS_CANDIDATES = listOf("Dangerfile.df.kts", "Dangerfile.kts")
    }

    /**
     * Detect a Dangerfile.
     *
     * When [explicitPath] is non-null, that exact path is used (returning null if it does not exist).
     * Otherwise the [workingDir] is searched using the standard candidate file names.
     *
     * @param workingDir Directory to search in when no explicit path is given
     * @param explicitPath Optional explicit Dangerfile path from CLI or config
     * @return The detected Dangerfile, or null if none found
     */
    fun detect(
        workingDir: String,
        explicitPath: String? = null
    ): DetectedDangerfile? {
        if (explicitPath != null) {
            val path = Path(explicitPath).absolutePathOrSelf()
            if (!SystemFileSystem.exists(path)) return null
            val type = if (explicitPath.endsWith(".kts")) DangerfileType.KTS else DangerfileType.JS
            // Relative paths are cwd-relative; the runner executes in the config dir, so pass them resolved.
            val resolved = if (isAbsolutePath(explicitPath)) explicitPath else path.toString()
            return DetectedDangerfile(resolved, type)
        }
        for (name in jsCandidates) {
            val p = Path(workingDir, name)
            if (SystemFileSystem.exists(p)) return DetectedDangerfile(p.absolutePathOrSelf().toString(), DangerfileType.JS)
        }
        for (name in ktsCandidates) {
            val p = Path(workingDir, name)
            if (SystemFileSystem.exists(p)) return DetectedDangerfile(p.absolutePathOrSelf().toString(), DangerfileType.KTS)
        }
        return null
    }
}
