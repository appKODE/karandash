package io.karandash.detect

import io.karandash.api.module.PlatformServiceModule
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger

/**
 * Parser for git remote URLs to extract platform and project information.
 *
 * Dispatches to registered provider modules via [PlatformServiceModule.parseGitRemoteUrl].
 * Returns a pair of (canonicalPlatformId, projectId).
 */
class GitRemoteParser(
    private val providers: List<PlatformServiceModule>,
    private val logger: Logger = NoOpLogger
) {
    /**
     * Parse a git remote URL and extract platform and project ID.
     *
     * @param remoteUrl The git remote URL (HTTPS or SSH format)
     * @return Pair of (canonicalPlatformId, projectId) or null if URL cannot be parsed
     */
    suspend fun parse(remoteUrl: String): Pair<String, String>? {
        val url = remoteUrl.trim()
        for (provider in providers) {
            logger.debug("Git remote parsing: trying provider '${provider.id}'")
            val projectId = provider.parseGitRemoteUrl(url) ?: continue
            logger.debug("Git remote parsing: matched by '${provider.id}'")
            return provider.id to projectId
        }
        logger.debug("Git remote parsing: no provider could parse URL")
        return null
    }
}
