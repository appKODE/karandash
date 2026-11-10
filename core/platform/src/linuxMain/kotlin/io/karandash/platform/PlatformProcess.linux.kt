package io.karandash.platform

import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.PR_SET_DUMPABLE
import platform.linux.prctl

/**
 * `timeout(1)` bounds the run. `-s KILL --preserve-status`: a timed-out command exits 128 + 9, the shared
 * ProcessTimeouts.EXIT_CODE (without `--preserve-status` GNU timeout exits 124 whatever the signal).
 */
internal actual fun boundedShellCommand(
    run: BoundedCommand,
    resolver: PosixUnsetEnv.Resolver,
): String {
    val bounded = if (run.timeoutSeconds != null) {
        "${resolver.program("timeout")} -s KILL --preserve-status ${run.timeoutSeconds} ${resolver.commandLine(run.command)}"
    } else {
        resolver.commandLine(run.command)
    }
    val timed = if (run.unsetEnv.isEmpty()) bounded else PosixUnsetEnv.execPrefix(run.unsetEnv) + bounded
    return "${run.cd}$timed ${run.redirects}"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun denyInspection(): InspectionDenial =
    if (prctl(PR_SET_DUMPABLE, 0L) == 0) InspectionDenial.Applied else InspectionDenial.Failed
