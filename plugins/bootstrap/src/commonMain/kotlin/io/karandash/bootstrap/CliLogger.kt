package io.karandash.bootstrap

import io.karandash.platform.PlatformEnvironment
import io.karandash.sdk.Logger
import io.karandash.security.TextRedactor
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * CLI logger that respects `--quiet` and `--verbose` flags.
 *
 * All pipeline and plugin logging flows through this single implementation.
 *
 * | Level   | quiet=false, verbose=false | quiet=false, verbose=true | quiet=true |
 * |---------|---------------------------|--------------------------|------------|
 * | debug   | suppressed                | stderr                   | suppressed |
 * | info    | stderr                    | stderr                   | suppressed |
 * | warn    | stderr                    | stderr                   | suppressed |
 * | error   | stderr                    | stderr                   | stderr     |
 *
 * Every line goes through [TextRedactor.redactForLog] (URL credentials, tokens, API keys): warnings and
 * errors carry exception messages and URLs too, not only debug output.
 *
 * @param quiet When true, suppress all output except errors.
 * @param verbose When true, include debug-level messages.
 * @param prefix Optional prefix prepended to each message (e.g. module ID).
 * @param sink Destination for formatted lines; stderr by default.
 */
class CliLogger(
    private val quiet: Boolean = false,
    private val verbose: Boolean = false,
    private val prefix: String? = null,
    private val contextFields: List<Pair<String, String>> = emptyList(),
    private val sink: (String) -> Unit = PlatformEnvironment::stderrPrintln,
) : Logger {
    override fun debug(message: String) {
        if (verbose && !quiet) {
            emit("[DEBUG] ${formatted(message)}")
        }
    }

    override fun info(message: String) {
        if (!quiet) {
            emit(formatted(message))
        }
    }

    override fun warn(message: String) {
        if (!quiet) {
            emit("[WARN] ${formatted(message)}")
        }
    }

    override fun error(message: String) {
        emit("[ERROR] ${formatted(message)}")
    }

    /** Adds the stack trace in verbose mode; otherwise one line with the exception type and message. */
    override fun error(
        message: String,
        throwable: Throwable,
    ) {
        val cause = if (isDebugEnabled) throwable.stackTraceToString() else "${throwable::class.simpleName}: ${throwable.message}"
        emit("[ERROR] ${formatted("$message: $cause")}")
    }

    override val isDebugEnabled: Boolean get() = verbose && !quiet

    override fun withContext(
        key: String,
        value: String
    ): Logger = CliLogger(quiet, verbose, prefix, contextFields + (key to value), sink)

    private fun formatted(message: String): String {
        val base = if (prefix != null) "[$prefix] $message" else message
        if (contextFields.isEmpty()) return base
        val ctx = contextFields.joinToString(" ") { (k, v) -> "$k=$v" }
        return "[$ctx] $base"
    }

    private fun emit(message: String) {
        val now = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())
        val ts = buildString {
            append(now.year.toString().padStart(4, '0'))
            append('-')
            append(now.monthNumber.toString().padStart(2, '0'))
            append('-')
            append(now.dayOfMonth.toString().padStart(2, '0'))
            append(' ')
            append(now.hour.toString().padStart(2, '0'))
            append(':')
            append(now.minute.toString().padStart(2, '0'))
            append(':')
            append(now.second.toString().padStart(2, '0'))
            append('.')
            val millis = now.nanosecond / 1_000_000
            append(millis.toString().padStart(3, '0'))
        }
        sink("$ts ${TextRedactor.redactForLog(message)}")
    }
}
