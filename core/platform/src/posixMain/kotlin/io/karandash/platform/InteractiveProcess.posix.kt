@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.karandash.platform

import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.posix.EINTR
import platform.posix.FD_CLOEXEC
import platform.posix.F_SETFD
import platform.posix.RTLD_LAZY
import platform.posix.SIGKILL
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.close
import platform.posix.dlopen
import platform.posix.dlsym
import platform.posix.fcntl
import platform.posix.kill
import platform.posix.pipe
import platform.posix.posix_errno
import platform.posix.read
import platform.posix.signal
import platform.posix.waitpid
import platform.posix.write

/** Outcome of [posixSpawnWithStdio]: the child's [pid], or the `posix_spawnp` error in [errno]. */
private class SpawnStatus(
    val pid: Int,
    val errno: Int,
)

/** Room for a `posix_spawn_file_actions_t`: a pointer on macOS, an 80-byte struct in glibc. */
private const val FILE_ACTIONS_BYTES = 256

private typealias FileActionsFn = CFunction<(COpaquePointer?) -> Int>
private typealias AddDup2Fn = CFunction<(COpaquePointer?, Int, Int) -> Int>
private typealias SpawnpFn = CFunction<
    (CPointer<IntVar>?, CPointer<ByteVar>?, COpaquePointer?, COpaquePointer?, Argv?, Argv?) -> Int,
>
private typealias Argv = CPointer<CPointerVar<ByteVar>>

/**
 * `posix_spawnp` of [command] with the inherited environment, [stdio] (stdin, stdout, stderr descriptors) duplicated
 * onto 0, 1 and 2. The spawn functions are looked up in the C library at run time: Kotlin/Native's macOS platform
 * libraries do not bind `spawn.h`, and the Linux binding is outside `platform.posix`.
 */
private fun posixSpawnWithStdio(
    command: List<String>,
    stdio: IntArray,
): SpawnStatus =
    memScoped {
        val libc = checkNotNull(dlopen(null, RTLD_LAZY)) { "Cannot open the C library: ${nativeLibraryError()}" }

        fun <F : CFunction<*>> symbol(name: String): CPointer<F> =
            checkNotNull(dlsym(libc, name)) { "C library has no $name" }.reinterpret()

        val actions = allocArray<ByteVar>(FILE_ACTIONS_BYTES)
        symbol<FileActionsFn>("posix_spawn_file_actions_init")(actions)
        val addDup2 = symbol<AddDup2Fn>("posix_spawn_file_actions_adddup2")
        stdio.forEachIndexed { target, fd -> addDup2(actions, fd, target) }
        val argv = allocArray<CPointerVar<ByteVar>>(command.size + 1)
        command.forEachIndexed { index, arg -> argv[index] = arg.cstr.ptr }
        argv[command.size] = null
        val pid = alloc<IntVar>()
        val spawnp = symbol<SpawnpFn>("posix_spawnp")
        val errno = spawnp(pid.ptr, command[0].cstr.ptr, actions, null, argv, environBlock())
        symbol<FileActionsFn>("posix_spawn_file_actions_destroy")(actions)
        SpawnStatus(pid.value, errno)
    }

/** Starts [command] with `posix_spawnp`, its stdio bound to three pipes whose other ends this process keeps. */
internal fun spawnPosixInteractive(command: List<String>): InteractiveProcess =
    memScoped {
        require(command.isNotEmpty()) { "Empty command" }
        // A child that dies mid-write must surface as a failed write, not kill this process.
        signal(SIGPIPE, SIG_IGN)
        val stdin = newPipe()
        val stdout = newPipe()
        val stderr = newPipe()
        val fds = listOf(stdin[0], stdin[1], stdout[0], stdout[1], stderr[0], stderr[1])
        // dup2 onto 0/1/2 clears the flag on the child's copies; every original closes on exec.
        fds.forEach { fcntl(it, F_SETFD, FD_CLOEXEC) }
        val status = posixSpawnWithStdio(command, intArrayOf(stdin[0], stdout[1], stderr[1]))
        close(stdin[0])
        close(stdout[1])
        close(stderr[1])
        if (status.errno != 0) {
            listOf(stdin[1], stdout[0], stderr[0]).forEach { close(it) }
            error("Cannot start ${command[0]}: errno ${status.errno}")
        }
        PosixInteractiveProcess(status.pid, stdin[1], stdout[0], stderr[0])
    }

private fun MemScope.newPipe(): IntArray {
    val ends = allocArray<IntVar>(2)
    check(pipe(ends) == 0) { "pipe() failed: errno ${posix_errno()}" }
    return intArrayOf(ends[0], ends[1])
}

private class PosixInteractiveProcess(
    private val pid: Int,
    stdinFd: Int,
    stdoutFd: Int,
    stderrFd: Int,
) : InteractiveProcess {
    private val stdin = atomic(stdinFd)
    private val reaped = atomic(false)
    private val stdout = ChunkLineReader(fdReader(stdoutFd))
    private val stderr = ChunkLineReader(fdReader(stderrFd))

    override fun writeLine(line: String): Boolean {
        val fd = stdin.value
        if (fd < 0) return false
        val bytes = (line + "\n").encodeToByteArray()
        var offset = 0
        while (offset < bytes.size) {
            val written = bytes.usePinned { write(fd, it.addressOf(offset), (bytes.size - offset).convert()) }.toInt()
            if (written < 0 && posix_errno() == EINTR) continue
            if (written <= 0) return false
            offset += written
        }
        return true
    }

    override fun readLine(): String? = stdout.readLine()

    override fun readErrorLine(): String? = stderr.readLine()

    override fun closeStdin() {
        val fd = stdin.getAndSet(-1)
        if (fd >= 0) close(fd)
    }

    override fun kill() {
        closeStdin()
        if (reaped.compareAndSet(expect = false, update = true)) {
            kill(pid, SIGKILL)
            while (waitpid(pid, null, 0) < 0 && posix_errno() == EINTR) {
                // Interrupted by a signal: wait again so the child is reaped.
            }
        }
    }

    /** Reads [initialFd] until end of stream, then closes it; later reads report end of stream. */
    private fun fdReader(initialFd: Int): (ByteArray) -> Int {
        val fd = atomic(initialFd)
        return { chunk ->
            val current = fd.value
            if (current < 0) {
                0
            } else {
                var read: Int
                do {
                    read = chunk.usePinned { read(current, it.addressOf(0), chunk.size.convert()) }.toInt()
                } while (read < 0 && posix_errno() == EINTR)
                if (read <= 0 && fd.compareAndSet(current, -1)) close(current)
                read
            }
        }
    }
}
