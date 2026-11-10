package io.karandash.platform

/**
 * macOS ships no `timeout(1)`, so a POSIX-sh watchdog bounds the run: the command is exec'd in a
 * background subshell, a second background job SIGKILLs it after the timeout, and the watchdog is
 * killed once the command exits on its own. A killed command exits 137. The `sh` that `system()` starts stays
 * the parent for that time and keeps the full environment (see docs/configuration/scripts.md).
 */
internal actual fun boundedShellCommand(
    run: BoundedCommand,
    resolver: PosixUnsetEnv.Resolver,
): String {
    val commandStr = resolver.commandLine(run.command)
    val execStr = PosixUnsetEnv.execPrefix(run.unsetEnv)
    val seconds = run.timeoutSeconds
    return if (seconds != null) {
        "(${run.cd}$execStr$commandStr ${run.redirects}) & p=\$!; " +
            "(sleep $seconds; kill -9 \$p) </dev/null >/dev/null 2>&1 & w=\$!; " +
            "wait \$p; s=\$?; kill \$w 2>/dev/null; exit \$s"
    } else {
        "${run.cd}${if (run.unsetEnv.isEmpty()) "" else execStr}$commandStr ${run.redirects}"
    }
}

/** macOS has no equivalent: same-uid processes can read the environment via `KERN_PROCARGS2` regardless. */
internal actual fun denyInspection(): InspectionDenial = InspectionDenial.NotApplicable
