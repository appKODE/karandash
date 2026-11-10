package io.karandash.cli.platform

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import io.karandash.crypto.sha256Hex as coreSha256Hex

fun Path.exists(): Boolean = SystemFileSystem.exists(this)

fun Path.writeText(text: String) {
    SystemFileSystem.sink(this).buffered().use { sink ->
        sink.write(text.encodeToByteArray())
        sink.flush()
    }
}

fun Path.readText(): String =
    SystemFileSystem.source(this).buffered().use { source ->
        source.readByteArray().decodeToString()
    }

fun Path.toAbsolutePath(): Path = SystemFileSystem.resolve(this)

fun Path.listDirectory(): List<Path> = SystemFileSystem.list(this).toList()

object PlatformHash {
    fun sha256Hex(input: String): String = coreSha256Hex(input)
}
