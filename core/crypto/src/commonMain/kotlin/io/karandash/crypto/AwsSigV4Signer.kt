package io.karandash.crypto

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/** Static AWS credentials; [toString] never prints the secret or the session token. */
@Serializable
data class AwsStaticCredentials(
    val accessKeyId: String,
    val secretAccessKey: String,
    val sessionToken: String? = null,
) {
    override fun toString(): String = "AwsStaticCredentials(accessKeyId=$accessKeyId, secretAccessKey=***, sessionToken=***)"
}

/**
 * One request to sign: [path] and [query] (without `?`) as sent on the wire, percent-encoded or not - each path
 * segment and query component is decoded and re-encoded once per the AWS rules (S3 style, no double encoding), so
 * the signature matches whichever form the HTTP client sends. [headers] are extra headers to sign besides `host`,
 * `x-amz-date`, `x-amz-security-token` and, with [signContentSha256], `x-amz-content-sha256`; the caller sends them
 * with the request unchanged.
 */
@Serializable
data class AwsSigV4Request(
    val method: String,
    val host: String,
    val path: String,
    val region: String,
    val service: String,
    val headers: Map<String, String> = emptyMap(),
    val query: String = "",
    val signContentSha256: Boolean = true,
)

/** Headers the caller adds to the request: `Authorization`, `X-Amz-Date`, `X-Amz-Content-Sha256`, `X-Amz-Security-Token`. */
@Serializable
data class AwsSigV4Headers(
    val authorization: String,
    val amzDate: String,
    val payloadSha256: String,
    val sessionToken: String?,
) {
    override fun toString(): String = "AwsSigV4Headers(amzDate=$amzDate, payloadSha256=$payloadSha256, authorization=***)"
}

/** AWS Signature Version 4 (header-based, `AWS4-HMAC-SHA256`), shared by the CodeCommit plugin and the S3 remote cache. */
object AwsSigV4Signer {
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val TERMINATOR = "aws4_request"
    private const val BYTE_MASK = 0xFF
    private const val ASCII_LIMIT = 0x80
    private const val NIBBLE_BITS = 4
    private const val NIBBLE_MASK = 0x0F
    private const val HEX_RADIX = 16
    private const val ESCAPE_LENGTH = 3
    private const val HEX_DIGITS = "0123456789ABCDEF"
    private const val UNRESERVED_MARKS = "-._~"

    fun sign(
        request: AwsSigV4Request,
        payload: ByteArray,
        credentials: AwsStaticCredentials,
        now: Instant = Clock.System.now(),
    ): AwsSigV4Headers {
        val (amzDate, dateStamp) = formatAmzDates(now)
        val payloadHash = sha256Hex(payload)
        val sessionToken = credentials.sessionToken?.takeIf { it.isNotBlank() }

        val canonicalHeaders = request.headers.mapKeys { it.key.lowercase() } +
            mapOf("host" to request.host, "x-amz-date" to amzDate) +
            listOfNotNull(
                sessionToken?.let { "x-amz-security-token" to it },
                ("x-amz-content-sha256" to payloadHash).takeIf { request.signContentSha256 },
            )
        val sortedHeaderNames = canonicalHeaders.keys.sorted()
        val signedHeaders = sortedHeaderNames.joinToString(";")
        val canonicalRequest = listOf(
            request.method.uppercase(),
            canonicalUri(request.path),
            canonicalQuery(request.query),
            sortedHeaderNames.joinToString(separator = "") { "$it:${collapseWhitespace(canonicalHeaders.getValue(it))}\n" },
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val credentialScope = "$dateStamp/${request.region}/${request.service}/$TERMINATOR"
        val stringToSign = listOf(ALGORITHM, amzDate, credentialScope, sha256Hex(canonicalRequest.encodeToByteArray()))
            .joinToString("\n")
        val signingKey = listOf(dateStamp, request.region, request.service, TERMINATOR)
            .fold(
                "AWS4${credentials.secretAccessKey}".encodeToByteArray()
            ) { key, part -> CryptoProvider.hmacSha256(key, part.encodeToByteArray()) }
        val signature = CryptoProvider.hmacSha256(signingKey, stringToSign.encodeToByteArray()).toHexString()

        return AwsSigV4Headers(
            authorization = "$ALGORITHM Credential=${credentials.accessKeyId}/$credentialScope, " +
                "SignedHeaders=$signedHeaders, Signature=$signature",
            amzDate = amzDate,
            payloadSha256 = payloadHash,
            sessionToken = sessionToken,
        )
    }

    /** AWS `UriEncode`: unreserved `A-Za-z0-9-._~` kept, every other UTF-8 byte as uppercase `%XX` (`/` too). */
    fun uriEncode(value: String): String =
        buildString {
            value.encodeToByteArray().forEach { byte ->
                val code = byte.toInt() and BYTE_MASK
                val char = Char(code)
                if (code < ASCII_LIMIT && (char.isLetterOrDigit() || char in UNRESERVED_MARKS)) {
                    append(char)
                } else {
                    append('%').append(HEX_DIGITS[code shr NIBBLE_BITS]).append(HEX_DIGITS[code and NIBBLE_MASK])
                }
            }
        }

    private fun canonicalUri(path: String): String = path.ifEmpty { "/" }.split('/').joinToString("/") { uriEncode(percentDecode(it)) }

    private fun canonicalQuery(query: String): String =
        query
            .split('&')
            .filter { it.isNotEmpty() }
            .map { uriEncode(percentDecode(it.substringBefore('='))) to uriEncode(percentDecode(it.substringAfter('=', ""))) }
            .sortedWith(compareBy({ it.first }, { it.second }))
            .joinToString("&") { "${it.first}=${it.second}" }

    private fun collapseWhitespace(value: String): String = value.split(' ', '\t').filter { it.isNotEmpty() }.joinToString(" ")

    /** Decodes `%XX` escapes (UTF-8); a `%` not followed by two hex digits stays literal, `+` is not a space. */
    private fun percentDecode(value: String): String {
        if ('%' !in value) return value
        val source = value.encodeToByteArray()
        val out = mutableListOf<Byte>()
        var index = 0
        while (index < source.size) {
            val escaped = if (source[index] == '%'.code.toByte() && index + 2 < source.size) hexByte(source, index + 1) else null
            if (escaped != null) {
                out.add(escaped)
                index += ESCAPE_LENGTH
            } else {
                out.add(source[index])
                index++
            }
        }
        return out.toByteArray().decodeToString()
    }

    private fun hexByte(
        source: ByteArray,
        at: Int,
    ): Byte? {
        val high = Char(source[at].toInt() and BYTE_MASK).digitToIntOrNull(HEX_RADIX) ?: return null
        val low = Char(source[at + 1].toInt() and BYTE_MASK).digitToIntOrNull(HEX_RADIX) ?: return null
        return ((high shl NIBBLE_BITS) or low).toByte()
    }

    private fun sha256Hex(value: ByteArray): String = CryptoProvider.sha256(value).toHexString()

    private fun formatAmzDates(now: Instant): Pair<String, String> {
        val utc = now.toLocalDateTime(TimeZone.UTC)
        val dateStamp = utc.year.toString().padStart(4, '0') + pad2(utc.month.ordinal + 1) + pad2(utc.day)
        val amzDate = dateStamp + "T" + pad2(utc.hour) + pad2(utc.minute) + pad2(utc.second) + "Z"
        return amzDate to dateStamp
    }

    private fun pad2(value: Int): String = value.toString().padStart(2, '0')
}
