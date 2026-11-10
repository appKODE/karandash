@file:OptIn(ExperimentalNativeApi::class)

package io.karandash.inspector

import io.karandash.loader.jar.JarMetadataReader
import io.karandash.loader.wasm.WasmMetadataReader
import io.karandash.platform.exists
import io.karandash.platform.readText
import io.karandash.platform.safeName
import kotlinx.io.files.Path
import kotlin.experimental.ExperimentalNativeApi

/**
 * Native implementation of [PluginInspector].
 *
 * Inspection is static: the file is read as bytes and never loaded, so installing, verifying or
 * listing a plugin runs none of its code - no `dlopen` (which would run library constructors), no
 * QuickJS evaluation, no WASM instantiation, no JAR subprocess. Like [PluginInspector] on the JVM,
 * metadata comes from the file name; the loaders read the plugin's own id when it is loaded.
 *
 * Verification checks the container format and that the required ABI exports are named in the file:
 * - `.js`/`.mjs`: non-empty
 * - `.wasm`: WASM magic plus `karandash_abi_version` and `karandash_module_id` exports
 * - `.jar`: ZIP magic plus a `META-INF/services/io.karandash.api.Module` entry
 * - native libraries: `karandash_abi_version` and `karandash_module_id` symbols
 */
class NativePluginInspector : PluginInspector {
    override suspend fun readMetadata(path: String): PluginFileMetadata? {
        val file = Path(path)
        if (!file.exists() || verifyFile(path, file.safeName.substringAfterLast('.', "").lowercase()) != null) return null
        val id = file.safeName.substringBeforeLast('.')
        return PluginFileMetadata(id = id, name = id, version = "unknown")
    }

    override suspend fun probeMetadata(path: String): PluginFileMetadata? {
        if (readMetadata(path) == null) return null
        return when (Path(path).safeName.substringAfterLast('.', "").lowercase()) {
            "wasm" -> WasmMetadataReader.readMetadata(Path(path))?.let { PluginFileMetadata(it.id, it.name, it.version) }
            "jar" -> JarMetadataReader.readMetadata(path).firstOrNull()?.let { PluginFileMetadata(it.id, it.name, it.version) }
            else -> readMetadata(path)
        }
    }

    override suspend fun verifyFile(
        path: String,
        extension: String,
    ): String? {
        val file = Path(path)
        if (!file.exists()) return "File not found: $path"
        // limit: decodes the whole file and searches for ASCII names (invalid UTF-8 never swallows ASCII
        // bytes); parse the export/symbol tables if a name-in-a-string-constant false positive ever matters.
        val content = file.readText()
        if (content.isEmpty()) return "File is empty: ${file.safeName}"
        return when (extension) {
            "js", "mjs" -> null

            "wasm" -> if (content.startsWith(WASM_MAGIC)) missingSymbols(content) else "Not a WASM module: ${file.safeName}"

            "jar" -> when {
                !content.startsWith(ZIP_MAGIC) -> {
                    "Not a valid JAR file: ${file.safeName}"
                }

                MODULE_SERVICE_ENTRY !in content -> {
                    "No valid plugin modules found in ${file.safeName} (no $MODULE_SERVICE_ENTRY entry)"
                }

                else -> {
                    null
                }
            }

            else -> missingSymbols(content)
        }
    }

    private fun missingSymbols(content: String): String? =
        REQUIRED_SYMBOLS.firstOrNull { it !in content }?.let { "Missing required symbol: $it" }

    override fun nativeLibraryExtensions(): Set<String> =
        when (Platform.osFamily) {
            OsFamily.MACOSX -> setOf("dylib")
            OsFamily.LINUX -> setOf("so")
            OsFamily.WINDOWS -> setOf("dll")
            else -> emptySet()
        }

    override suspend fun shutdown() {
        // No persistent resources to release
    }

    private companion object {
        const val WASM_MAGIC = "\u0000asm"
        const val ZIP_MAGIC = "PK\u0003\u0004"
        const val MODULE_SERVICE_ENTRY = "META-INF/services/io.karandash.api.Module"
        val REQUIRED_SYMBOLS = listOf("karandash_abi_version", "karandash_module_id")
    }
}
