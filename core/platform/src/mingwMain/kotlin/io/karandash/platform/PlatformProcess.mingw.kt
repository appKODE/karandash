package io.karandash.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import platform.posix._getpid
import platform.windows.AssignProcessToJobObject
import platform.windows.CREATE_SUSPENDED
import platform.windows.CREATE_UNICODE_ENVIRONMENT
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.CreateJobObjectW
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FreeEnvironmentStringsW
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetEnvironmentStringsW
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.GetStdHandle
import platform.windows.HANDLE
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.PROCESS_INFORMATION
import platform.windows.ResumeThread
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.STD_INPUT_HANDLE
import platform.windows.TRUE
import platform.windows.TRUNCATE_EXISTING
import platform.windows.TerminateJobObject
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject

private const val MILLIS_PER_SECOND = 1000
private const val SPAWN_FAILED_EXIT_CODE = -1

@OptIn(ExperimentalForeignApi::class)
actual object PlatformProcess {
    actual fun execute(
        command: List<String>,
        workingDir: String?,
        stdin: String?,
        timeoutSeconds: Int?,
        unsetEnv: Set<String>,
    ): ProcessResult {
        val stdoutPath = createTempCapturePath("karandash-stdout")
        val stderrPath = createTempCapturePath("karandash-stderr")
        val stdinPath = if (stdin != null) createTempCapturePath("karandash-stdin") else null
        try {
            if (stdinPath != null && stdin != null) Path(stdinPath).writeText(stdin)
            val envBlock = unsetEnv.takeIf { it.isNotEmpty() }?.let { WindowsEnvBlock.of(inheritedEnvironment(), it) }
            val spawn = Spawn(WindowsCommandLine.of(command), workingDir, stdinPath, stdoutPath, stderrPath, envBlock)
            val status = run(spawn, timeoutSeconds)
            val error = if (status == SPAWN_FAILED_EXIT_CODE) {
                "Cannot start ${command.firstOrNull()}: Windows error ${GetLastError()}"
            } else {
                readCaptureFile(stderrPath)
            }
            return ProcessResult(exitCode = status, output = readCaptureFile(stdoutPath), error = error)
        } finally {
            deleteIfExists(Path(stdoutPath))
            deleteIfExists(Path(stderrPath))
            if (stdinPath != null) deleteIfExists(Path(stdinPath))
        }
    }

    actual suspend fun executeAsync(
        command: List<String>,
        workingDir: String?,
        stdin: String?,
        timeoutSeconds: Int?,
        unsetEnv: Set<String>,
    ): ProcessResult =
        withContext(Dispatchers.IO) {
            execute(command, workingDir, stdin, timeoutSeconds, unsetEnv)
        }

    actual fun spawnInteractive(command: List<String>): InteractiveProcess = spawnWindowsInteractive(command)

    actual fun denySameUserInspection(): InspectionDenial = InspectionDenial.NotApplicable

    /**
     * One CreateProcessW call: the command line, the files bound to the child's standard handles and the environment
     * block ([WindowsEnvBlock]; null inherits the parent's).
     */
    private class Spawn(
        val commandLine: String,
        val workingDir: String?,
        val stdinPath: String?,
        val stdoutPath: String,
        val stderrPath: String,
        val envBlock: String?,
    )

    /** This process's `NAME=value` entries, read from the `GetEnvironmentStringsW` block. */
    private fun inheritedEnvironment(): List<String> {
        val block = GetEnvironmentStringsW() ?: return emptyList()
        try {
            val entries = mutableListOf<String>()
            val current = StringBuilder()
            var index = 0
            while (true) {
                val unit = block[index++].toInt()
                if (unit != 0) {
                    current.append(unit.toChar())
                } else if (current.isEmpty()) {
                    return entries
                } else {
                    entries += current.toString()
                    current.clear()
                }
            }
        } finally {
            FreeEnvironmentStringsW(block)
        }
    }

    /**
     * Starts [spawn] with CreateProcessW (no `cmd.exe`) inside a job object, so a timeout kills the whole tree. The
     * capture files are bound through the STARTUPINFO standard handles. A killed command exits 137, like the POSIX
     * watchdog; a command that cannot start returns [SPAWN_FAILED_EXIT_CODE].
     */
    private fun run(
        spawn: Spawn,
        timeoutSeconds: Int?,
    ): Int =
        memScoped {
            val security = alloc<SECURITY_ATTRIBUTES>().apply {
                nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
                lpSecurityDescriptor = null
                bInheritHandle = TRUE
            }
            val stdinHandle = spawn.stdinPath?.let { openInheritable(it, GENERIC_READ, OPEN_EXISTING, security.ptr) }
            val stdoutHandle = openInheritable(spawn.stdoutPath, GENERIC_WRITE.convert(), TRUNCATE_EXISTING, security.ptr)
            val stderrHandle = openInheritable(spawn.stderrPath, GENERIC_WRITE.convert(), TRUNCATE_EXISTING, security.ptr)
            val startup = alloc<STARTUPINFOW>().apply {
                cb = sizeOf<STARTUPINFOW>().convert()
                dwFlags = STARTF_USESTDHANDLES.convert()
                hStdInput = stdinHandle ?: GetStdHandle(STD_INPUT_HANDLE)
                hStdOutput = stdoutHandle
                hStdError = stderrHandle
            }
            try {
                startInJob(spawn, startup, timeoutSeconds)
            } finally {
                listOfNotNull(stdinHandle, stdoutHandle, stderrHandle).forEach { CloseHandle(it) }
            }
        }

    private fun MemScope.startInJob(
        spawn: Spawn,
        startup: STARTUPINFOW,
        timeoutSeconds: Int?,
    ): Int {
        val job = CreateJobObjectW(null, null)
        val process = alloc<PROCESS_INFORMATION>()
        val cmd = spawn.commandLine.wcstr.getPointer(this)
        val dir = spawn.workingDir?.wcstr?.getPointer(this)
        val env = spawn.envBlock?.wcstr?.getPointer(this)
        val flags = (CREATE_SUSPENDED or CREATE_UNICODE_ENVIRONMENT).convert<UInt>()
        val created = CreateProcessW(null, cmd, null, null, TRUE, flags, env, dir, startup.ptr, process.ptr)
        if (created == 0) {
            CloseHandle(job)
            return SPAWN_FAILED_EXIT_CODE
        }
        AssignProcessToJobObject(job, process.hProcess)
        ResumeThread(process.hThread)
        val waitMillis = timeoutSeconds?.let { (it * MILLIS_PER_SECOND).convert<UInt>() } ?: INFINITE
        val exitCode = if (WaitForSingleObject(process.hProcess, waitMillis) == WAIT_TIMEOUT.convert<UInt>()) {
            TerminateJobObject(job, ProcessTimeouts.EXIT_CODE.convert())
            WaitForSingleObject(process.hProcess, INFINITE)
            ProcessTimeouts.EXIT_CODE
        } else {
            val code = alloc<DWORDVar>()
            GetExitCodeProcess(process.hProcess, code.ptr)
            code.value.toInt()
        }
        CloseHandle(process.hThread)
        CloseHandle(process.hProcess)
        CloseHandle(job)
        return exitCode
    }

    private fun openInheritable(
        path: String,
        access: UInt,
        disposition: Int,
        security: CPointer<SECURITY_ATTRIBUTES>,
    ): HANDLE {
        val share = (FILE_SHARE_READ or FILE_SHARE_WRITE).convert<UInt>()
        val handle = CreateFileW(path, access, share, security, disposition.convert(), FILE_ATTRIBUTE_NORMAL.convert(), null)
        check(handle != null && handle != INVALID_HANDLE_VALUE) { "Cannot open capture file $path: Windows error ${GetLastError()}" }
        return handle
    }

    /** Creates an owner-only capture file with an exclusive create in [tmpDir]; fails rather than reuse a planted name. */
    internal fun createTempCapturePath(
        prefix: String,
        tmpDir: String = PlatformEnvironment.getEnv("TEMP")?.takeIf { it.isNotBlank() }
            ?: PlatformEnvironment.getEnv("TMP")?.takeIf { it.isNotBlank() }
            ?: "C:\\Temp",
    ): String = createTempFileInDir(Path(tmpDir), "$prefix-${_getpid()}-", "").toString()
}
