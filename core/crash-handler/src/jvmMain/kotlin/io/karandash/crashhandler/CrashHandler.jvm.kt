package io.karandash.crashhandler

import io.karandash.security.TextRedactor

/**
 * JVM crash handler - installs a default uncaught exception handler that
 * prints a formatted crash report to stderr, redacted like log lines (an exception message may carry a token).
 */
actual fun installCrashHandler() {
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val report = "\n[CRASH] karandash JVM uncaught exception in thread '${thread.name}'\n" +
            "[CRASH] ${throwable::class.simpleName}: ${throwable.message}\n" + throwable.stackTraceToString()
        System.err.println(TextRedactor.redactForLog(report))
        System.err.flush()
    }
}
