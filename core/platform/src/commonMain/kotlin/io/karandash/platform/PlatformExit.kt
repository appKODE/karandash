package io.karandash.platform

/** Terminates the process with [status]. `kotlin.system.exitProcess` is not visible to common metadata. */
expect fun platformExit(status: Int): Nothing
