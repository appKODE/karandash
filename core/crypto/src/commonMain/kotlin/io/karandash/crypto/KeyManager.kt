package io.karandash.crypto

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.karandash.platform.copyTo
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.exists
import io.karandash.platform.readBytes
import io.karandash.platform.userHomeDirectory
import io.karandash.platform.writeBytes
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Manages trusted public keys for plugin signature verification.
 *
 * Keys are stored in `~/.karandash/keys/` directory with metadata in
 * a `trusted-keys.json` index file.
 */
class KeyManager(
    private val keyDir: Path = defaultKeyDir(),
    private val logger: Logger = NoOpLogger,
) {
    companion object {
        /**
         * Default location for trusted keys: `~/.karandash/keys/`
         */
        fun defaultKeyDir(): Path {
            val home = userHomeDirectory()
            return Path(home, ".karandash/keys")
        }

        private const val INDEX_FILE = "trusted-keys.json"
        private const val KEY_ID_PREFIX_LENGTH = 16
        private const val MIN_PUBLIC_KEY_DER_LENGTH = 32
        private const val PUB_KEY_EXTENSION = ".pub"
        private const val RSA_OID_SEARCH_LIMIT = 20

        private fun keyNotFoundMessage(keyId: String) = "Key not found: $keyId"

        /** Ids are the lowercase-hex fingerprint prefix [importKey] generates; anything else could leave [keyDir]. */
        private fun isValidKeyId(keyId: String) = keyId.isNotEmpty() && keyId.all { it in '0'..'9' || it in 'a'..'f' }

        private fun invalidKeyId(keyId: String) = Result.failure<Nothing>(IllegalArgumentException("Invalid key id: $keyId"))

        private fun corruptIndex() = Result.failure<Nothing>(IOException("Trusted keys index is corrupt; fix or remove $INDEX_FILE first"))

        private fun keyFilePath(
            keyDir: Path,
            keyId: String,
        ) = Path(keyDir, "$keyId$PUB_KEY_EXTENSION")
    }

    private val json = KarandashJson.PrettyIgnoreUnknown

    private val indexPath: Path = Path(keyDir, INDEX_FILE)

    /**
     * Import a public key from a file.
     *
     * @param keyPath Path to the public key file (X.509 DER encoded).
     * @param description Optional description for the key.
     * @return [Result] containing the key ID on success.
     */
    fun importKey(
        keyPath: Path,
        description: String? = null,
    ): Result<String> {
        return try {
            if (!keyPath.exists()) {
                return Result.failure(IllegalArgumentException("Key file not found: $keyPath"))
            }

            val keyBytes = keyPath.readBytes()
            if (!isValidPublicKeyDer(keyBytes)) {
                return Result.failure(
                    IllegalArgumentException("Invalid public key format: not a valid X.509 DER public key")
                )
            }
            val fingerprint = CryptoProvider.publicKeyFingerprint(keyBytes)

            val keyId = fingerprint.take(KEY_ID_PREFIX_LENGTH)

            createDirectories(keyDir)

            val targetPath = keyFilePath(keyDir, keyId)
            if (targetPath.exists()) {
                return Result.failure(IllegalArgumentException("Key already imported: $keyId"))
            }
            val index = loadIndex()?.toMutableList() ?: return corruptIndex()
            keyPath.copyTo(targetPath)

            index.add(
                TrustedKey(
                    id = keyId,
                    fingerprint = fingerprint,
                    addedDate = Clock.System.now(),
                    description = description,
                )
            )
            saveIndex(index)

            Result.success(keyId)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Export a public key to a file.
     *
     * @param keyId The key ID to export.
     * @param output Path where the key should be written.
     * @return [Result] indicating success or failure.
     */
    fun exportKey(
        keyId: String,
        output: Path,
    ): Result<Unit> {
        if (!isValidKeyId(keyId)) return invalidKeyId(keyId)
        return try {
            val sourcePath = keyFilePath(keyDir, keyId)
            if (!sourcePath.exists()) {
                return Result.failure(IllegalArgumentException(keyNotFoundMessage(keyId)))
            }

            sourcePath.copyTo(output)
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * List all trusted keys.
     *
     * @return List of [TrustedKey] metadata.
     */
    fun listTrustedKeys(): List<TrustedKey> = loadIndex().orEmpty()

    /**
     * Revoke a trusted key by removing it from the store.
     *
     * @param keyId The key ID to revoke.
     * @return [Result] indicating success or failure.
     */
    fun revokeKey(keyId: String): Result<Unit> {
        if (!isValidKeyId(keyId)) return invalidKeyId(keyId)
        return try {
            val keyFile = keyFilePath(keyDir, keyId)
            if (!keyFile.exists()) {
                return Result.failure(IllegalArgumentException(keyNotFoundMessage(keyId)))
            }
            val index = loadIndex()?.toMutableList() ?: return corruptIndex()

            deleteIfExists(keyFile)

            index.removeAll { it.id == keyId }
            saveIndex(index)

            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Load a public key's DER bytes by its ID.
     *
     * @param keyId The key ID.
     * @return [Result] containing the public key DER bytes on success.
     */
    fun loadPublicKey(keyId: String): Result<ByteArray> {
        if (!isValidKeyId(keyId)) return invalidKeyId(keyId)
        return try {
            val keyFile = keyFilePath(keyDir, keyId)
            if (!keyFile.exists()) {
                return Result.failure(IllegalArgumentException(keyNotFoundMessage(keyId)))
            }

            val keyBytes = keyFile.readBytes()
            Result.success(keyBytes)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    /**
     * Get all trusted public keys as DER byte arrays.
     *
     * @return List of public key DER byte arrays.
     */
    fun allPublicKeys(): List<ByteArray> =
        listTrustedKeys().mapNotNull { key ->
            loadPublicKey(key.id).getOrNull()
        }

    /** The index, or `null` when it exists but cannot be read; callers must not save over it then. */
    private fun loadIndex(): List<TrustedKey>? {
        if (!indexPath.exists()) {
            return emptyList()
        }

        return try {
            val content = indexPath.readBytes().decodeToString()
            json.decodeFromString<List<TrustedKeyDto>>(content).map { it.toTrustedKey() }
        } catch (e: IOException) {
            logger.warn("Failed to read trusted keys index $indexPath: ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            logger.warn("Failed to parse trusted keys index $indexPath: ${e.message}")
            null
        }
    }

    private fun saveIndex(keys: List<TrustedKey>) {
        createDirectories(keyDir)
        val dtos = keys.map { TrustedKeyDto.fromTrustedKey(it) }
        val content = json.encodeToString(dtos)
        indexPath.writeBytes(content.encodeToByteArray())
    }

    /**
     * Basic validation of X.509 SubjectPublicKeyInfo DER encoding.
     *
     * Checks:
     * 1. Starts with ASN.1 SEQUENCE tag (0x30)
     * 2. Contains the RSA algorithm OID (1.2.840.113549.1.1.1)
     * 3. Minimum reasonable length for an RSA-2048 key
     */
    private fun isValidPublicKeyDer(keyBytes: ByteArray): Boolean {
        if (keyBytes.size < MIN_PUBLIC_KEY_DER_LENGTH) return false
        // Must start with SEQUENCE tag
        if (keyBytes[0] != 0x30.toByte()) return false
        // RSA OID: 06 09 2a 86 48 86 f7 0d 01 01 01
        val rsaOid = byteArrayOf(
            0x06,
            0x09,
            0x2a,
            0x86.toByte(),
            0x48,
            0x86.toByte(),
            0xf7.toByte(),
            0x0d,
            0x01,
            0x01,
            0x01,
        )
        // Search for the OID within the first 20 bytes
        var found = false
        for (i in 0..minOf(keyBytes.size - rsaOid.size, RSA_OID_SEARCH_LIMIT)) {
            if (keyBytes.copyOfRange(i, i + rsaOid.size).contentEquals(rsaOid)) {
                found = true
                break
            }
        }
        return found
    }
}

/**
 * Metadata about a trusted public key.
 */
@Serializable
data class TrustedKey(
    val id: String,
    val fingerprint: String,
    val addedDate: Instant,
    val description: String?,
)

/**
 * Serializable DTO for [TrustedKey] (Instant is not directly serializable).
 */
@Serializable
private data class TrustedKeyDto(
    val id: String,
    val fingerprint: String,
    val addedDate: String,
    val description: String?,
) {
    fun toTrustedKey() =
        TrustedKey(
            id = id,
            fingerprint = fingerprint,
            addedDate = Instant.parse(addedDate),
            description = description,
        )

    companion object {
        fun fromTrustedKey(key: TrustedKey) =
            TrustedKeyDto(
                id = key.id,
                fingerprint = key.fingerprint,
                addedDate = key.addedDate.toString(),
                description = key.description,
            )
    }
}
