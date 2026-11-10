package io.karandash.crashhandler

/**
 * Installs platform-appropriate crash handlers that write diagnostics
 * to stderr before termination.
 *
 * - JVM: Sets the default uncaught exception handler, which covers every thread.
 * - Native: Installs POSIX signal handlers for SIGSEGV, SIGABRT, SIGILL; the handler only calls write(2).
 */
expect fun installCrashHandler()
