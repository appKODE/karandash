@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreatePipe
import platform.windows.CreateProcessW
import platform.windows.DWORDVar
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.HANDLE_FLAG_INHERIT
import platform.windows.INFINITE
import platform.windows.PROCESS_INFORMATION
import platform.windows.ReadFile
import platform.windows.SECURITY_ATTRIBUTES
import platform.windows.STARTF_USESTDHANDLES
import platform.windows.STARTUPINFOW
import platform.windows.SetHandleInformation
import platform.windows.TRUE
import platform.windows.TerminateProcess
import platform.windows.WaitForSingleObject
import platform.windows.WriteFile

/** Read and write ends of one anonymous pipe. */
private class PipeEnds(
    val read: HANDLE,
    val write: HANDLE,
)

/**
 * Starts [command] with CreateProcessW (no `cmd.exe`), its standard handles bound to three anonymous pipes. Only the
 * child's ends are inheritable, so the parent's ends never leak into this or a later child.
 */
internal fun spawnWindowsInteractive(command: List<String>): InteractiveProcess =
    memScoped {
        require(command.isNotEmpty()) { "Empty command" }
        val security = alloc<SECURITY_ATTRIBUTES>().apply {
            nLength = sizeOf<SECURITY_ATTRIBUTES>().convert()
            lpSecurityDescriptor = null
            bInheritHandle = TRUE
        }
        val stdin = newPipe(security.ptr)
        val stdout = newPipe(security.ptr)
        val stderr = newPipe(security.ptr)
        listOf(stdin.write, stdout.read, stderr.read).forEach { SetHandleInformation(it, HANDLE_FLAG_INHERIT.convert(), 0u) }
        val startup = alloc<STARTUPINFOW>().apply {
            cb = sizeOf<STARTUPINFOW>().convert()
            dwFlags = STARTF_USESTDHANDLES.convert()
            hStdInput = stdin.read
            hStdOutput = stdout.write
            hStdError = stderr.write
        }
        val process = alloc<PROCESS_INFORMATION>()
        val commandLine = WindowsCommandLine.of(command).wcstr.getPointer(this)
        val flags = CREATE_NO_WINDOW.convert<UInt>()
        val created = CreateProcessW(null, commandLine, null, null, TRUE, flags, null, null, startup.ptr, process.ptr)
        val error = GetLastError()
        listOf(stdin.read, stdout.write, stderr.write).forEach { CloseHandle(it) }
        val handle = process.hProcess
        if (created == 0 || handle == null) {
            listOf(stdin.write, stdout.read, stderr.read).forEach { CloseHandle(it) }
            error("Cannot start ${command[0]}: Windows error $error")
        }
        CloseHandle(process.hThread)
        WindowsInteractiveProcess(handle, stdin.write, stdout.read, stderr.read)
    }

private fun MemScope.newPipe(security: CPointer<SECURITY_ATTRIBUTES>): PipeEnds {
    val read = alloc<HANDLEVar>()
    val write = alloc<HANDLEVar>()
    check(CreatePipe(read.ptr, write.ptr, security, 0u) != 0) { "CreatePipe failed: Windows error ${GetLastError()}" }
    return PipeEnds(checkNotNull(read.value), checkNotNull(write.value))
}

private class WindowsInteractiveProcess(
    private val process: HANDLE,
    stdinHandle: HANDLE,
    stdoutHandle: HANDLE,
    stderrHandle: HANDLE,
) : InteractiveProcess {
    private val stdin = atomic<HANDLE?>(stdinHandle)
    private val killed = atomic(false)
    private val stdout = ChunkLineReader(handleReader(stdoutHandle))
    private val stderr = ChunkLineReader(handleReader(stderrHandle))

    override fun writeLine(line: String): Boolean {
        val handle = stdin.value ?: return false
        val bytes = (line + "\n").encodeToByteArray()
        return memScoped {
            val written = alloc<DWORDVar>()
            bytes.usePinned { WriteFile(handle, it.addressOf(0), bytes.size.convert(), written.ptr, null) } != 0 &&
                written.value.toInt() == bytes.size
        }
    }

    override fun readLine(): String? = stdout.readLine()

    override fun readErrorLine(): String? = stderr.readLine()

    override fun closeStdin() {
        stdin.getAndSet(null)?.let { CloseHandle(it) }
    }

    override fun kill() {
        closeStdin()
        if (killed.compareAndSet(expect = false, update = true)) {
            TerminateProcess(process, ProcessTimeouts.EXIT_CODE.convert())
            WaitForSingleObject(process, INFINITE)
            CloseHandle(process)
        }
    }

    /** Reads [initial] until the pipe breaks or ends, then closes it; later reads report end of stream. */
    private fun handleReader(initial: HANDLE): (ByteArray) -> Int {
        val handle = atomic<HANDLE?>(initial)
        return { chunk ->
            val current = handle.value
            if (current == null) {
                0
            } else {
                val read = memScoped {
                    val count = alloc<DWORDVar>()
                    val ok = chunk.usePinned { ReadFile(current, it.addressOf(0), chunk.size.convert(), count.ptr, null) }
                    if (ok != 0) count.value.toInt() else 0
                }
                if (read <= 0 && handle.compareAndSet(current, null)) CloseHandle(current)
                read
            }
        }
    }
}
