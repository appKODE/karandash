package io.karandash.cli.commands

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.crypto.signPluginJar
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Command to sign plugin JARs with a private key.
 *
 * Usage:
 *   karandash plugin sign <jar-file> --key <private-key>
 */
internal class SignCommand : SuspendCliktCommand(name = "sign") {
    override fun help(context: Context) = "Sign a plugin JAR with a private key"

    private val jarPath by argument(
        help = "Path to the plugin JAR file to sign"
    )

    private val keyPath by option(
        "--key",
        help = "Path to the private key file (RSA, PKCS#8 or PKCS#1 DER)"
    ).required()

    private val outputPath by option(
        "--output",
        "-o",
        help = "Output path for signature file (default: <jar-file>.sig)"
    )

    override suspend fun suspendRun() {
        if (!jarPath.endsWith(".jar")) {
            echo("Error: Input file must have .jar extension", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        if (!SystemFileSystem.exists(Path(jarPath))) {
            echo("Error: JAR file not found: $jarPath", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        if (!SystemFileSystem.exists(Path(keyPath))) {
            echo("Error: Key file not found: $keyPath", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }

        signPluginJar(jarPath, keyPath, outputPath).fold(
            onSuccess = { sigPath ->
                echo("Successfully signed ${jarPath.substringAfterLast('/')}")
                echo("Signature written to: $sigPath")
            },
            onFailure = { error ->
                echo("Error: Failed to sign JAR: ${error.message}", err = true)
                throw ProgramResult(ExitCodes.EXECUTION_ERROR)
            }
        )
    }
}
