package io.karandash.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalEncodingApi::class)
class JwtTest {
    private val keyPair = CryptographyProvider.Default
        .get(RSA.PKCS1)
        .keyPairGenerator(digest = SHA256)
        .generateKeyBlocking()
    private val publicKeyDer = keyPair.publicKey.encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)
    private val claims = buildJsonObject {
        put("iat", 1_700_000_000)
        put("exp", 1_700_000_540)
        put("iss", "12345")
    }

    @Test
    fun signRs256ProducesAVerifiableTokenFromPkcs1AndPkcs8Pem() {
        for (format in listOf(RSA.PrivateKey.Format.PEM.PKCS1, RSA.PrivateKey.Format.PEM)) {
            val pem = keyPair.privateKey.encodeToByteArrayBlocking(format).decodeToString()
            val parts = Jwt.signRs256(claims, pem).split(".")
            assertEquals(3, parts.size, "JWT has three parts ($format)")
            assertEquals("RS256", decode(parts[0])["alg"]?.jsonPrimitive?.content)
            assertEquals(claims, decode(parts[1]))
            val signature = base64Url.decode(parts[2])
            val signingInput = "${parts[0]}.${parts[1]}".encodeToByteArray()
            assertTrue(CryptoProvider.verifyRsaSha256Signature(signingInput, signature, publicKeyDer), "signature ($format)")
            assertTrue(parts.none { it.contains('=') }, "base64url without padding")
        }
    }

    @Test
    fun pemToDerAcceptsEscapedNewlinesFromASingleLineEnvironmentVariable() {
        val pem = keyPair.privateKey.encodeToByteArrayBlocking(RSA.PrivateKey.Format.PEM.PKCS1).decodeToString()
        val der = keyPair.privateKey.encodeToByteArrayBlocking(RSA.PrivateKey.Format.DER.PKCS1)
        assertTrue(der.contentEquals(Jwt.pemToDer(pem.replace("\n", "\\n"))))
    }

    @Test
    fun pemToDerRejectsAnEmptyBody() {
        assertFailsWith<IllegalArgumentException> { Jwt.pemToDer("-----BEGIN RSA PRIVATE KEY-----\n-----END RSA PRIVATE KEY-----") }
    }

    private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

    private fun decode(part: String): JsonObject = Json.parseToJsonElement(base64Url.decode(part).decodeToString()).jsonObject
}
