package io.karandash.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.karandash.api.exception.PluginSecurityException
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.exists
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
import io.karandash.platform.readBytes
import io.karandash.platform.writeBytes
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SigningAndKeyStoreTest {
    private companion object {
        private fun keyPair() =
            CryptographyProvider.Default
                .get(RSA.PKCS1)
                .keyPairGenerator(digest = SHA256)
                .generateKeyBlocking()

        val trusted = keyPair()
        val other = keyPair()
        val trustedPublic: ByteArray = trusted.publicKey.encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)
        val otherPublic: ByteArray = other.publicKey.encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)
    }

    private val dir = TestFiles
        .createTestTempDirectory("crypto-test")
        .also { createDirectories(it) }

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun deleteTree(path: Path) {
        if (path.isDirectory()) path.listFiles().orEmpty().forEach { deleteTree(it) }
        deleteIfExists(path)
    }

    private fun file(
        name: String,
        bytes: ByteArray,
    ): Path = Path(dir, name).also { it.writeBytes(bytes) }

    private fun signedJar(): Path {
        val jar = file("plugin.jar", "jar-bytes".encodeToByteArray())
        val key = file("private.der", trusted.privateKey.encodeToByteArrayBlocking(RSA.PrivateKey.Format.DER))
        assertEquals("$jar.sig", signPluginJar(jar.toString(), key.toString()).getOrThrow())
        return jar
    }

    @Test
    fun `signPluginJar writes a signature that verifies only with the matching key`() =
        runTest {
            val jar = signedJar()
            val sig = Path("$jar.sig")

            assertTrue(SignatureVerifier.verifySignature(jar, sig, trustedPublic))
            assertFalse(SignatureVerifier.verifySignature(jar, sig, otherPublic))
            assertFalse(SignatureVerifier.verifySignature(jar, sig, "garbage".encodeToByteArray()))
        }

    @Test
    fun `signPluginJar honours a custom output path and fails for missing inputs`() {
        val jar = file("a.jar", "x".encodeToByteArray())
        val key = file("k.der", trusted.privateKey.encodeToByteArrayBlocking(RSA.PrivateKey.Format.DER))
        val out = Path(dir, "custom.sig").toString()

        assertEquals(out, signPluginJar(jar.toString(), key.toString(), out).getOrThrow())
        assertTrue(Path(out).exists())
        assertIs<IllegalArgumentException>(signPluginJar("$dir/none.jar", key.toString()).exceptionOrNull())
        assertIs<IllegalArgumentException>(signPluginJar(jar.toString(), "$dir/none.der").exceptionOrNull())
        assertTrue(signPluginJar(jar.toString(), jar.toString()).isFailure)
    }

    @Test
    fun `verifySignature is false when the jar or signature is missing or data changed`() =
        runTest {
            val jar = signedJar()
            val sig = Path("$jar.sig")

            assertFalse(SignatureVerifier.verifySignature(Path(dir, "none.jar"), sig, trustedPublic))
            assertFalse(SignatureVerifier.verifySignature(jar, Path(dir, "none.sig"), trustedPublic))
            jar.writeBytes("tampered".encodeToByteArray())
            assertFalse(SignatureVerifier.verifySignature(jar, sig, trustedPublic))
        }

    @Test
    fun `verifyJar succeeds when any trusted key matches`() =
        runTest {
            val jar = signedJar()

            assertTrue(SignatureVerifier.verifyJar(jar, listOf(otherPublic, trustedPublic)).getOrThrow())
        }

    @Test
    fun `verifyJar fails with a security exception for each rejection reason`() =
        runTest {
            val unsigned = file("unsigned.jar", "x".encodeToByteArray())
            val jar = signedJar()

            val reasons = listOf(
                SignatureVerifier.verifyJar(unsigned, listOf(trustedPublic)) to "No signature file",
                SignatureVerifier.verifyJar(jar, emptyList()) to "No trusted keys",
                SignatureVerifier.verifyJar(jar, listOf(otherPublic)) to "no matching trusted key",
            )
            reasons.forEach { (result, message) ->
                val error = assertIs<PluginSecurityException>(result.exceptionOrNull())
                assertTrue(error.message.orEmpty().contains(message), message)
            }
        }

    @Test
    fun `findSignatureFile looks next to the jar`() {
        val jar = file("p.jar", byteArrayOf(1))
        assertNull(SignatureVerifier.findSignatureFile(jar))

        val sig = file("p.jar.sig", byteArrayOf(2))
        assertEquals(sig.toString(), SignatureVerifier.findSignatureFile(jar).toString())
    }

    @Test
    fun `checksum verify matches the file digest in any case`() {
        val data = file("data.bin", "hello world".encodeToByteArray())
        val hex = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9"

        assertEquals(hex, ChecksumVerifier.computeSha256(data))
        assertTrue(ChecksumVerifier.verify(data, "sha256:$hex"))
        assertTrue(ChecksumVerifier.verify(data, "sha256:${hex.uppercase()}"))
        assertFalse(ChecksumVerifier.verify(data, "sha256:${hex.replaceFirst('b', 'c')}"))
        assertFalse(ChecksumVerifier.verify(data, "sha256:${hex.dropLast(2)}"))
    }

    @Test
    fun `checksum verify rejects malformed checksums`() {
        val data = file("data.bin", byteArrayOf(1))
        listOf("md5:abcd", "sha256:", "sha256:xyz", "abc").forEach { checksum ->
            assertFailsWith<IllegalArgumentException>(checksum) { ChecksumVerifier.verify(data, checksum) }
        }
    }

    @Test
    fun `sha256 helpers agree with the digest`() {
        val hex = sha256Hex("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
        assertEquals(hex.take(16).toULong(16).toLong(), sha256First64Bits("abc"))
        assertEquals(sha256First64Bits("x"), sha256First64Bits("x"))
        assertTrue(sha256First64Bits("x") != sha256First64Bits("y"))
    }

    @Test
    fun `key manager imports lists loads exports and revokes a key`() {
        val keys = KeyManager(keyDir = Path(dir, "keys"))
        val keyFile = file("trusted.pub", trustedPublic)

        val id = keys.importKey(keyFile, description = "ci key").getOrThrow()

        assertEquals(CryptoProvider.publicKeyFingerprint(trustedPublic).take(16), id)
        val listed = keys.listTrustedKeys().single()
        assertEquals(id, listed.id)
        assertEquals("ci key", listed.description)
        assertContentEquals(trustedPublic, keys.loadPublicKey(id).getOrThrow())
        assertEquals(1, keys.allPublicKeys().size)

        val exported = Path(dir, "exported.pub")
        keys.exportKey(id, exported).getOrThrow()
        assertContentEquals(trustedPublic, exported.readBytes())

        keys.revokeKey(id).getOrThrow()
        assertEquals(emptyList(), keys.listTrustedKeys())
        assertTrue(keys.loadPublicKey(id).isFailure)
    }

    @Test
    fun `key manager rejects missing invalid and duplicate keys`() {
        val keys = KeyManager(keyDir = Path(dir, "keys"))
        val valid = file("trusted.pub", trustedPublic)
        keys.importKey(valid).getOrThrow()

        val failures = listOf(
            keys.importKey(Path(dir, "none.pub")) to "not found",
            keys.importKey(file("short.pub", byteArrayOf(0x30, 1, 2))) to "Invalid public key",
            keys.importKey(file("notseq.pub", ByteArray(64) { 1 })) to "Invalid public key",
            keys.importKey(file("noOid.pub", ByteArray(64) { if (it == 0) 0x30 else 1 })) to "Invalid public key",
            keys.importKey(valid) to "already imported",
            keys.exportKey("deadbeef", Path(dir, "o")) to "Key not found",
            keys.revokeKey("deadbeef") to "Key not found",
            keys.loadPublicKey("deadbeef") to "Key not found",
        )
        failures.forEach { (result, message) ->
            val error = assertIs<IllegalArgumentException>(result.exceptionOrNull(), message)
            assertTrue(error.message.orEmpty().contains(message), message)
        }
    }

    @Test
    fun `key manager lists a corrupt index as empty and skips keys whose file is gone`() {
        val keyDir = Path(dir, "keys").also { createDirectories(it) }
        Path(keyDir, "trusted-keys.json").writeText("{not json")
        assertEquals(emptyList(), KeyManager(keyDir = keyDir).listTrustedKeys())
        deleteIfExists(Path(keyDir, "trusted-keys.json"))
        val keys = KeyManager(keyDir = keyDir)

        val id = keys.importKey(file("trusted.pub", trustedPublic)).getOrThrow()
        deleteIfExists(Path(keyDir, "$id.pub"))
        assertEquals(1, keys.listTrustedKeys().size)
        assertEquals(emptyList(), keys.allPublicKeys())
    }

    @Test
    fun `default key dir is under the user home`() {
        val keyDir = KeyManager.defaultKeyDir()
        assertEquals("keys", keyDir.name)
        assertEquals(".karandash", keyDir.parent?.name)
    }
}
