package io.karandash.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA1
import dev.whyoleg.cryptography.algorithms.SHA256
import io.karandash.api.support.suspendCatching

/**
 * Multiplatform cryptographic operations for plugin signature verification.
 */
object CryptoProvider {
    private val provider = CryptographyProvider.Default

    @OptIn(DelicateCryptographyApi::class)
    private val sha1 = provider.get(SHA1)
    private val sha256 = provider.get(SHA256)
    private val rsaPkcs1 = provider.get(RSA.PKCS1)
    private val hmac = provider.get(HMAC)

    /**
     * Compute SHA-256 hash of the given data.
     *
     * @param data The bytes to hash.
     * @return 32-byte SHA-256 digest.
     */
    fun sha256(data: ByteArray): ByteArray = sha256.hasher().hashBlocking(data)

    /** SHA-1 of [data], 20 bytes. Not for security: only for identifiers a platform derives with it (GitLab diff anchors). */
    fun sha1(data: ByteArray): ByteArray = sha1.hasher().hashBlocking(data)

    /** HMAC-SHA256 of [data] under [key] (any key length), 32 bytes. */
    fun hmacSha256(
        key: ByteArray,
        data: ByteArray
    ): ByteArray =
        hmac
            .keyDecoder(SHA256)
            .decodeFromByteArrayBlocking(HMAC.Key.Format.RAW, key)
            .signatureGenerator()
            .generateSignatureBlocking(data)

    /**
     * Verify an RSA-SHA256 signature.
     *
     * @param data The original data that was signed.
     * @param signature The RSA signature bytes.
     * @param publicKeyDer The public key in X.509 DER (SubjectPublicKeyInfo) format.
     * @return true if the signature is valid for the given data and key.
     */
    fun verifyRsaSha256Signature(
        data: ByteArray,
        signature: ByteArray,
        publicKeyDer: ByteArray
    ): Boolean {
        if (publicKeyDer.isEmpty() || signature.isEmpty()) return false

        return suspendCatching {
            val verifier = rsaPkcs1
                .publicKeyDecoder(SHA256)
                .decodeFromByteArrayBlocking(RSA.PublicKey.Format.DER.Generic, publicKeyDer)
                .signatureVerifier()
            verifier.tryVerifySignatureBlocking(data, signature)
        }.getOrDefault(false)
    }

    /**
     * Sign data with an RSA-SHA256 private key.
     *
     * @param data The data to sign.
     * @param privateKeyDer The private key in DER, PKCS#8 or PKCS#1 (what OpenSSL 3 `genpkey -outform DER` writes).
     * @return The RSA-SHA256 signature bytes.
     */
    fun signRsaSha256(
        data: ByteArray,
        privateKeyDer: ByteArray
    ): ByteArray {
        val signer = rsaPkcs1
            .privateKeyDecoder(SHA256)
            .decodeFromByteArrayBlocking(privateKeyDerFormat(privateKeyDer), privateKeyDer)
            .signatureGenerator()
        return signer.generateSignatureBlocking(data)
    }

    /**
     * Compute a fingerprint of a public key (SHA-256 of DER bytes, hex-encoded).
     *
     * @param publicKeyDer The public key in X.509 DER format.
     * @return Lowercase hex string of the SHA-256 fingerprint.
     */
    fun publicKeyFingerprint(publicKeyDer: ByteArray): String = sha256(publicKeyDer).toHexString()

    /**
     * PKCS#1 `RSAPrivateKey` is SEQUENCE { INTEGER version, INTEGER modulus, ... }, PKCS#8 has an AlgorithmIdentifier
     * SEQUENCE after the version: the tag after the outer header and the 3-byte version tells them apart. The JDK
     * decodes only PKCS#8 as generic DER, OpenSSL both, so naming the format keeps JVM and native in step.
     */
    private fun privateKeyDerFormat(der: ByteArray): RSA.PrivateKey.Format.DER {
        val length = der.getOrNull(1)?.toInt()?.and(BYTE_MASK) ?: return RSA.PrivateKey.Format.DER.Generic
        val versionAt = 2 + if (length and LONG_FORM_BIT != 0) length and LONG_FORM_BIT.inv() else 0
        val afterVersion = der.getOrNull(versionAt + VERSION_SIZE)
        return if (afterVersion == INTEGER_TAG) RSA.PrivateKey.Format.DER.PKCS1 else RSA.PrivateKey.Format.DER.Generic
    }

    private const val BYTE_MASK = 0xFF
    private const val LONG_FORM_BIT = 0x80
    private const val VERSION_SIZE = 3
    private const val INTEGER_TAG: Byte = 0x02
}
