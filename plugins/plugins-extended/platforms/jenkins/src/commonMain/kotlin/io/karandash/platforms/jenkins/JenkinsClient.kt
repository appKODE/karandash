package io.karandash.platforms.jenkins

import io.karandash.http.HostCredentials
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.AdapterHelpers
import io.karandash.providers.common.UrlValidator
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.URLParserException
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

@Serializable
internal data class JenkinsBuild(
    val building: Boolean = false,
    val result: String? = null,
)

/**
 * Reads a build's state from the Jenkins JSON API (`<build url>/api/json`).
 *
 * [baseUrl] (`JENKINS_URL`) passes [UrlValidator.validateBaseUrl] like every platform's base URL and throws
 * [IllegalArgumentException] when refused; private hosts are checked per request against the run's network policy by
 * the [ProviderHttpClientFactory] client.
 */
internal class JenkinsClient(
    baseUrl: String,
    credentials: JenkinsCredentials,
    private val logger: Logger = NoOpLogger,
    engine: HttpClientEngine? = null,
    allowLocalhost: Boolean = false,
) {
    private val baseHost: String =
        Url(UrlValidator.validateBaseUrl(baseUrl, allowLocalhost = allowLocalhost, allowPrivateNetwork = true)).host

    private val httpClient: HttpClient = ProviderHttpClientFactory.createProvider(
        providerName = "Jenkins",
        engine = engine,
        credentials = credentials.auth()?.let { HostCredentials.of(baseUrl, it) },
        logger = logger,
        allowLocalhost = allowLocalhost,
    )

    /**
     * `success`, `failed` or `pending` for the build at [buildUrl]; null when it cannot be read or [buildUrl] is not on
     * the `JENKINS_URL` host, so the Jenkins credentials never go to another host.
     */
    suspend fun fetchCiStatus(buildUrl: String): String? {
        val buildHost = hostOf(buildUrl)
        if (!buildHost.equals(baseHost, ignoreCase = true)) {
            logger.warn("Jenkins: BUILD_URL host '$buildHost' is not the JENKINS_URL host '$baseHost'; build status not read")
            return null
        }
        return try {
            val response = httpClient.get("${buildUrl.trimEnd('/')}/api/json?tree=building,result")
            if (response.status.isSuccess()) {
                toCiStatus(KarandashJson.Default.decodeFromString<JenkinsBuild>(response.bodyAsText()))
            } else {
                logger.warn("Jenkins: build status request returned HTTP ${response.status.value}")
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SerializationException) {
            logger.warn("Jenkins: unreadable build status response: ${e.message}")
            null
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception, // boundary: Jenkins failure degrades to no CI status
        ) {
            logger.warn("Jenkins: failed to read build status: ${e.message}")
            null
        }
    }

    fun close() {
        httpClient.close()
    }

    private fun hostOf(url: String): String? =
        try {
            Url(url).host
        } catch (e: URLParserException) {
            logger.warn("Jenkins: unreadable BUILD_URL: ${e.message}")
            null
        }

    private fun toCiStatus(build: JenkinsBuild): String =
        when {
            build.building || build.result == null -> AdapterHelpers.CI_PENDING
            build.result == "SUCCESS" -> AdapterHelpers.CI_SUCCESS
            else -> AdapterHelpers.CI_FAILED
        }
}
