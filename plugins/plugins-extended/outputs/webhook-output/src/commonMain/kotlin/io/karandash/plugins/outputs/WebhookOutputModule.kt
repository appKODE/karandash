package io.karandash.plugins.outputs

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.ReporterConfig
import io.karandash.api.config.PreserveKeys
import io.karandash.api.finding.FormattedReport
import io.karandash.helpers.output.NotifyWhen
import io.karandash.helpers.output.OnError
import io.karandash.helpers.output.handle
import io.karandash.helpers.output.postOrFail
import io.karandash.http.HostCredentials
import io.karandash.http.HttpAuth
import io.karandash.http.NetworkPolicy
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.http.SsrfUrlValidator
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.sdk.typed.AbstractTypedOutputModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.hostWithPortIfSpecified
import kotlinx.atomicfu.atomic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Config for [WebhookOutputModule].
 *
 * @property url The HTTP endpoint to POST the formatted report to.
 * @property headers Additional HTTP headers to include in the request.
 * @property authToken Optional bearer token added as `Authorization` header.
 * @property contentType `Content-Type` of the POSTed body.
 * @property onError `fail` (default) fails the output when the POST fails; `warn` only logs it.
 * @property notifyWhen `when`: which runs deliver (`always`, `changed`: the verdict or the findings differ from the last
 *   platform summary, `failed`, `never`); default [NotifyWhen.ALWAYS]. The pipeline applies it before calling the module.
 */
@Serializable
data class WebhookOutputConfig(
    val url: String = "",
    @PreserveKeys
    val headers: Map<String, String> = emptyMap(),
    val authToken: String? = null,
    val contentType: String = "application/json",
    val onError: OnError = OnError.FAIL,
    @SerialName("when")
    val notifyWhen: NotifyWhen = NotifyWhen.ALWAYS,
) : ReporterConfig

/**
 * [config]'s auth token and custom headers as credentials keyed for the webhook host: the client strips them from
 * any request that leaves it (a redirect to another host).
 */
internal fun webhookCredentials(config: WebhookOutputConfig): HostCredentials =
    HostCredentials.of(config.url, config.headers.keys + HttpHeaders.Authorization) {
        config.authToken?.let(HttpAuth::Bearer)
    }

/**
 * Output module that HTTP-POSTs a [FormattedReport.body] to a configurable webhook endpoint.
 *
 * Accepts all formats. URL is SSRF-validated before each request. A failure (bad URL, non-2xx response) is logged at
 * warn and, under the default `onError: fail`, fails the output (the pipeline reports the failed output).
 *
 * @param engine Ktor engine override for tests; null uses the platform engine.
 */
class WebhookOutputModule(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedOutputModule<WebhookOutputConfig>(WebhookOutputConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Webhook Output"
    override val version: String = Version.VERSION
    override val outputId: String = OUTPUT_ID
    override val description: String = "POSTs a formatted report body to an HTTP webhook endpoint."

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.output.webhook"

        /** Output identifier. */
        const val OUTPUT_ID = "webhook"
    }

    private var logger: Logger = NoOpLogger
    private val allowLocalhostFlag = atomic<Boolean>(false)

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        allowLocalhostFlag.value = context.allowLocalhost
        logger.info("Webhook output module initialized")
        return true
    }

    override suspend fun outputTyped(
        report: FormattedReport,
        config: WebhookOutputConfig,
    ) = config.onError.handle(logger, "Webhook output") {
        require(config.url.isNotEmpty()) { "Webhook output requires 'url' configuration" }

        SsrfUrlValidator.validate(config.url, allowLocalhostFlag.value, NetworkPolicy.allowsPrivateNetwork())

        // path, query and userinfo of a webhook URL often carry the token: log only scheme and host
        val target = Url(config.url).let { "${it.protocol.name}://${it.hostWithPortIfSpecified}" }
        logger.info("Posting webhook output to $target")
        val contentType = ContentType.parse(config.contentType)
        // per call: the credentials come from this output's config
        createClient(webhookCredentials(config)).use { client ->
            client.postOrFail(config.url, "$target failed") {
                contentType(contentType)
                setBody(report.body)
                config.headers.forEach { (k, v) -> header(k, v) }
            }
        }
    }

    private fun createClient(credentials: HostCredentials): HttpClient =
        ProviderHttpClientFactory.create(
            engine = engine,
            logger = logger,
            allowLocalhost = allowLocalhostFlag.value,
            credentials = credentials,
            retryUnsentPost = true,
        )

    override suspend fun shutdown() = Unit
}
