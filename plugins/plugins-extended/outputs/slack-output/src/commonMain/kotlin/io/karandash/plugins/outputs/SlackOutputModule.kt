package io.karandash.plugins.outputs

import io.karandash.Version
import io.karandash.api.PluginContext
import io.karandash.api.ReporterConfig
import io.karandash.api.finding.FormattedReport
import io.karandash.helpers.output.NotifyWhen
import io.karandash.helpers.output.OnError
import io.karandash.helpers.output.handle
import io.karandash.helpers.output.postOrFail
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.http.SsrfHostKind
import io.karandash.http.SsrfUrlValidator
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.typed.AbstractTypedOutputModule
import io.karandash.security.UrlSanitizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.atomicfu.atomic
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Config for [SlackOutputModule].
 *
 * @property webhook The Slack incoming webhook URL (must be `https://hooks.slack.com/`; a loopback
 *   http(s) URL is also accepted when the run sets `allow-localhost`, for local relays and offline tests).
 * @property channel Optional channel override, added to the payload as `channel` unless the body already sets one
 *   (Slack ignores it for most webhook types).
 * @property onError `warn` (default) only logs a failed delivery; `fail` also fails the output.
 * @property notifyWhen `when`: which runs deliver (`always`, `changed`: the verdict or the findings differ from the last
 *   platform summary, `failed`, `never`); default [NotifyWhen.CHANGED]. The pipeline applies it before calling the module.
 * @property token Bot token (`xoxb-…`, scopes `chat:write` and `channels:history`): when set, the report is posted to
 *   [channel] (a channel ID) with `chat.postMessage` and a later run for the same MR updates that message with
 *   `chat.update`; [webhook] is then unused. Unset (default): webhook mode.
 * @property notificationTarget The MR this run reports on, set by the pipeline; it keys the message bot-token mode updates.
 */
@Serializable
data class SlackOutputConfig(
    val webhook: String = "",
    val channel: String? = null,
    val onError: OnError = OnError.WARN,
    @SerialName("when")
    val notifyWhen: NotifyWhen = NotifyWhen.CHANGED,
    val token: String? = null,
    val notificationTarget: String = "",
) : ReporterConfig

/**
 * Output module that HTTP-POSTs a Slack Block Kit JSON payload to a Slack webhook URL.
 *
 * Accepts only the `"slack"` format - expects [FormattedReport.body] to be valid Slack Block Kit JSON.
 * The webhook URL is a credential: it never appears in log or error messages. A missing or rejected webhook and a
 * failed delivery are logged at warn and, only under `onError: fail`, fail the output.
 *
 * @param engine Ktor engine override for tests; null uses the platform engine.
 */
class SlackOutputModule(
    private val engine: HttpClientEngine? = null,
) : AbstractTypedOutputModule<SlackOutputConfig>(SlackOutputConfig.serializer()) {
    override val id: String = ID
    override val name: String = "Slack Output"
    override val version: String = Version.VERSION
    override val outputId: String = OUTPUT_ID
    override val acceptedFormats: Set<String> = setOf("slack")
    override val preferredFormat: String get() = acceptedFormats.single()
    override val description: String = "Posts a Slack Block Kit report to a webhook URL, or posts and updates it with a bot token."
    override val hoverDocumentation: String = HOVER_DOCUMENTATION

    companion object {
        /** Module ID for registry lookups. */
        const val ID = "io.karandash.output.slack"

        /** Output identifier. */
        const val OUTPUT_ID = "slack"

        private const val SLACK_WEBHOOK_PREFIX = "https://hooks.slack.com/"
    }

    /** A loopback http(s) URL, accepted only when the operator opted into local targets via `allow-localhost`. */
    private fun isAllowedLoopback(url: String): Boolean {
        if (!allowLocalhostFlag.value) return false
        return try {
            SsrfUrlValidator.validate(url, allowLocalhost = true)
            SsrfUrlValidator.classifyHost(url) == SsrfHostKind.LOOPBACK
        } catch (e: IllegalArgumentException) {
            logger.warn("Slack webhook is not a valid URL: ${e.message?.let(UrlSanitizer::sanitizeText)}")
            false
        }
    }

    private var logger: Logger = NoOpLogger
    private val allowLocalhostFlag = atomic(false)
    private val httpClient = atomic<HttpClient?>(null)

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        allowLocalhostFlag.value = context.allowLocalhost
        httpClient.value =
            ProviderHttpClientFactory.create(
                engine = engine,
                logger = logger,
                allowLocalhost = context.allowLocalhost,
                retryUnsentPost = true,
            )
        logger.info("Slack output module initialized")
        return true
    }

    override suspend fun outputTyped(
        report: FormattedReport,
        config: SlackOutputConfig,
    ) = config.onError.handle(logger, "Failed to send Slack output") {
        val client = checkNotNull(httpClient.value) { "HTTP client not initialized" }
        if (!config.token.isNullOrEmpty()) {
            require(!config.channel.isNullOrBlank()) { "Slack bot-token mode requires 'channel' (a channel ID)" }
            SlackBotPoster(client, config.token, config.channel, logger).deliver(report.body, config.notificationTarget)
            logger.info("Slack output sent successfully")
            return@handle
        }
        require(config.webhook.isNotEmpty()) { "Slack output requires 'webhook' configuration" }
        require(config.webhook.startsWith(SLACK_WEBHOOK_PREFIX) || isAllowedLoopback(config.webhook)) {
            "Slack webhook must be a $SLACK_WEBHOOK_PREFIX URL (or a loopback URL with allow-localhost)"
        }
        client.postOrFail(config.webhook, "Slack webhook rejected the message") {
            contentType(ContentType.Application.Json)
            setBody(withChannel(report.body, config.channel))
        }
        logger.info("Slack output sent successfully")
    }

    private fun withChannel(
        body: String,
        channel: String?,
    ): String {
        if (channel.isNullOrBlank()) return body
        val payload = try {
            KarandashJson.Default.parseToJsonElement(body).jsonObject
        } catch (e: SerializationException) {
            logger.warn("Slack channel '$channel' not applied: body is not JSON (${e.message})")
            return body
        } catch (e: IllegalArgumentException) {
            logger.warn("Slack channel '$channel' not applied: body is not a JSON object (${e.message})")
            return body
        }
        if (payload["channel"] != null) return body
        return JsonObject(payload + ("channel" to JsonPrimitive(channel))).toString()
    }

    override suspend fun shutdown() {
        httpClient.value?.close()
        httpClient.value = null
    }
}

private val HOVER_DOCUMENTATION = """
    # slack output

    **Slack Output**

    Delivers the `slack` format to Slack: an incoming webhook, or a bot token that posts once and updates that message
    on later runs for the same MR.

    ## Properties
    - `webhook: String` - incoming webhook URL, `https://hooks.slack.com/...` (webhook mode)
    - `token: String` - bot token `xoxb-...` (scopes `chat:write`, `channels:history`); when set, `webhook` is unused
    - `channel: String` - channel ID in bot-token mode; a channel override in webhook mode
    - `when: String` - `always`, `changed` (default), `failed` or `never`
    - `onError: String` - `warn` (default) or `fail`

    ## Example
    ```
    reporters:
      slack:
        webhook: "${'$'}{SLACK_WEBHOOK}"
        channel: "#code-reviews"
    ```
""".trimIndent()
