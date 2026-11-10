package io.karandash.detect

import io.karandash.api.module.PlatformServiceModule
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.GitRunner
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.currentWorkingDirectory
import kotlinx.io.files.Path

/**
 * Detects CI platform and git repository information.
 *
 * Detection priority:
 * 1. Explicit environment variables (HIGH confidence)
 * 2. CI environment detection via registered providers (HIGH confidence)
 * 3. Git remote parsing via registered providers (MEDIUM confidence)
 * 4. The best viable provider score without a CI environment, a provider heuristic (LOW confidence)
 */
open class PlatformDetector(
    private val providers: List<PlatformServiceModule> = emptyList(),
    private val envProvider: (String) -> String? = { PlatformEnvironment.getEnv(it) },
    private val workingDir: Path = currentWorkingDirectory(),
    private val logger: Logger = NoOpLogger
) {
    private val ciDetector = CIDetector(providers, logger)
    private val remoteParser = GitRemoteParser(providers, logger)

    /**
     * Detection strategies in priority order. Each returns a DetectionResult or null.
     * Custom strategies can be added by modifying this list.
     */
    private val strategies: List<suspend () -> DetectionResult?> = listOf(
        ::detectFromEnv,
        ::detectFromCi,
        ::detectFromGitRemote,
        ::detectFromScore,
    )

    /**
     * Main detection method - tries all detection strategies in priority order.
     */
    suspend fun detect(): DetectionResult? {
        for (strategy in strategies) {
            logger.debug("Platform detection: trying strategy...")
            val result = strategy()
            if (result != null) {
                logger.debug(
                    "Platform detection: resolved via '${result.source}' (confidence=${result.confidence}): platform=${result.platform}"
                )
                return result
            }
        }
        return null
    }

    /**
     * Detect from explicit KARANDASH_* environment variables.
     */
    suspend fun detectFromEnv(): DetectionResult? {
        val rawPlatform = envProvider("KARANDASH_PLATFORM") ?: run {
            logger.debug("Platform detection [env]: KARANDASH_PLATFORM not set")
            return null
        }
        val platform = resolveCanonicalPlatform(rawPlatform)

        return DetectionResult(
            platform = platform,
            projectId = envProvider("KARANDASH_PROJECT_ID"),
            mrId = envProvider("KARANDASH_MR_ID"),
            baseUrl = envProvider("KARANDASH_BASE_URL"),
            token = envProvider("KARANDASH_TOKEN"),
            confidence = DetectionConfidence.HIGH,
            source = "explicit"
        )
    }

    /**
     * Detect from CI environment variables by delegating to registered providers.
     */
    suspend fun detectFromCi(): DetectionResult? {
        val ciEnvironment = ciDetector.detect(envProvider) ?: run {
            logger.debug("Platform detection [ci]: no CI environment detected")
            return null
        }

        // CIEnvironment.platform may be a full module ID; resolve to canonical name
        val platform = resolveCanonicalPlatform(ciEnvironment.platform)

        return DetectionResult(
            platform = platform,
            projectId = ciEnvironment.projectId,
            mrId = ciEnvironment.mrId,
            baseUrl = ciEnvironment.baseUrl ?: defaultApiUrlForPlatform(platform),
            token = ciEnvironment.token,
            confidence = DetectionConfidence.HIGH,
            source = "ci:$platform"
        )
    }

    /**
     * Detect from git remote URL by delegating to registered providers.
     */
    suspend fun detectFromGitRemote(): DetectionResult? {
        val remoteUrl = readGitRemote() ?: run {
            logger.debug("Platform detection [git-remote]: could not read git remote URL")
            return null
        }
        val (platform, projectId) = remoteParser.parse(remoteUrl) ?: run {
            logger.debug("Platform detection [git-remote]: could not parse remote URL")
            return null
        }

        return DetectionResult(
            platform = platform,
            projectId = projectId,
            mrId = null,
            baseUrl = defaultApiUrlForPlatform(platform),
            token = null,
            confidence = DetectionConfidence.MEDIUM,
            source = "git-remote:$platform"
        )
    }

    /**
     * Detect from the best viable provider score that carries no CI environment: a provider heuristic names
     * the platform but not the project or MR, so the result is [DetectionConfidence.LOW].
     */
    suspend fun detectFromScore(): DetectionResult? {
        val best = ProviderAutoDetector(providers, envProvider, logger)
            .detectBest()
            ?.takeIf { it.ciEnvironment == null } ?: return null
        val platform = resolveCanonicalPlatform(best.providerId)
        return DetectionResult(
            platform = platform,
            projectId = null,
            mrId = null,
            baseUrl = defaultApiUrlForPlatform(platform),
            token = null,
            confidence = DetectionConfidence.LOW,
            source = best.source
        )
    }

    /**
     * Read git remote URL by executing git command.
     *
     * A 5-second timeout prevents hanging in environments where git waits on SSH auth
     * prompts, credential helpers, or unreachable remotes (e.g. containers without an SSH agent).
     */
    open fun readGitRemote(): String? =
        GitRunner.Default
            .gitSync(listOf("remote", "get-url", "origin"), workingDir.toString(), GIT_REMOTE_TIMEOUT_SECONDS)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    companion object {
        private const val GIT_REMOTE_TIMEOUT_SECONDS = 5
    }

    /**
     * Look up default API URL for a canonical platform ID from registered providers.
     */
    private suspend fun defaultApiUrlForPlatform(platform: String): String? =
        providers
            .firstOrNull { it.id == platform }
            ?.defaultApiUrl()

    private suspend fun resolveCanonicalPlatform(rawPlatform: String): String =
        resolveCanonicalPlatform(rawPlatform, providers).also {
            val note = if (it == rawPlatform) " (no provider matched)" else ""
            logger.debug("Platform detection: '$rawPlatform' resolves to '$it'$note")
        }
}
