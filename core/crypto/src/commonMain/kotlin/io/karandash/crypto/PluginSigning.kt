package io.karandash.crypto

import io.karandash.api.support.suspendCatching
import io.karandash.platform.readBytes
import io.karandash.platform.writeBytes
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write

/**
 * Sign a plugin JAR file with an RSA private key.
 *
 * @param jarPath Path to the JAR file to sign.
 * @param keyPath Path to the private key file (RSA, PKCS#8 or PKCS#1 DER).
 * @param outputPath Output path for the signature file (default: `<jar-path>.sig`).
 * @return [Result] containing the signature output path on success.
 */
fun signPluginJar(
    jarPath: String,
    keyPath: String,
    outputPath: String? = null,
): Result<String> =
    suspendCatching {
        val jarFile = Path(jarPath)
        val keyFile = Path(keyPath)

        require(SystemFileSystem.exists(jarFile)) { "JAR file not found: $jarPath" }
        require(SystemFileSystem.exists(keyFile)) { "Key file not found: $keyPath" }

        val jarBytes = jarFile.readBytes()
        val keyBytes = keyFile.readBytes()

        val signatureBytes = CryptoProvider.signRsaSha256(jarBytes, keyBytes)

        val sigPath = outputPath ?: "$jarPath.sig"
        Path(sigPath).writeBytes(signatureBytes)

        sigPath
    }
