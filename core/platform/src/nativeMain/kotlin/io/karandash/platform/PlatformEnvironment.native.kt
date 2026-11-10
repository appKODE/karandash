package io.karandash.platform

import kotlinx.cinterop.*
import platform.posix.*

@OptIn(ExperimentalForeignApi::class)
actual object PlatformEnvironment {
    actual fun getEnv(name: String): String? = getenv(name)?.toKString()

    actual fun names(): Set<String> {
        val block = environBlock() ?: return emptySet()
        return generateSequence(0) { it + 1 }
            .map { block[it] }
            .takeWhile { it != null }
            .mapNotNull { entry -> entry?.toKString()?.substringBefore('=')?.ifEmpty { null } }
            .toSet()
    }

    actual fun stderrPrintln(message: String) {
        fprintf(stderr, "%s\n", message)
        fflush(stderr)
    }

    actual fun executablePath(): String? = currentExecutable()?.takeIf { it.isNotEmpty() }
}

/** Path of the running executable from the OS (`_NSGetExecutablePath`, `/proc/self/exe`, `GetModuleFileNameW`). */
internal expect fun currentExecutable(): String?

/** The C `environ` block: `NAME=VALUE` strings ending with a null pointer. */
@OptIn(ExperimentalForeignApi::class)
internal expect fun environBlock(): CPointer<CPointerVar<ByteVar>>?
