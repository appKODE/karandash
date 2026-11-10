package io.karandash.platform

import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** `unsetEnv` removes variables from the child's environment at exec time, and the parent can deny inspection. */
class PlatformProcessEnvTest {
    private val os: String =
        try {
            PlatformProcess.execute(listOf("uname", "-s")).output.trim()
        } catch (ignored: IOException) {
            ""
        }

    private val posix = os == "Linux" || os == "Darwin"

    @Test
    fun `unset vars are absent while other vars survive and the command still resolves without PATH`() {
        if (!posix) return
        val unset = setOf("HOME", "PATH")
        val probe = listOf("sh", "-c", "echo \${HOME-unset}:\${USER-unset}:\${SHELL-unset}")
        val expected = "unset:" + PlatformEnvironment.getEnv("USER").orEmpty().ifEmpty { "unset" } + ":" +
            PlatformEnvironment.getEnv("SHELL").orEmpty().ifEmpty { "unset" } + "\n"

        val plain = PlatformProcess.execute(probe, unsetEnv = unset)
        val timed = PlatformProcess.execute(probe, timeoutSeconds = 10, unsetEnv = unset)

        assertEquals(ProcessResult(0, expected, ""), plain)
        assertEquals(ProcessResult(0, expected, ""), timed)
    }

    @Test
    fun `no unset vars keeps the inherited environment`() {
        if (!posix) return

        val result = PlatformProcess.execute(listOf("sh", "-c", "echo \${HOME-unset}"))

        assertEquals(PlatformEnvironment.getEnv("HOME") + "\n", result.output)
    }

    @Test
    fun `inspection is applied on Linux and not applicable elsewhere`() {
        if (!posix) return

        val expected = if (os == "Linux") InspectionDenial.Applied else InspectionDenial.NotApplicable

        assertEquals(expected, PlatformProcess.denySameUserInspection())
    }

    @Test
    fun `child cannot read the parent environment once inspection is denied`() {
        // root and CAP_SYS_PTRACE bypass the dumpable check, so the denial cannot be observed there.
        if (os != "Linux" || PlatformProcess.execute(listOf("id", "-u")).output.trim() == "0") return
        // Native runs the probe under system()'s sh, an exec'd (so dumpable again) ancestor: walk up to the first
        // same-uid ancestor whose environment is unreadable, i.e. this test process.
        val probe = listOf(
            "sh",
            "-c",
            "me=\$(id -u); p=\$PPID; while [ \"\$p\" -gt 1 ]; do " +
                "if [ \"\$(awk '/^Uid:/{print \$2}' /proc/\$p/status)\" = \"\$me\" ] && " +
                "! cat /proc/\$p/environ > /dev/null 2>&1; then echo denied; exit; fi; " +
                "p=\$(awk '/^PPid:/{print \$2}' /proc/\$p/status); done; echo readable",
        )

        PlatformProcess.denySameUserInspection()

        assertEquals("denied\n", PlatformProcess.execute(probe, unsetEnv = setOf("HOME")).output)
    }
}
