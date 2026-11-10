package io.karandash.platform

actual fun getCurrentWorkingDirectoryString(): String = System.getProperty("user.dir")

/** `$HOME` first, like the native actuals, so `~` follows the environment; the JVM `user.home` is the fallback. */
actual fun getUserHomeDirectoryString(): String = userHome(System.getenv("HOME"), System.getProperty("user.home"))

internal fun userHome(
    homeEnv: String?,
    userHomeProperty: String,
): String = homeEnv?.takeIf { it.isNotBlank() } ?: userHomeProperty

actual fun fallbackTempDirectoryString(): String = "/tmp"
