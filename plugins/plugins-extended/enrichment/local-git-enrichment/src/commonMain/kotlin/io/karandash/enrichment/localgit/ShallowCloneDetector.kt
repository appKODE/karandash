package io.karandash.enrichment.localgit

import io.karandash.api.platform.SubprocessBudget
import io.karandash.sdk.Logger

/**
 * Detects whether the current git repository is a shallow clone.
 *
 * Uses `git rev-parse --is-shallow-repository` (Git ≥ 2.15). Older Git versions
 * return a non-zero exit code, which [GitCliHelper.run] maps to null - in that
 * case this function conservatively reports "not shallow".
 */
internal object ShallowCloneDetector {
    suspend fun isShallow(
        workingDir: String?,
        budget: SubprocessBudget? = null,
        logger: Logger? = null,
    ): Boolean {
        if (workingDir.isNullOrBlank()) return false
        val output = GitCliHelper.run(
            workingDir = workingDir,
            args = listOf("rev-parse", "--is-shallow-repository"),
            budget = budget,
            logger = logger,
        ) ?: return false
        return output.trim() == "true"
    }
}
