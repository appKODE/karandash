package io.karandash.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CryptoProviderTest {
    // --- SHA-256 known test vectors ---

    @Test
    fun sha256OfEmptyBytesMatchesKnownHash() {
        val hash = CryptoProvider.sha256(ByteArray(0))
        val hex = hash.toHexString()
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex,
            "SHA-256 of empty input must match NIST known-answer",
        )
    }

    @Test
    fun sha256OfHelloWorldMatchesKnownHash() {
        val data = "hello world".encodeToByteArray()
        val hash = CryptoProvider.sha256(data)
        val hex = hash.toHexString()
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            hex,
            "SHA-256 of 'hello world' must match known hash",
        )
    }

    @Test
    fun sha256OfAbcMatchesKnownHash() {
        val data = "abc".encodeToByteArray()
        val hash = CryptoProvider.sha256(data)
        val hex = hash.toHexString()
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex,
            "SHA-256 of 'abc' must match NIST test vector",
        )
    }

    @Test
    fun sha1OfAbcMatchesKnownHash() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1Hex("abc"), "SHA-1 of 'abc' must match FIPS 180 vector")
        assertEquals(20, CryptoProvider.sha1(ByteArray(0)).size)
    }

    @Test
    fun sha256ProducesDeterministicOutput() {
        val data = "deterministic test".encodeToByteArray()
        val hash1 = CryptoProvider.sha256(data).toHexString()
        val hash2 = CryptoProvider.sha256(data).toHexString()
        assertEquals(hash1, hash2, "SHA-256 must produce identical output for identical input")
    }

    // --- RSA signature verification with pre-computed test vectors ---

    private val testPublicKeyDerHex =
        "30820122300d06092a864886f70d01010105000382010f003082010a0282010100" +
            "af44f30b34e1b0a334a8f9ef736a4921e0d5ea7ddaced2d53d8e3a2ad76db7db" +
            "a1f33665d1811eac5d5108204b9e8931bf0f0b2b5fe6f3edf6a094c4ea1fe1d8" +
            "8e1651c971660d19c60fba0c5b91c8570f9f75d64c047cc15e5d835640569cfc" +
            "8f1bb3cd5f5044ecafdb58333ce214f989a59830aa0ade3beadd4629b85b74ae" +
            "2ca8e8198f63ddc0794c085a59a7140d5e0b53253c1f3761b1bfd867bf0e7182" +
            "34e553d977dae0be6309154f26215a6e5c94e26dfa449a39e2e68825897969d2" +
            "d70f84021bd9318f34e9d7f7ffb2bb022cc38701969363638a890c54ccddd4a5" +
            "c2ae4ec7fb66f91872ad3401a428fc25efe0e510e7fa8d5aaa4f4462582c8785" +
            "0203010001"

    private val testSignatureHex =
        "9697136808267d83f212a41980d34c1cdaa196cef1a7922c18cdb7071fbd0c40" +
            "a9e47cdf85238b708996c07fed2c3861a0b27a3adfeae295a4c82ae6897c8208" +
            "688e56d25ff51685b1de68e4de8087db64c4eb8d4abe33c68a56afb78c7a99d5" +
            "fdc54af37814a87afdac90146c47bb45ef8c5d45f209263ddfa126818813b209" +
            "d7482c71a0db5e7f802541d123fd2b9446bc59c5da3c38a69ce53be097aa9ac8" +
            "c99e7ab3e12abd6be5c5e44839bc3da09de84c7c78755d95be788ce5c243b504" +
            "9067bad98d0efbb803d819bcc0496e1b7e0d5ef19a37308ddda734b30e4efe1c" +
            "64affa5e21062914a4132a3e87d5d351c36912dd6aa8efb5091d20cc66e0fdf3"

    private val testDataHex = "74657374206461746120666f72207369676e696e670a"

    private fun hexToByteArray(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string must have even length" }
        return ByteArray(len / 2) { i ->
            val hi = hexCharToInt(hex[i * 2])
            val lo = hexCharToInt(hex[i * 2 + 1])
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun hexCharToInt(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> error("Invalid hex char: $c")
        }

    @Test
    fun verifyRsaSha256SignatureSucceedsWithValidTestVector() {
        val data = hexToByteArray(testDataHex)
        val signature = hexToByteArray(testSignatureHex)
        val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

        val result = CryptoProvider.verifyRsaSha256Signature(data, signature, publicKeyDer)
        assertTrue(result, "Valid RSA-SHA256 signature must verify successfully")
    }

    @Test
    fun verifyRsaSha256SignatureFailsWithTamperedData() {
        val data = hexToByteArray(testDataHex)
        val signature = hexToByteArray(testSignatureHex)
        val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

        val tamperedData = data.copyOf()
        tamperedData[0] = (tamperedData[0].toInt() xor 0xFF).toByte()

        val result = CryptoProvider.verifyRsaSha256Signature(tamperedData, signature, publicKeyDer)
        assertFalse(result, "Signature must fail verification with tampered data")
    }

    @Test
    fun verifyRsaSha256SignatureFailsWithTamperedSignature() {
        val data = hexToByteArray(testDataHex)
        val signature = hexToByteArray(testSignatureHex)
        val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

        val tamperedSig = signature.copyOf()
        tamperedSig[0] = (tamperedSig[0].toInt() xor 0xFF).toByte()

        val result = CryptoProvider.verifyRsaSha256Signature(data, tamperedSig, publicKeyDer)
        assertFalse(result, "Signature must fail verification with tampered signature")
    }

    @Test
    fun verifyRsaSha256SignatureFailsWithEmptyInputs() {
        val publicKeyDer = hexToByteArray(testPublicKeyDerHex)
        val signature = hexToByteArray(testSignatureHex)

        assertFalse(
            CryptoProvider.verifyRsaSha256Signature(ByteArray(0), signature, ByteArray(0)),
            "Must return false for empty public key",
        )
        assertFalse(
            CryptoProvider.verifyRsaSha256Signature(ByteArray(0), ByteArray(0), publicKeyDer),
            "Must return false for empty signature",
        )
    }

    // --- publicKeyFingerprint ---

    @Test
    fun publicKeyFingerprintProducesConsistentOutput() {
        val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

        val fp1 = CryptoProvider.publicKeyFingerprint(publicKeyDer)
        val fp2 = CryptoProvider.publicKeyFingerprint(publicKeyDer)

        assertEquals(fp1, fp2, "Fingerprint must be consistent across invocations")
        assertEquals(64, fp1.length, "Fingerprint must be 64 hex chars (SHA-256)")
        assertTrue(fp1.all { it in '0'..'9' || it in 'a'..'f' }, "Fingerprint must be lowercase hex")
    }

    @Test
    fun publicKeyFingerprintIsSha256OfDerBytes() {
        val publicKeyDer = hexToByteArray(testPublicKeyDerHex)
        val expectedFingerprint = CryptoProvider.sha256(publicKeyDer).toHexString()
        val actualFingerprint = CryptoProvider.publicKeyFingerprint(publicKeyDer)

        assertEquals(
            expectedFingerprint,
            actualFingerprint,
            "publicKeyFingerprint must equal SHA-256 of the DER bytes",
        )
    }

    /** RFC 4231 test case 2 and case 6 (key longer than the block size). */
    @Test
    fun `hmacSha256 matches the RFC 4231 vectors`() {
        assertEquals(
            "5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843",
            CryptoProvider.hmacSha256("Jefe".encodeToByteArray(), "what do ya want for nothing?".encodeToByteArray()).toHexString(),
        )
        assertEquals(
            "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54",
            CryptoProvider
                .hmacSha256(
                    ByteArray(131) { 0xAA.toByte() },
                    "Test Using Larger Than Block-Size Key - Hash Key First".encodeToByteArray(),
                ).toHexString(),
        )
    }

    // --- HexUtils ---

    @Test
    fun toHexStringProducesCorrectOutput() {
        assertEquals("", ByteArray(0).toHexString())
        assertEquals("00", byteArrayOf(0).toHexString())
        assertEquals("ff", byteArrayOf(-1).toHexString())
        assertEquals("0102030405", byteArrayOf(1, 2, 3, 4, 5).toHexString())
        assertEquals(
            "deadbeef",
            byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()).toHexString(),
        )
    }

    /** `plugin sign` takes both DER layouts on every platform: PKCS#8, and the PKCS#1 that OpenSSL 3 `genpkey` writes. */
    @Test
    fun signRsaSha256AcceptsPkcs8AndPkcs1Keys() {
        val keyPair = CryptographyProvider.Default
            .get(RSA.PKCS1)
            .keyPairGenerator(digest = SHA256)
            .generateKeyBlocking()
        val publicKeyDer = keyPair.publicKey.encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)
        val data = "plugin".encodeToByteArray()
        for (format in listOf(RSA.PrivateKey.Format.DER, RSA.PrivateKey.Format.DER.PKCS1)) {
            val signature = CryptoProvider.signRsaSha256(data, keyPair.privateKey.encodeToByteArrayBlocking(format))
            assertTrue(CryptoProvider.verifyRsaSha256Signature(data, signature, publicKeyDer), "signing with $format")
        }
    }
}
