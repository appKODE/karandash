package io.karandash.crypto

import io.karandash.api.support.rethrowCancellation
import java.nio.file.Files
import java.nio.file.Path
import java.security.Signature
import kotlinx.io.files.Path as KPath

private const val SIGNATURE_ALGORITHM = "SHA256withRSA"

/**
 * Sign a JAR file with a private key (JVM only, for testing and CLI usage).
 *
 * @param jarPath Path to the JAR file.
 * @param privateKey The private key to sign with.
 * @param outputPath Path where signature should be written (default: <jar>.sig).
 * @return [Result] containing the signature file path on success.
 */
@Suppress("TooGenericExceptionCaught") // Catch-all for Result.failure wrapping (IO + crypto)
fun SignatureVerifier.signJar(
    jarPath: Path,
    privateKey: java.security.PrivateKey,
    outputPath: Path? = null,
): Result<Path> {
    return try {
        if (!Files.exists(jarPath)) {
            return Result.failure(IllegalArgumentException("JAR file not found: $jarPath"))
        }

        val jarBytes = Files.readAllBytes(jarPath)

        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(jarBytes)
        val signatureBytes = signature.sign()

        val sigPath = outputPath ?: jarPath.resolveSibling("${jarPath.fileName}.sig")
        Files.write(sigPath, signatureBytes)

        Result.success(sigPath)
    } catch (e: Exception) {
        // boundary: JCA signing and file I/O; failure becomes Result.failure
        e.rethrowCancellation()
        Result.failure(e)
    }
}

/**
 * Verify a JAR against all trusted keys in the KeyManager (JVM bridge).
 *
 * Delegates to the common [SignatureVerifier.verifyJar] with DER-encoded keys.
 *
 * @param jarPath Path to the JAR file.
 * @param keyManager The [KeyManager] containing trusted keys.
 * @return [Result] indicating success (at least one key matched) or failure.
 */
suspend fun SignatureVerifier.verifyJar(
    jarPath: Path,
    keyManager: KeyManager,
): Result<Boolean> {
    val jarFile = KPath(jarPath.toString())
    val publicKeysDer = keyManager.allPublicKeys()
    return verifyJar(jarFile, publicKeysDer)
}

/**
 * Verify a JAR signature using java.nio.file.Path arguments (JVM convenience).
 *
 * @param jarPath Path to the JAR file.
 * @param signaturePath Path to the signature file.
 * @param publicKey The public key to verify against.
 * @return true if the signature is valid.
 */
suspend fun SignatureVerifier.verifySignature(
    jarPath: Path,
    signaturePath: Path,
    publicKey: java.security.PublicKey,
): Boolean {
    val jarFile = KPath(jarPath.toString())
    val sigFile = KPath(signaturePath.toString())
    return verifySignature(jarFile, sigFile, publicKey.encoded)
}

/**
 * Find the signature file for a JAR (JVM convenience using Path).
 *
 * @param jarPath Path to the JAR file.
 * @return Path to signature file, or null if not found.
 */
fun SignatureVerifier.findSignatureFile(jarPath: Path): Path? {
    val jarFile = KPath(jarPath.toString())
    val sigFile = findSignatureFile(jarFile) ?: return null
    return java.nio.file.Paths
        .get(sigFile.toString())
}
