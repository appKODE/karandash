package io.karandash.subprocess

import io.karandash.models.support.KarandashJson
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.PlatformProcess
import io.karandash.platform.ProcessTimeouts
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.isFile
import io.karandash.platform.resolveUserPath
import io.karandash.platform.safeParent
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.io.files.Path
import kotlinx.serialization.encodeToString

/**
 * Native-side client for the JAR plugin subprocess bridge.
 *
 * Spawns a JVM subprocess with plugin JARs on the classpath and communicates
 * via stdin/stdout JSON protocol. Each [send] call starts a new JVM process
 * (batch-mode IPC), keeping the implementation simple and stateless.
 *
 * The JVM subprocess runs a bridge server which handles the request and exits. Its stderr lines
 * are forwarded to [logger] at debug level; the subprocess emits its own debug lines only when
 * [logger] has debug enabled (passed down as the `karandash.bridge.verbose` system property).
 * A subprocess that does not answer within [timeoutSeconds] is killed (`PlatformProcess` watchdog) and the
 * request fails, so a hung handshake never leaves a JVM behind.
 */
class JarBridgeClient(
    private val javaPath: String,
    private val classpath: String,
    private val logger: Logger = NoOpLogger,
    private val timeoutSeconds: Int = BRIDGE_TIMEOUT_SECONDS,
) {
    private val json = KarandashJson.Wire

    /**
     * Send a request to the JVM bridge and return the response.
     *
     * Spawns `java -cp <classpath> io.karandash.loader.jar.JarBridgeServer`
     * with the request as stdin, reads the response from stdout.
     * Uses [PlatformProcess.executeAsync] to avoid blocking the calling coroutine thread.
     */
    suspend fun send(request: JarBridgeRequest): JarBridgeResponse {
        val requestJson = json.encodeToString(request)

        val result = PlatformProcess.executeAsync(
            command = command(),
            stdin = requestJson,
            timeoutSeconds = timeoutSeconds,
        )

        result.error
            .lineSequence()
            .filter { it.isNotBlank() }
            .forEach { logger.debug(it) }
        if (result.isFailed) {
            return JarBridgeResponse(
                success = false,
                error = "JVM bridge process failed (exit ${result.exitCode}): ${result.error}",
            )
        }

        val responseLine = result.output.lines().firstOrNull { it.isNotBlank() }
            ?: return JarBridgeResponse(
                success = false,
                error = "JVM bridge returned empty output",
            )

        return try {
            json.decodeFromString<JarBridgeResponse>(responseLine)
        } catch (
            // boundary: subprocess IPC parsing
            @Suppress("TooGenericExceptionCaught") e: Exception
        ) {
            JarBridgeResponse(
                success = false,
                error = "Failed to parse bridge response: ${e.message}",
            )
        }
    }

    /** `java ... JarBridgeServer`: the bridge JVM's command line, for one request or a long-lived session. */
    fun command(): List<String> =
        listOfNotNull(javaPath, VERBOSE_FLAG.takeIf { logger.isDebugEnabled }, "-cp", classpath, BRIDGE_MAIN_CLASS)

    companion object {
        /** File name of the jar-bridge shadow JAR in `~/.karandash/lib` and next to the release binary. */
        const val BRIDGE_JAR_NAME = "karandash-jar-bridge.jar"

        private const val BRIDGE_MAIN_CLASS = "io.karandash.loader.jar.JarBridgeServer"

        /** Turns on the subprocess's debug lines; read by `JarBridgeServer`'s plugin context. */
        private const val VERBOSE_FLAG = "-Dkarandash.bridge.verbose=true"

        /** Maximum seconds to wait for a JVM bridge subprocess to complete. */
        private const val BRIDGE_TIMEOUT_SECONDS = ProcessTimeouts.BRIDGE_SECONDS

        /**
         * Attempts to locate the `java` executable.
         *
         * Checks JAVA_HOME first, then falls back to PATH lookup.
         */
        fun findJava(): String? {
            val javaHome = PlatformEnvironment.getEnv("JAVA_HOME")
            if (javaHome != null) {
                val javaBin = "$javaHome/bin/java"
                val result = PlatformProcess.execute(listOf(javaBin, "-version"))
                if (result.isSuccess || (result.hasError && result.error.contains("version"))) {
                    return javaBin
                }
            }

            // Try java on PATH
            val result = PlatformProcess.execute(listOf("java", "-version"))
            if (result.isSuccess || (result.hasError && result.error.contains("version"))) {
                return "java"
            }

            return null
        }

        /**
         * Locate the bridge JAR, first match wins:
         * 1. the `KARANDASH_BRIDGE_JAR` env var;
         * 2. `~/.karandash/lib/karandash-jar-bridge.jar`;
         * 3. [BRIDGE_JAR_NAME] alongside the current [executable] (where the release archives and the image put it).
         */
        fun findBridgeJar(
            env: (String) -> String? = PlatformEnvironment::getEnv,
            executable: String? = PlatformEnvironment.executablePath(),
        ): String? = resolveBridgeJar(env("KARANDASH_BRIDGE_JAR"), env("HOME") ?: env("USERPROFILE"), executable)

        /** The classpath entry is made absolute, so the bridge JVM finds it whatever its working directory. */
        internal fun resolveBridgeJar(
            envPath: String?,
            home: String?,
            executable: String? = null,
        ): String? {
            val cwd = currentWorkingDirectory()
            if (envPath != null) return resolveUserPath(envPath, cwd).toString()
            val candidates =
                listOfNotNull(
                    home?.let { Path(it, ".karandash", "lib", BRIDGE_JAR_NAME) },
                    executable?.let { Path(it).safeParent }?.let { Path(it, BRIDGE_JAR_NAME) },
                )
            return candidates.firstOrNull { it.isFile() }?.let { resolveUserPath(it.toString(), cwd).toString() }
        }
    }
}
