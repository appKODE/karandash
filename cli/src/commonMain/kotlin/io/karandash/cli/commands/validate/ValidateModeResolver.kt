package io.karandash.cli.commands.validate

import io.karandash.api.ModuleRegistry
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.errors.ProviderException
import io.karandash.api.exception.KarandashException
import io.karandash.api.exception.ProviderApiException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CiStatusSource
import io.karandash.api.platform.FetchOptions
import io.karandash.api.support.rethrowCancellation
import io.karandash.api.support.suspendCatching
import io.karandash.cli.commands.MergeRequestSnapshot
import io.karandash.cli.provider.ProviderRuntime
import io.karandash.detect.PlatformDetector
import io.karandash.models.CiJobStatus
import io.karandash.models.MergeRequest
import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import kotlinx.serialization.Serializable

/**
 * Resolves validate command execution mode (file/live/PR URL/auto-detect).
 * Extracted from ValidateCommandBase to keep orchestration smaller and lower-risk.
 */
internal class ValidateModeResolver(
    private val mrDataPath: String?,
    private val mrId: String?,
    private val platform: String?,
    private val project: String?,
    private val prUrl: String?,
    private val noAutoDetect: Boolean,
    private val logger: Logger,
    private val readMrFileContent: (String) -> String,
    private val providerRuntime: ProviderRuntime,
    /**
     * Optional callback invoked after a provider module is resolved but before any
     * fetch is performed. Used to re-initialize the provider with actual credentials
     * from the parsed config when they were unavailable during bootstrap. The second argument is the base URL
     * the `--pr` URL points at (null in the other modes), for a platform whose config sets none.
     */
    private val providerInitializer: (suspend (PlatformServiceModule, String?) -> Unit)? = null,
    /** The env var a provider's config reads its token from (`token: ${VAR}`), named in auth hints over the platform default. */
    private val configuredTokenVar: (PlatformServiceModule?) -> String? = { null },
    private val fetchOptions: FetchOptions = FetchOptions(),
    private val ciEnv: (String) -> String? = PlatformEnvironment::getEnv,
) {
    @Serializable
    data class Resolution(
        val mr: MergeRequest,
        val provider: PlatformServiceModule?,
        /** Project the MR was fetched from (live, `--pr` and auto-detect modes); null in file mode. */
        val projectId: String? = null,
        /** MR id the MR was fetched by; null in file mode. */
        val mrId: String? = null,
    )

    suspend fun resolve(
        registry: ModuleRegistry,
        preferredPluginIds: Set<String>
    ): Resolution {
        if (prUrl != null) return resolvePrUrl(prUrl, registry, preferredPluginIds)

        val isFileMode = mrDataPath != null
        val liveParams = listOf(mrId, platform, project)
        val hasAnyLiveParam = liveParams.any { it != null }
        val hasAllLiveParams = liveParams.all { it != null }

        if (isFileMode && hasAnyLiveParam) {
            throw ConfigException(
                "Cannot use --mr (file mode) together with --mr-id/--platform/--project (live mode). Choose one mode."
            )
        }

        if (!isFileMode && !hasAnyLiveParam) {
            if (!noAutoDetect) {
                val detector = PlatformDetector(registry.platformServices(), logger = logger)
                val detection = detector.detect()
                val detectedProjectId = detection?.projectId
                val detectedMrId = detection?.mrId
                if (detection != null && detectedProjectId != null && detectedMrId != null) {
                    logger.info(
                        "[Auto-detected: ${detection.platform} (confidence: ${detection.confidence}, source: ${detection.source})]"
                    )
                    return fetchFromProvider(
                        platformName = detection.platform,
                        projectId = detectedProjectId,
                        mergeRequestId = detectedMrId,
                        registry = registry,
                        preferredPluginIds = preferredPluginIds
                    )
                }
            }
            throw ConfigException(
                "Must specify either --mr for file mode, or --mr-id + --platform + --project for live mode. " +
                    "Or run in a CI environment for automatic detection."
            )
        }

        if (hasAnyLiveParam && !hasAllLiveParams) {
            val missing = mutableListOf<String>()
            if (mrId == null) missing.add("--mr-id")
            if (platform == null) missing.add("--platform")
            if (project == null) missing.add("--project")
            throw ConfigException(
                "Live mode requires all of --mr-id, --platform, --project. Missing: ${missing.joinToString(", ")}"
            )
        }

        return if (isFileMode) {
            Resolution(mr = loadFromFile(mrDataPath), provider = null)
        } else {
            fetchFromProvider(
                platformName = requireNotNull(platform) { "platform must be set when using live mode" },
                projectId = requireNotNull(project) { "project must be set when using live mode" },
                mergeRequestId = requireNotNull(mrId) { "mrId must be set when using live mode" },
                registry = registry,
                preferredPluginIds = preferredPluginIds
            )
        }
    }

    private fun loadFromFile(path: String): MergeRequest {
        try {
            val mrContent = readMrFileContent(path)
            return MergeRequestSnapshot.parse(mrContent)
        } catch (e: ConfigException) {
            throw e
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ConfigException("Failed to load MR data from file: ${e.message}")
        } catch (e: kotlinx.io.IOException) {
            throw ConfigException("Failed to load MR data from file: ${e.message}")
        }
    }

    private suspend fun resolvePrUrl(
        url: String,
        registry: ModuleRegistry,
        preferredPluginIds: Set<String>
    ): Resolution {
        if (mrDataPath != null) {
            throw ConfigException("Cannot use --pr together with --mr (file mode). Choose one mode.")
        }
        if (listOf(mrId, platform, project).any { it != null }) {
            throw ConfigException(
                "Cannot use --pr together with --mr-id/--platform/--project. Use --pr alone for URL-based validation."
            )
        }

        val parsed = PrUrlParser(registry.platformServices()).parse(url, warnOnInsecureHttp = true)
            ?: throw ConfigException(
                "${KarandashError.PR_URL_PARSE_FAILED.bracketCode} Cannot parse PR URL: $url. Please check the URL format."
            )

        logger.info("[PR URL mode: ${parsed.platform}]")

        return fetchWith(parsed.platform, registry, preferredPluginIds, parsed.baseUrl) { provider ->
            val mr = provider.fetchMergeRequestByUrl(url, fetchOptions)
            Resolution(mr = mr, provider = provider, projectId = parsed.projectId, mrId = parsed.mrId)
        }
    }

    private suspend fun fetchFromProvider(
        platformName: String,
        projectId: String,
        mergeRequestId: String,
        registry: ModuleRegistry,
        preferredPluginIds: Set<String>
    ): Resolution =
        fetchWith(platformName, registry, preferredPluginIds) { provider ->
            val mr = provider.fetchMergeRequest(projectId, mergeRequestId, fetchOptions)
            Resolution(mr = mr, provider = provider, projectId = projectId, mrId = mergeRequestId)
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchWith(
        platformName: String,
        registry: ModuleRegistry,
        preferredPluginIds: Set<String>,
        urlBaseUrl: String? = null,
        fetch: suspend (PlatformServiceModule) -> Resolution,
    ): Resolution {
        var resolvedProvider: PlatformServiceModule? = null
        try {
            val provider = providerRuntime.resolveModule(
                platform = platformName,
                registry = registry,
                preferredPluginIds = preferredPluginIds
            )
            resolvedProvider = provider
            providerInitializer?.invoke(provider, urlBaseUrl)
            return withCiStatus(fetch(provider), registry)
        } catch (e: ProviderApiException) {
            // a credential exchange (GitHub App token) fails outside the fetch wrappers: classify it like a fetch error
            throw buildApiErrorMessage(platformName, resolvedProvider, e)
        } catch (e: KarandashException) {
            throw e
        } catch (e: IllegalStateException) {
            e.rethrowCancellation()
            throw providerFetchException(platformName, resolvedProvider, e)
        } catch (e: IllegalArgumentException) {
            throw providerFetchException(platformName, resolvedProvider, e)
        } catch (e: Exception) {
            e.rethrowCancellation()
            // boundary: platform service fetchMergeRequest()/fetchMergeRequestByUrl() may throw any exception
            throw providerFetchException(platformName, resolvedProvider, e)
        }
    }

    /** Fills `ciStatus`/`ciJobs` from a [CiStatusSource] (Jenkins) when the MR's own platform reported none. */
    private suspend fun withCiStatus(
        resolution: Resolution,
        registry: ModuleRegistry,
    ): Resolution {
        if (resolution.mr.ciStatus != null) return resolution
        val status = registry
            .platformServices()
            .filterIsInstance<CiStatusSource>()
            .firstNotNullOfOrNull { it.fetchCiStatus(ciEnv) } ?: return resolution
        val job = CiJobStatus(name = ciEnv("JOB_NAME") ?: "jenkins", status = status)
        return resolution.copy(mr = resolution.mr.copy(ciStatus = status, ciJobs = listOf(job)))
    }

    private suspend fun providerFetchException(
        platformName: String,
        provider: PlatformServiceModule?,
        cause: Exception
    ): ProviderException {
        val apiException = cause.findProviderApiException()
        if (apiException != null) {
            return buildApiErrorMessage(platformName, provider, apiException)
        }

        if (cause.isAuthFailure()) {
            val hint = (configuredTokenVar(provider) ?: provider?.authTokenHint()).toHintSuffix()
            val baseMessage = cause.message ?: "Authentication failed for $platformName provider"
            return ProviderException("${KarandashError.AUTH_FAILED.bracketCode} $baseMessage$hint", cause)
        }

        // a coded cause (a missing token) is already the user-facing message
        val coded = cause.message?.takeIf { it.startsWith(KarandashError.AUTH_MISSING.bracketCode) }
        val detail = cause.message ?: cause::class.simpleName
        return ProviderException(coded ?: "Failed to fetch MR from $platformName: $detail", cause)
    }

    private suspend fun buildApiErrorMessage(
        platformName: String,
        provider: PlatformServiceModule?,
        cause: ProviderApiException
    ): ProviderException {
        val hint = (configuredTokenVar(provider) ?: provider?.authTokenHint()).toHintSuffix()
        if (cause.isNotFound && provider.rejectsCredentials()) {
            return ProviderException(credentialsMissingMessage(platformName, provider, hint), cause)
        }
        val message = buildString {
            when {
                cause.isUnauthorized -> {
                    append("${KarandashError.AUTH_FAILED.bracketCode} Authentication failed for $platformName provider (HTTP 401).")
                    append("\nYour token is invalid, expired, or revoked.")
                    cause.responseBody.takeIf { it.isNotBlank() }?.let { append("\nServer response: $it") }
                    append(hint)
                    provider?.tokenLocationHint()?.let { append("\n$it") }
                }

                cause.isForbidden -> {
                    append("${KarandashError.AUTH_FAILED.bracketCode} Insufficient permissions for $platformName provider (HTTP 403).")
                    append("\nYour token is valid but lacks required permissions.")
                    provider?.permissionScope()?.let { append("\n$it") }
                    append(hint)
                }

                cause.isNotFound -> {
                    append("${KarandashError.MR_NOT_FOUND.bracketCode} Resource not found on $platformName (HTTP 404).")
                    append("\nThis can mean the MR/project doesn't exist, OR your token lacks access.")
                    val notFound = provider?.notFoundGuidance()
                    if (notFound != null) {
                        append("\n$notFound")
                    } else {
                        append(DEFAULT_NOT_FOUND)
                    }
                    append(hint)
                }

                cause.isRateLimited -> {
                    append("${KarandashError.RATE_LIMITED.bracketCode} API rate limit exceeded for $platformName (HTTP 429).")
                    append("\nWait and retry, or use a token with higher rate limits.")
                }

                else -> {
                    append("$platformName API error (HTTP ${cause.statusCode} ${cause.statusDescription}).")
                    append("\nRequest: ${cause.requestLabel}")
                    append(hint)
                }
            }
        }
        return ProviderException(message, cause)
    }

    /**
     * An anonymous or bad token reads a private project as 404 on GitLab and GitHub: ask the platform whether it
     * accepts the credentials. A platform that cannot check (`null`) or fails to answer keeps the plain 404.
     */
    private suspend fun PlatformServiceModule?.rejectsCredentials(): Boolean =
        this != null && suspendCatching { checkAuth() }.getOrNull() == false

    private fun credentialsMissingMessage(
        platformName: String,
        provider: PlatformServiceModule?,
        hint: String,
    ): String =
        buildString {
            append("${KarandashError.AUTH_MISSING.bracketCode} $platformName answered HTTP 404 and does not accept the configured token")
            append(" (none is set, or it is invalid or expired).")
            append("\nWithout a valid token a private project or MR reads as not found.")
            append(hint)
            provider?.tokenLocationHint()?.let { append(LINE_BREAK).append(it) }
        }

    private fun Throwable.findProviderApiException(): ProviderApiException? {
        if (this is ProviderApiException) return this
        return cause?.findProviderApiException()
    }

    private fun Throwable.isAuthFailure(): Boolean {
        val text = message.orEmpty().lowercase()
        if (
            text.contains("http 401") ||
            text.contains("401 unauthorized") ||
            text.contains("http 403") ||
            text.contains("403 forbidden") ||
            text.contains("unauthorized") ||
            text.contains("authentication failed") ||
            text.contains("access denied")
        ) {
            return true
        }
        return cause?.isAuthFailure() == true
    }

    private fun String?.toHintSuffix(): String =
        this
            ?.takeIf { it.isNotBlank() }
            ?.let { "\nHint: configure token via $it." }
            ?: ""

    private companion object {
        private const val LINE_BREAK = '\n'
        private const val DEFAULT_NOT_FOUND = "\nVerify the project/MR ID is correct and your token has read access."
    }
}
