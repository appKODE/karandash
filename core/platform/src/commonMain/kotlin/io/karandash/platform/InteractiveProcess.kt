package io.karandash.platform

import kotlinx.io.Buffer
import kotlinx.io.indexOf
import kotlinx.io.readString

/**
 * A child process started by [PlatformProcess.spawnInteractive], spoken to in UTF-8 lines. Each stream has one
 * owner at a time: one writer, one stdout reader, one stderr reader. All calls block, so run them on
 * `Dispatchers.IO`.
 */
interface InteractiveProcess {
    /** Writes [line] and a newline to the child's stdin; false once stdin is closed or the child is gone. */
    fun writeLine(line: String): Boolean

    /** The next stdout line without its terminator, or null at end of stream. */
    fun readLine(): String?

    /** The next stderr line without its terminator, or null at end of stream. */
    fun readErrorLine(): String?

    /** Closes the child's stdin, so a child reading it sees end of stream. Idempotent. */
    fun closeStdin()

    /** Kills the child if it still runs and reaps it. Idempotent; call it once the child is no longer needed. */
    fun kill()
}

/** Splits a byte stream read by [readChunk] (bytes read into the array, 0 or less at end) into UTF-8 lines. */
internal class ChunkLineReader(
    private val readChunk: (ByteArray) -> Int,
) {
    private val buffer = Buffer()
    private val chunk = ByteArray(CHUNK_SIZE)

    fun readLine(): String? {
        while (true) {
            val newline = buffer.indexOf(NEWLINE)
            if (newline >= 0) {
                val line = buffer.readString(newline)
                buffer.skip(1)
                return line.removeSuffix("\r")
            }
            val read = readChunk(chunk)
            if (read <= 0) return if (buffer.exhausted()) null else buffer.readString()
            buffer.write(chunk, 0, read)
        }
    }

    private companion object {
        const val CHUNK_SIZE = 8192
        const val NEWLINE = '\n'.code.toByte()
    }
}
