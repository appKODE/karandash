@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import platform.posix.strdup

private fun String.toCConfigString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Return the unique identifier for this config format.
 *
 * @return Null-terminated UTF-8 string with the format ID.
 */
@CName("karandash_config_format_id")
fun configFormatId(): CPointer<ByteVar> = "kotlin-example-format".toCConfigString()

/**
 * Return a JSON array of file extensions handled by this config format.
 *
 * @return Null-terminated UTF-8 JSON array of extension strings (without leading dot).
 */
@CName("karandash_config_format_extensions")
fun configFormatExtensions(): CPointer<ByteVar> = """["ktf"]""".toCConfigString()

/**
 * Parse config file content into a JSON object.
 *
 * @param content Null-terminated UTF-8 string with the raw config file content.
 * @return Null-terminated UTF-8 JSON object representing the parsed config.
 */
@CName("karandash_config_parse")
fun configParse(content: CPointer<ByteVar>?): CPointer<ByteVar> {
    // content is available for real parsing; this example ignores it
    content?.let { /* unused in example */ }
    return """{"parsed":true}""".toCConfigString()
}

/**
 * Extract the extends value from config file content.
 *
 * @param content Null-terminated UTF-8 string with the raw config file content.
 * @return Null-terminated UTF-8 string with the base config path, or the literal string "null" if absent.
 */
@CName("karandash_config_parse_extends")
fun configParseExtends(content: CPointer<ByteVar>?): CPointer<ByteVar> {
    // content is available for real parsing; this example returns null (no extends)
    content?.let { /* unused in example */ }
    return "null".toCConfigString()
}
