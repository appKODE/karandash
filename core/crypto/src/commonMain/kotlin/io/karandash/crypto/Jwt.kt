package io.karandash.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Compact JSON Web Tokens signed with RS256 (RSASSA-PKCS1-v1_5 over SHA-256), as GitHub Apps use to
 * authenticate as the app.
 */
object Jwt {
    @OptIn(ExperimentalEncodingApi::class)
    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private val header = buildJsonObject {
        put("alg", "RS256")
        put("typ", "JWT")
    }

    /**
     * Signs [claims] with [privateKeyPem], an RSA private key in PEM (`BEGIN RSA PRIVATE KEY`, PKCS#1, or
     * `BEGIN PRIVATE KEY`, PKCS#8).
     *
     * @return `header.payload.signature`, each part base64url without padding.
     * @throws IllegalArgumentException when the PEM holds no key.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun signRs256(
        claims: JsonObject,
        privateKeyPem: String,
    ): String {
        val signingInput = encode(header.toString().encodeToByteArray()) + "." + encode(claims.toString().encodeToByteArray())
        val signature = CryptoProvider.signRsaSha256(signingInput.encodeToByteArray(), pemToDer(privateKeyPem))
        return signingInput + "." + encode(signature)
    }

    /**
     * The DER bytes inside a PEM block. Tolerates the literal `\n` escapes a key gets when it is pasted into a
     * single-line environment variable.
     *
     * @throws IllegalArgumentException when no base64 body is found.
     */
    @OptIn(ExperimentalEncodingApi::class)
    fun pemToDer(pem: String): ByteArray {
        val body = pem
            .replace("\\n", "\n")
            .lines()
            .filterNot { it.trimStart().startsWith("-----") }
            .joinToString("")
            .filter { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' }
        require(body.isNotEmpty()) { "PEM key has no base64 body" }
        return Base64.Default.decode(body)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun encode(bytes: ByteArray): String = base64Url.encode(bytes)
}
