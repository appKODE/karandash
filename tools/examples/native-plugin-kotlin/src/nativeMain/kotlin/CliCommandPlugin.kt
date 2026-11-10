@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import platform.posix.strdup

private fun String.toCCliString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Return a JSON array of CLI command descriptors contributed by this plugin.
 *
 * @return Null-terminated UTF-8 JSON array of command descriptor objects.
 */
@CName("karandash_cli_commands")
fun cliCommands(): CPointer<ByteVar> =
    """[{"name":"kotlin-example","help":"Kotlin/Native example command","subcommands":[]}]""".toCCliString()
