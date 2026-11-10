package io.karandash.cli.commands.validate

import io.karandash.api.module.PlatformServiceModule
import io.karandash.detect.PlatformUrlParser
import kotlinx.serialization.Serializable

/**
 * Parsed PR/MR URL components.
 */
@Serializable
data class ParsedPrUrl(
    val platform: String,
    val baseUrl: String,
    val projectId: String,
    val mrId: String
)

/**
 * Parser for PR/MR URLs from all supported platforms.
 *
 * Delegates to [PlatformUrlParser] which dispatches to registered provider modules.
 */
class PrUrlParser(
    providers: List<PlatformServiceModule>
) {
    private val urlParser = PlatformUrlParser(providers)

    /**
     * Parse a PR/MR URL from any supported platform.
     *
     * @param url The PR/MR URL to parse
     * @param warnOnInsecureHttp Whether to emit a warning when HTTP is used
     * @return ParsedPrUrl if parsing succeeds, null otherwise
     */
    suspend fun parse(
        url: String,
        warnOnInsecureHttp: Boolean = false
    ): ParsedPrUrl? {
        val parsed = urlParser.parse(url, warnOnInsecureHttp) ?: return null
        return ParsedPrUrl(
            platform = parsed.platform,
            baseUrl = parsed.baseUrl,
            projectId = parsed.projectId,
            mrId = parsed.mrId
        )
    }
}
