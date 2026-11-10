package io.karandash.loader

import io.karandash.crypto.KeyManager
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeyManagerTest {
    private lateinit var tempDir: Path
    private val createdFiles = mutableListOf<Path>()
    private var fileCounter = 0

    // RSA-2048 public key in X.509 DER (SubjectPublicKeyInfo) format, hex-encoded.
    // Same test vector used in CryptoProviderTest.
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

    // A second distinct RSA-2048 public key for multi-key tests.
    // Same DER structure as testPublicKeyDerHex but with first byte of modulus changed (af -> bf).
    private val testPublicKeyDer2Hex =
        "30820122300d06092a864886f70d01010105000382010f003082010a0282010100" +
            "bf44f30b34e1b0a334a8f9ef736a4921e0d5ea7ddaced2d53d8e3a2ad76db7db" +
            "a1f33665d1811eac5d5108204b9e8931bf0f0b2b5fe6f3edf6a094c4ea1fe1d8" +
            "8e1651c971660d19c60fba0c5b91c8570f9f75d64c047cc15e5d835640569cfc" +
            "8f1bb3cd5f5044ecafdb58333ce214f989a59830aa0ade3beadd4629b85b74ae" +
            "2ca8e8198f63ddc0794c085a59a7140d5e0b53253c1f3761b1bfd867bf0e7182" +
            "34e553d977dae0be6309154f26215a6e5c94e26dfa449a39e2e68825897969d2" +
            "d70f84021bd9318f34e9d7f7ffb2bb022cc38701969363638a890c54ccddd4a5" +
            "c2ae4ec7fb66f91872ad3401a428fc25efe0e510e7fa8d5aaa4f4462582c8785" +
            "0203010001"

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("key-manager-test")
        tempDir.parent?.let { createDirectories(it) }
        createDirectories(tempDir)
    }

    @AfterTest
    fun cleanup() {
        // Clean up all created files and the temp directory
        for (file in createdFiles) {
            deleteIfExists(file)
        }
        createdFiles.clear()
        // Clean up tempDir contents
        tempDir.listFiles()?.forEach { deleteIfExists(it) }
        deleteIfExists(tempDir)
    }

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

    private fun writeTestKey(
        fileName: String,
        keyHex: String = testPublicKeyDerHex
    ): Path {
        val keyFile = Path("$tempDir/$fileName")
        keyFile.writeBytes(hexToByteArray(keyHex))
        createdFiles.add(Path(keyFile.toString()))
        return keyFile
    }

    @Test
    fun importKeySucceedsWithValidRsaPublicKey() {
        val keyManager = KeyManager(tempDir)
        val publicKeyFile = writeTestKey("test-key.pub")

        val result = keyManager.importKey(publicKeyFile, "Test key")

        assertTrue(result.isSuccess, "Import should succeed")
        val keyId = result.getOrThrow()
        assertNotNull(keyId)
        assertEquals(16, keyId.length, "Key ID should be 16 characters (first 16 of SHA-256)")
    }

    @Test
    fun importKeyFailsWithNonExistentFile() {
        val keyManager = KeyManager(tempDir)
        val nonExistentFile = Path("$tempDir/non-existent.pub")

        val result = keyManager.importKey(nonExistentFile)

        assertTrue(result.isFailure, "Import should fail")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("not found", ignoreCase = true) == true,
            "Error message should mention 'not found'"
        )
    }

    @Test
    fun importKeyFailsWithInvalidKeyFormat() {
        val keyManager = KeyManager(tempDir)
        val invalidKeyFile = Path("$tempDir/invalid.pub")
        invalidKeyFile.writeBytes("not a valid key".encodeToByteArray())
        createdFiles.add(Path(invalidKeyFile.toString()))

        val result = keyManager.importKey(invalidKeyFile)

        assertTrue(result.isFailure, "Import should fail with invalid key")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Invalid public key format", ignoreCase = true) == true,
            "Error message should mention 'Invalid public key format'"
        )
    }

    @Test
    fun importKeyPreventsDuplicateImports() {
        val keyManager = KeyManager(tempDir)
        val publicKeyFile = writeTestKey("test-key.pub")

        // Import once
        val result1 = keyManager.importKey(publicKeyFile, "First import")
        assertTrue(result1.isSuccess)

        // Try to import again
        val result2 = keyManager.importKey(publicKeyFile, "Second import")
        assertTrue(result2.isFailure, "Duplicate import should fail")
        assertTrue(
            result2.exceptionOrNull()?.message?.contains("already imported", ignoreCase = true) == true,
            "Error message should mention 'already imported'"
        )
    }

    @Test
    fun listTrustedKeysReturnsEmptyListInitially() {
        val keyManager = KeyManager(tempDir)

        val keys = keyManager.listTrustedKeys()

        assertTrue(keys.isEmpty(), "Should have no trusted keys initially")
    }

    @Test
    fun listTrustedKeysReturnsImportedKeys() {
        val keyManager = KeyManager(tempDir)
        val publicKeyFile = writeTestKey("test-key.pub")

        val keyId = keyManager.importKey(publicKeyFile, "Test key").getOrThrow()

        val keys = keyManager.listTrustedKeys()
        assertEquals(1, keys.size)
        assertEquals(keyId, keys[0].id)
        assertEquals("Test key", keys[0].description)
        assertNotNull(keys[0].addedDate)
    }

    @Test
    fun exportKeyWritesKeyToOutputPath() {
        val keyManager = KeyManager(tempDir)
        val publicKeyFile = writeTestKey("test-key.pub")
        val originalBytes = hexToByteArray(testPublicKeyDerHex)

        val keyId = keyManager.importKey(publicKeyFile).getOrThrow()
        val exportFile = Path("$tempDir/exported.pub")
        createdFiles.add(Path(exportFile.toString()))

        val result = keyManager.exportKey(keyId, exportFile)

        assertTrue(result.isSuccess, "Export should succeed")
        assertTrue(exportFile.exists(), "Exported file should exist")

        val exportedBytes = exportFile.readBytes()
        assertContentEquals(originalBytes, exportedBytes, "Exported key should match original")
    }

    @Test
    fun exportKeyFailsForNonExistentKey() {
        val keyManager = KeyManager(tempDir)
        val exportFile = Path("$tempDir/exported.pub")
        createdFiles.add(Path(exportFile.toString()))

        val result = keyManager.exportKey("non-existent-key-id", exportFile)

        assertTrue(result.isFailure, "Export should fail for non-existent key")
    }

    @Test
    fun revokeKeyRemovesKeyFromStore() {
        val keyManager = KeyManager(tempDir)
        val publicKeyFile = writeTestKey("test-key.pub")

        val keyId = keyManager.importKey(publicKeyFile, "Test key").getOrThrow()
        assertEquals(1, keyManager.listTrustedKeys().size)

        val result = keyManager.revokeKey(keyId)

        assertTrue(result.isSuccess, "Revoke should succeed")
        assertEquals(0, keyManager.listTrustedKeys().size, "Key should be removed from list")
        assertFalse(
            Path("$tempDir/$keyId.pub").exists(),
            "Key file should be deleted"
        )
    }

    @Test
    fun revokeKeyFailsForNonExistentKey() {
        val keyManager = KeyManager(tempDir)

        val result = keyManager.revokeKey("non-existent-key-id")

        assertTrue(result.isFailure, "Revoke should fail for non-existent key")
    }

    @Test
    fun loadPublicKeyReturnsValidDerBytes() {
        val keyManager = KeyManager(tempDir)
        val publicKeyFile = writeTestKey("test-key.pub")
        val originalBytes = hexToByteArray(testPublicKeyDerHex)

        val keyId = keyManager.importKey(publicKeyFile).getOrThrow()

        val result = keyManager.loadPublicKey(keyId)

        assertTrue(result.isSuccess, "Load should succeed")
        val keyBytes = result.getOrThrow()
        assertContentEquals(originalBytes, keyBytes, "Loaded key should match original DER bytes")
    }

    @Test
    fun loadPublicKeyFailsForNonExistentKey() {
        val keyManager = KeyManager(tempDir)

        val result = keyManager.loadPublicKey("non-existent-key-id")

        assertTrue(result.isFailure, "Load should fail for non-existent key")
    }

    @Test
    fun allPublicKeysReturnsAllImportedKeys() {
        val keyManager = KeyManager(tempDir)

        // Import two keys
        val keyFile1 = writeTestKey("key1.pub", testPublicKeyDerHex)
        keyManager.importKey(keyFile1, "Key 1")

        val keyFile2 = writeTestKey("key2.pub", testPublicKeyDer2Hex)
        keyManager.importKey(keyFile2, "Key 2")

        val allKeys = keyManager.allPublicKeys()

        assertEquals(2, allKeys.size, "Should have 2 public keys")
        // Verify they are valid DER key bytes (non-empty)
        assertTrue(allKeys.all { it.isNotEmpty() }, "All keys should be non-empty byte arrays")
    }

    @Test
    fun keyMetadataPersistsAcrossKeyManagerInstances() {
        val publicKeyFile = writeTestKey("test-key.pub")

        // Import key with first instance
        val keyManager1 = KeyManager(tempDir)
        val keyId = keyManager1.importKey(publicKeyFile, "Persistent key").getOrThrow()

        // Create new instance and verify key is still there
        val keyManager2 = KeyManager(tempDir)
        val keys = keyManager2.listTrustedKeys()

        assertEquals(1, keys.size)
        assertEquals(keyId, keys[0].id)
        assertEquals("Persistent key", keys[0].description)
    }

    // === Negative Test Cases: DER Validation Edge Cases ===

    @Test
    fun importKeyRejectsKeySmallerThan32Bytes() {
        val keyManager = KeyManager(tempDir)
        val tinyKeyFile = Path("$tempDir/tiny-key.pub")
        val tinyKey = ByteArray(31) { 0x30.toByte() } // 31 bytes, starts with valid tag but too small
        tinyKeyFile.writeBytes(tinyKey)
        createdFiles.add(Path(tinyKeyFile.toString()))

        val result = keyManager.importKey(tinyKeyFile, "tiny-key")

        assertTrue(result.isFailure, "Should reject key < 32 bytes")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Invalid public key format", ignoreCase = true) == true,
            "Error message should mention 'Invalid public key format'"
        )
    }

    @Test
    fun importKeyRejectsKeyWithWrongAsn1Tag() {
        val keyManager = KeyManager(tempDir)
        val invalidTagFile = Path("$tempDir/wrong-tag.pub")
        // Create a 300-byte array starting with 0x31 (not 0x30 which is required SEQUENCE tag)
        val invalidTagKey = ByteArray(300) { i ->
            if (i == 0) 0x31.toByte() else 0x00.toByte()
        }
        invalidTagFile.writeBytes(invalidTagKey)
        createdFiles.add(Path(invalidTagFile.toString()))

        val result = keyManager.importKey(invalidTagFile, "wrong-tag")

        assertTrue(result.isFailure, "Should reject key without 0x30 SEQUENCE tag")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Invalid public key format", ignoreCase = true) == true,
            "Error message should mention 'Invalid public key format'"
        )
    }

    @Test
    fun importKeyRejectsKeyWithoutRsaOid() {
        val keyManager = KeyManager(tempDir)
        val noOidFile = Path("$tempDir/no-oid.pub")
        // Create a valid ASN.1 structure (starts with 0x30) but without RSA OID
        val noOidKey = ByteArray(300) { i ->
            if (i == 0) 0x30.toByte() else 0x00.toByte()
        }
        noOidFile.writeBytes(noOidKey)
        createdFiles.add(Path(noOidFile.toString()))

        val result = keyManager.importKey(noOidFile, "no-oid")

        assertTrue(result.isFailure, "Should reject key without RSA OID")
        assertTrue(
            result.exceptionOrNull()?.message?.contains("Invalid public key format", ignoreCase = true) == true,
            "Error message should mention 'Invalid public key format'"
        )
    }

    // === Negative Test Cases: Corrupted Index Handling ===

    @Test
    fun corruptedIndexReturnsEmptyListForListTrustedKeys() {
        val keyManager = KeyManager(tempDir)
        createDirectories(tempDir)

        // Write corrupted JSON to the index file
        val indexFile = Path("$tempDir/trusted-keys.json")
        indexFile.writeBytes("{corrupted json}".encodeToByteArray())
        createdFiles.add(Path(indexFile.toString()))

        val keys = keyManager.listTrustedKeys()

        assertTrue(keys.isEmpty(), "Should return empty list when index is corrupted (graceful degradation)")
    }

    /** A corrupt index is kept for the user to fix, not silently replaced. */
    @Test
    fun corruptedIndexIsNotOverwrittenByImport() {
        createDirectories(tempDir)

        val indexFile = Path("$tempDir/trusted-keys.json")
        indexFile.writeBytes("{corrupted json}".encodeToByteArray())
        createdFiles.add(Path(indexFile.toString()))

        val keyManager1 = KeyManager(tempDir)
        assertTrue(keyManager1.listTrustedKeys().isEmpty(), "Should initially return empty list due to corruption")

        val result = keyManager1.importKey(writeTestKey("recovery-key.pub"), "Recovery key")

        assertTrue(result.isFailure, "Import must refuse to overwrite a corrupt index")
        assertEquals("{corrupted json}", indexFile.readBytes().decodeToString())
    }

    @Test
    fun indexWithMalformedDateHandledGracefully() {
        createDirectories(tempDir)

        // Create an index with invalid Instant format in addedDate
        val corruptedJson = """
            [
                {
                    "id": "malformed-date-id",
                    "fingerprint": "abc123",
                    "addedDate": "not-a-valid-instant",
                    "description": "Malformed date entry"
                }
            ]
        """.trimIndent()

        val indexFile = Path("$tempDir/trusted-keys.json")
        indexFile.writeBytes(corruptedJson.encodeToByteArray())
        createdFiles.add(Path(indexFile.toString()))

        val keyManager = KeyManager(tempDir)
        val keys = keyManager.listTrustedKeys()

        // loadIndex() catches all exceptions and returns empty list
        assertTrue(keys.isEmpty(), "Should handle malformed date gracefully by returning empty list")
    }

    // === Multi-key store behavior ===

    @Test
    fun revokingOneKeyKeepsTheOtherKeyAndItsMetadata() {
        val keyManager = KeyManager(tempDir)
        val keyId1 = keyManager.importKey(writeTestKey("key1.pub", testPublicKeyDerHex), "Key 1").getOrThrow()
        val keyId2 = keyManager.importKey(writeTestKey("key2.pub", testPublicKeyDer2Hex), "Key 2").getOrThrow()

        keyManager.revokeKey(keyId1).getOrThrow()

        val remaining = KeyManager(tempDir).listTrustedKeys()
        assertEquals(listOf(keyId2), remaining.map { it.id })
        assertEquals("Key 2", remaining.single().description)
        assertContentEquals(hexToByteArray(testPublicKeyDer2Hex), keyManager.allPublicKeys().single())
    }

    @Test
    fun allPublicKeysSkipsIndexEntriesWhoseKeyFileIsGone() {
        val keyManager = KeyManager(tempDir)
        val keyId1 = keyManager.importKey(writeTestKey("key1.pub", testPublicKeyDerHex)).getOrThrow()
        keyManager.importKey(writeTestKey("key2.pub", testPublicKeyDer2Hex)).getOrThrow()

        deleteIfExists(Path("$tempDir/$keyId1.pub"))

        assertEquals(2, keyManager.listTrustedKeys().size, "index still lists both")
        assertContentEquals(hexToByteArray(testPublicKeyDer2Hex), keyManager.allPublicKeys().single())
        assertTrue(keyManager.revokeKey(keyId1).isFailure, "a key without its file cannot be revoked")
    }

    @Test
    fun exportedKeyCanBeReImportedIntoAnotherStoreUnderTheSameId() {
        val keyManager = KeyManager(tempDir)
        val keyId = keyManager.importKey(writeTestKey("source.pub"), null).getOrThrow()
        val exported = Path("$tempDir/exported.der")
        createdFiles.add(exported)
        keyManager.exportKey(keyId, exported).getOrThrow()

        val otherDir = Path("$tempDir/other-store")
        val other = KeyManager(otherDir)
        val reimportedId = other.importKey(exported).getOrThrow()

        assertEquals(keyId, reimportedId)
        assertEquals(null, other.listTrustedKeys().single().description)
        otherDir.listFiles()?.forEach { deleteIfExists(it) }
        deleteIfExists(otherDir)
    }

    @Test
    fun rejectedImportLeavesTheStoreUntouched() {
        val keyManager = KeyManager(tempDir)
        val bogus = Path("$tempDir/bogus.pub")
        bogus.writeBytes(ByteArray(64) { 0x30 })
        createdFiles.add(bogus)

        assertTrue(keyManager.importKey(bogus).isFailure)

        assertFalse(Path("$tempDir/trusted-keys.json").exists(), "no index written for a rejected key")
        assertTrue(keyManager.allPublicKeys().isEmpty())
    }
}
