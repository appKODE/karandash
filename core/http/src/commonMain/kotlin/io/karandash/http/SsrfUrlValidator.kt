package io.karandash.http

import io.karandash.security.UrlSanitizer
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import kotlinx.coroutines.flow.MutableStateFlow

/** Network class of a URL host as seen by [SsrfUrlValidator.classifyHost]. */
enum class SsrfHostKind {
    /** Public name or address. */
    PUBLIC,

    /** Single-label name (`intranet`): resolves through the local search domain. */
    DOTLESS,

    /** RFC 1918, carrier-grade NAT (`100.64/10`), benchmarking (`198.18/15`) or unique-local (`fc00::/7`) address. */
    PRIVATE,

    /** `localhost`, `127.0.0.0/8`, `::1`, `0.0.0.0/8`. */
    LOOPBACK,

    /** Cloud metadata, link-local, operator-blocked hosts and obfuscated numeric hosts - never fetched. */
    BLOCKED,
}

/**
 * Validates URLs to prevent Server-Side Request Forgery (SSRF) attacks.
 *
 * Only HTTPS URLs pointing to public hosts are permitted. Private IP ranges,
 * loopback addresses, and cloud metadata endpoints are all blocked.
 */
object SsrfUrlValidator {
    private const val MAX_IPV4_OCTET_VALUE = 255
    private const val LOCALHOST = "localhost"
    private const val LOOPBACK_IPV4 = "127.0.0.1"
    private const val METADATA_IPV4 = "169.254.169.254"
    private val PRIVATE_10_REGEX = Regex("^10\\..*")
    private val PRIVATE_172_REGEX = Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*")
    private val PRIVATE_192_REGEX = Regex("^192\\.168\\..*")

    /** Carrier-grade NAT `100.64.0.0/10` and benchmarking `198.18.0.0/15`: not public, often internal. */
    private val CGNAT_REGEX = Regex("^100\\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\\..*")
    private val BENCHMARK_REGEX = Regex("^198\\.1[89]\\..*")
    private val IPV6_CHARS_REGEX = Regex("^[0-9a-f:]+$")
    private val HEX_OCTET_REGEX = Regex("^0x[0-9a-f]*$")
    private val METADATA_HOSTS = setOf(METADATA_IPV4, "metadata.google.internal", "metadata")
    private val METADATA_IPV6_GROUPS = listOf(0xfd00, 0xec2, 0, 0, 0, 0, 0, 0x254)
    private const val GROUP_MASK_LINK_LOCAL = 0xffc0
    private const val GROUP_LINK_LOCAL = 0xfe80
    private const val GROUP_MASK_ULA = 0xfe00
    private const val GROUP_ULA = 0xfc00
    private const val GROUP_ALL_ONES = 0xffff
    private const val NAT64_GROUP_0 = 0x64
    private const val NAT64_GROUP_1 = 0xff9b
    private const val SIX_TO_FOUR_GROUP_0 = 0x2002
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xff
    private const val IPV6_GROUP_COUNT = 8
    private const val IPV6_GROUP_MAX_DIGITS = 4
    private const val HEX_RADIX = 16
    private const val PRIVATE_HINT = "; set allow_private_network: true for a host on your internal network"
    private const val LOCALHOST_HINT = "; set allow_localhost: true for a loopback host"

    /** The built-in set of blocked hosts checked on every non-localhost validation. */
    val DEFAULT_BLOCKED_HOSTS: Set<String> = setOf(
        LOCALHOST,
        LOOPBACK_IPV4,
        "0.0.0.0",
        METADATA_IPV4,
        "metadata.google.internal",
        "[::1]",
    )

    private val defaultBlockedNormalized: Set<String> = DEFAULT_BLOCKED_HOSTS.mapTo(mutableSetOf(), ::normalizeHost)

    private val _additionalBlockedHosts = MutableStateFlow(emptySet<String>())

    /** Currently configured additional blocked hosts beyond [DEFAULT_BLOCKED_HOSTS]. */
    val additionalBlockedHosts: Set<String> get() = _additionalBlockedHosts.value

    /**
     * Extend the blocked-host list with additional entries beyond the built-in defaults.
     *
     * Useful for environments that need to block additional internal hostnames
     * (e.g. corporate metadata endpoints, internal proxy addresses).
     *
     * @param hosts Hostnames to block in addition to [DEFAULT_BLOCKED_HOSTS]
     */
    fun configureAdditionalBlockedHosts(hosts: Set<String>) {
        _additionalBlockedHosts.value = hosts.mapTo(mutableSetOf(), ::normalizeHost)
    }

    /**
     * Validates that [url] is a safe, publicly-reachable HTTPS URL.
     *
     * When [allowLocalhost] is true, skips private-IP, loopback and HTTPS-only checks to permit self-hosted
     * services on localhost or private networks; metadata, link-local and operator-blocked hosts stay refused. [allowPrivateNetwork]
     * (the run's [NetworkPolicy]) permits private and dotless hosts only.
     *
     * @throws IllegalArgumentException if the URL is malformed, uses a non-HTTPS
     *   protocol (unless [allowLocalhost]), or points to a blocked host.
     */
    fun validate(
        url: String,
        allowLocalhost: Boolean = false,
        allowPrivateNetwork: Boolean = false,
    ) {
        val parsed = try {
            Url(url)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("Invalid webhook URL: ${UrlSanitizer.sanitize(url)}", e)
        }

        if (allowLocalhost) {
            require(
                parsed.protocol == URLProtocol.HTTPS || parsed.protocol == URLProtocol.HTTP
            ) {
                "Webhook URL must use HTTP or HTTPS protocol. Got: ${parsed.protocol.name}://"
            }
            rejectBlockedHost(url, parsed.host, "Webhook URL")
            return
        }

        require(parsed.protocol == URLProtocol.HTTPS) {
            "Webhook URL must use HTTPS protocol. Got: ${parsed.protocol.name}://"
        }

        rejectNonPublicHost(url, parsed.host, "Webhook URL", allowDotless = false, allowPrivateNetwork)
    }

    /**
     * Fails unless [url]'s host classifies as [SsrfHostKind.PUBLIC] (or [SsrfHostKind.DOTLESS] when allowed, or
     * either private kind under [allowPrivateNetwork]).
     */
    private fun rejectNonPublicHost(
        url: String,
        host: String,
        what: String,
        allowDotless: Boolean,
        allowPrivateNetwork: Boolean,
    ) {
        val kind = classifyHost(url)
        require(isPermitted(kind, allowPrivateNetwork) || (kind == SsrfHostKind.DOTLESS && allowDotless)) {
            when (kind) {
                SsrfHostKind.DOTLESS -> "$what has suspicious hostname without dots: '$host' (potential SSRF)$PRIVATE_HINT"
                SsrfHostKind.PRIVATE -> "$what must not point to private IP address '$host' (potential SSRF)$PRIVATE_HINT"
                else -> "$what must not point to blocked host '$host' (potential SSRF)"
            }
        }
    }

    /** Metadata, link-local and operator-blocked hosts: refused even under `allow_localhost`. */
    private fun rejectBlockedHost(
        url: String,
        host: String,
        what: String,
    ) {
        require(classifyHost(url) != SsrfHostKind.BLOCKED) { "$what must not point to blocked host '$host' (potential SSRF)" }
    }

    private fun isPermitted(
        kind: SsrfHostKind,
        allowPrivateNetwork: Boolean,
    ): Boolean =
        kind == SsrfHostKind.PUBLIC ||
            (allowPrivateNetwork && (kind == SsrfHostKind.PRIVATE || kind == SsrfHostKind.DOTLESS))

    private fun isValidIPv4(ip: String): Boolean {
        val parts = ip.split('.')
        if (parts.size != 4) return false
        return parts.all { part ->
            part.isNotEmpty() && part.toIntOrNull(10)?.let { it in 0..MAX_IPV4_OCTET_VALUE } == true
        }
    }

    /**
     * Classifies the host of [url] for callers that apply their own SSRF policy
     * (e.g. operator-trusted `extends:` vs. strict plugin content fetches).
     *
     * Lexical only: a public name that resolves to a private address is classified PUBLIC. Clients built by
     * [ProviderHttpClientFactory] also run [requirePublicAddresses] on every request.
     *
     * @throws IllegalArgumentException if [url] cannot be parsed.
     */
    fun classifyHost(url: String): SsrfHostKind = classifyHostName(Url(url).host)

    /**
     * Fails when [host] resolves (through [resolve]) to any address that is not [SsrfHostKind.PUBLIC] (or private,
     * under [allowPrivateNetwork]): a public
     * name pointing at a private, loopback or metadata address (DNS rebinding). IP literals are not resolved, and
     * a name that does not exist (empty list) passes, since the request then fails on its own DNS error; [resolve]
     * throws on any other lookup failure.
     *
     * limit: check-then-connect - the engine resolves again, so a 0-TTL record flipped between this check and
     * the connect still gets through; pin the checked address in the engine's resolver if that matters.
     *
     * @throws IllegalArgumentException naming the first non-public address.
     */
    internal suspend fun requirePublicAddresses(
        host: String,
        resolve: suspend (String) -> List<String>,
        allowPrivateNetwork: Boolean = false,
    ) {
        val name = normalizeHost(host)
        if (isValidIPv4(name) || ipv6Groups(name) != null) return
        val bad = resolve(name).firstOrNull { !isPermitted(classifyHostName(it.substringBefore('%')), allowPrivateNetwork) }
        require(bad == null) {
            val hint = if (classifyHostName(bad.orEmpty().substringBefore('%')) == SsrfHostKind.PRIVATE) PRIVATE_HINT else LOCALHOST_HINT
            "Host '$host' resolves to non-public address '$bad' (potential SSRF)$hint"
        }
    }

    private fun normalizeHost(host: String): String =
        host
            .lowercase()
            .removePrefix("[")
            .removeSuffix("]")
            .trimEnd('.') // `localhost.` and `127.0.0.1.` are the same hosts to a resolver

    private fun classifyHostName(rawHost: String): SsrfHostKind {
        val host = normalizeHost(rawHost)
        val groups = ipv6Groups(host)
        val embedded = groups?.let(::embeddedIpv4)
        return when {
            host in _additionalBlockedHosts.value -> SsrfHostKind.BLOCKED

            host in defaultBlockedNormalized -> if (host in METADATA_HOSTS) SsrfHostKind.BLOCKED else SsrfHostKind.LOOPBACK

            embedded != null -> classifyHostName(embedded)

            groups != null -> classifyIpv6(groups)

            host.contains(':') -> SsrfHostKind.BLOCKED

            // not a parseable IPv6 literal (zone id, malformed)
            isBlockedHost(host) -> SsrfHostKind.BLOCKED

            isLoopbackHost(host) -> SsrfHostKind.LOOPBACK

            isPrivateRange(host) -> SsrfHostKind.PRIVATE

            isValidIPv4(host) -> SsrfHostKind.PUBLIC

            !host.contains('.') -> SsrfHostKind.DOTLESS

            else -> SsrfHostKind.PUBLIC
        }
    }

    private fun classifyIpv6(groups: List<Int>): SsrfHostKind =
        when {
            groups.dropLast(1).all { it == 0 } && groups.last() <= 1 -> SsrfHostKind.LOOPBACK

            // `::`, `::1`
            groups == METADATA_IPV6_GROUPS -> SsrfHostKind.BLOCKED

            // AWS IMDS `fd00:ec2::254`, a ULA that would otherwise pass as PRIVATE
            groups[0] and GROUP_MASK_LINK_LOCAL == GROUP_LINK_LOCAL -> SsrfHostKind.BLOCKED

            // fe80::/10
            groups[0] and GROUP_MASK_ULA == GROUP_ULA -> SsrfHostKind.PRIVATE

            // fc00::/7
            else -> SsrfHostKind.PUBLIC
        }

    /**
     * The IPv4 address an IPv6 literal carries, or null: IPv4-mapped `::ffff:0:0/96`, IPv4-translated
     * `::ffff:0:0:0/96`, IPv4-compatible `::/96` (other than `::` and `::1`), NAT64 `64:ff9b::/96` and 6to4
     * `2002::/16`. Each reaches that IPv4 host, so it is classified as one.
     */
    private fun embeddedIpv4(g: List<Int>): String? {
        val prefix = g.dropLast(2)
        val (high, low) = g.takeLast(2)
        val words = when {
            prefix == listOf(0, 0, 0, 0, 0, GROUP_ALL_ONES) -> listOf(high, low)
            prefix == listOf(0, 0, 0, 0, GROUP_ALL_ONES, 0) -> listOf(high, low)
            prefix.all { it == 0 } && (high != 0 || low > 1) -> listOf(high, low)
            prefix == listOf(NAT64_GROUP_0, NAT64_GROUP_1, 0, 0, 0, 0) -> listOf(high, low)
            g[0] == SIX_TO_FOUR_GROUP_0 -> listOf(g[1], g[2])
            else -> return null
        }
        return words.joinToString(".") { "${it shr BYTE_BITS}.${it and BYTE_MASK}" }
    }

    private fun isBlockedHost(host: String): Boolean =
        host in METADATA_HOSTS ||
            (host.startsWith("169.254.") && isValidIPv4(host)) ||
            isObfuscatedNumericHost(host)

    /** `2130706433`, `0x7f.1`, `0177.0.0.1`: numeric forms a resolver may read as an IP we can't vet. */
    private fun isObfuscatedNumericHost(host: String): Boolean {
        val parts = host.split('.')
        val numeric = parts.all { it.isNotEmpty() && (it.all(Char::isDigit) || HEX_OCTET_REGEX.matches(it)) }
        return (numeric && !isValidIPv4(host)) || (numeric && parts.any { it.length > 1 && it.startsWith('0') })
    }

    private fun isLoopbackHost(host: String): Boolean =
        host == LOCALHOST ||
            host.endsWith(".$LOCALHOST") ||
            // `0.0.0.0/8` reaches the local host on Linux and macOS
            ((host.startsWith("0.") || host.startsWith("127.")) && isValidIPv4(host))

    /**
     * The eight 16-bit groups of an IPv6 literal with `::` expanded (`0:0:0:0:0:0:0:1` is `::1`) and a dotted IPv4
     * tail (`::ffff:1.2.3.4`) read as its two groups, or null when [host] is not one.
     */
    private fun ipv6Groups(host: String): List<Int>? {
        if (!host.contains(':')) return null
        val tail = host.substringAfterLast(':')
        val hex = when {
            !tail.contains('.') -> host
            isValidIPv4(tail) -> host.substringBeforeLast(':') + ":" + dottedToGroups(tail)
            else -> return null
        }
        if (!hex.matches(IPV6_CHARS_REGEX)) return null
        val halves = hex.split("::")
        if (halves.size > 2) return null
        val parts = halves.map { half ->
            if (half.isEmpty()) {
                emptyList()
            } else {
                half.split(':').map { group -> group.takeIf { it.length <= IPV6_GROUP_MAX_DIGITS }?.toIntOrNull(HEX_RADIX) ?: return null }
            }
        }
        val missing = IPV6_GROUP_COUNT - parts.sumOf { it.size }
        return when {
            halves.size == 1 && missing == 0 -> parts[0]
            halves.size == 2 && missing > 0 -> parts[0] + List(missing) { 0 } + parts[1]
            else -> null
        }
    }

    /** `1.2.3.4` -> `102:304`. */
    private fun dottedToGroups(ipv4: String): String =
        ipv4
            .split('.')
            .map { it.toInt() }
            .chunked(2) { (high, low) -> ((high shl BYTE_BITS) or low).toString(HEX_RADIX) }
            .joinToString(":")

    private fun isPrivateRange(host: String): Boolean =
        isValidIPv4(host) &&
            listOf(PRIVATE_10_REGEX, PRIVATE_172_REGEX, PRIVATE_192_REGEX, CGNAT_REGEX, BENCHMARK_REGEX).any { host.matches(it) }

    /**
     * Validates a base URL for use with external provider APIs.
     *
     * @param url The URL to validate.
     * @param allowHttp If true, allows HTTP (default: false, HTTPS only).
     * @param allowLocalhost If true, skips private/loopback IP checks and allows HTTP.
     * @param allowPrivateNetwork If true (the run's [NetworkPolicy]), permits private and dotless hosts only.
     * @return The validated and normalized URL (trailing slash removed).
     * @throws IllegalArgumentException if validation fails.
     */
    fun validateBaseUrl(
        url: String,
        allowHttp: Boolean = false,
        allowLocalhost: Boolean = false,
        allowPrivateNetwork: Boolean = false,
    ): String {
        require(url.isNotBlank()) { "Base URL cannot be blank" }
        if (allowLocalhost || allowHttp) {
            require(url.startsWith("http://") || url.startsWith("https://")) {
                "Base URL must use HTTP or HTTPS scheme: ${UrlSanitizer.sanitize(url)}"
            }
        } else {
            require(url.startsWith("https://")) {
                "Base URL must use HTTPS scheme: ${UrlSanitizer.sanitize(url)}"
            }
        }
        if (allowLocalhost) {
            rejectBlockedHost(url, Url(url).host, "Base URL")
        } else {
            rejectNonPublicHost(url, Url(url).host, "Base URL", allowDotless = true, allowPrivateNetwork)
        }
        return url.trimEnd('/')
    }

    /**
     * Validates that a pagination or redirect URL belongs to the expected base domain.
     *
     * @throws IllegalArgumentException if the URL does not match the expected base.
     */
    fun validateUrlMatchesBase(
        url: String,
        expectedBase: String,
    ) {
        val base = expectedBase.trimEnd('/')
        require(url == base || url.startsWith("$base/") || url.startsWith("$base?")) {
            "URL does not match expected base domain. Expected: ${UrlSanitizer.sanitize(expectedBase)}, Got: ${UrlSanitizer.sanitize(url)}"
        }
    }
}
