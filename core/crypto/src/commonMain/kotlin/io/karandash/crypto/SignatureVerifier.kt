package io.karandash.crypto

import io.karandash.api.exception.PluginSecurityException
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.exists
import io.karandash.platform.readBytes
import io.karandash.platform.safeName
import io.karandash.platform.safeParent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Verifies JAR signatures using RSA-2048 with SHA-256.
 *
 * Signature format: RSA/SHA256withRSA
 * Signature files have `.sig` extension.
 *
 * Verification methods are multiplatform. Signing (private key operations)
 * is JVM-only and available as an extension in jvmMain.
 */
object SignatureVerifier {
    /**
     * Verify a JAR signature against a single public key.
     *
     * @param jarFile The JAR file to verify.
     * @param signatureFile The signature file.
     * @param publicKeyDer The public key in X.509 DER (SubjectPublicKeyInfo) format.
     * @return true if the signature is valid.
     */
    suspend fun verifySignature(
        jarFile: Path,
        signatureFile: Path,
        publicKeyDer: ByteArray,
        logger: Logger = NoOpLogger,
    ): Boolean {
        return try {
            if (!jarFile.exists()) {
                return false
            }
            if (!signatureFile.exists()) {
                return false
            }

            val jarBytes = withContext(Dispatchers.IO) { jarFile.readBytes() }
            val signatureBytes = withContext(Dispatchers.IO) { signatureFile.readBytes() }

            CryptoProvider.verifyRsaSha256Signature(jarBytes, signatureBytes, publicKeyDer)
        } catch (e: CancellationException) {
            throw e
        } catch (
            // boundary: crypto ops throw varied exceptions
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            logger.debug("SignatureVerifier: verification failed for ${jarFile.safeName}: ${e.message}")
            false
        }
    }

    /**
     * Verify a JAR against a list of trusted public keys (DER-encoded).
     *
     * @param jarFile The JAR file to verify.
     * @param publicKeysDer List of public keys in X.509 DER format.
     * @return [Result] indicating success (at least one key matched) or failure.
     */
    @Suppress("TooGenericExceptionCaught") // Catch-all for Result.failure wrapping
    suspend fun verifyJar(
        jarFile: Path,
        publicKeysDer: List<ByteArray>,
    ): Result<Boolean> {
        return try {
            val signatureFile = findSignatureFile(jarFile)
                ?: return Result.failure(
                    PluginSecurityException("No signature file found for ${jarFile.safeName}")
                )

            if (publicKeysDer.isEmpty()) {
                return Result.failure(
                    PluginSecurityException("No trusted keys configured")
                )
            }

            for (publicKeyDer in publicKeysDer) {
                if (verifySignature(jarFile, signatureFile, publicKeyDer)) {
                    return Result.success(true)
                }
            }

            Result.failure(
                PluginSecurityException("Signature verification failed: no matching trusted key")
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // boundary: crypto provider and file I/O; failure becomes Result.failure
            Result.failure(e)
        }
    }

    /**
     * Find the signature file for a JAR.
     *
     * Looks for `<jar-name>.sig` in the same directory.
     *
     * @param jarFile The JAR file.
     * @return Path for the signature file, or null if not found.
     */
    fun findSignatureFile(jarFile: Path): Path? {
        val parent = jarFile.safeParent ?: Path(".")
        val signatureFile = Path(parent, "${jarFile.safeName}.sig")
        return if (signatureFile.exists()) signatureFile else null
    }
}
