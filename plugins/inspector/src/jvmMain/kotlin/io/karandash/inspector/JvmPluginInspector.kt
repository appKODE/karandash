package io.karandash.inspector

import io.karandash.api.support.suspendCatching
import io.karandash.loader.wasm.WasmMetadataReader
import kotlinx.io.files.Path
import java.util.jar.JarFile

/**
 * JVM implementation of [PluginInspector].
 *
 * JARs are inspected statically: the inspector reads the `ServiceLoader` registration and the manifest
 * but never loads, instantiates or initializes plugin classes, so installing or listing a plugin runs none of
 * its code. Metadata comes from the `Plugin-Id` / `Plugin-Name` / `Plugin-Version` manifest attributes
 * (falling back to `Implementation-Title` / `Implementation-Version`, then the file name).
 *
 * Non-JAR plugin formats (JS, WASM, native) are accepted by extension only -
 * verification checks that the file is non-empty.
 */
class JvmPluginInspector : PluginInspector {
    override suspend fun readMetadata(path: String): PluginFileMetadata? {
        val file = java.io.File(path)
        if (!file.exists() || !file.isFile) return null

        if (file.extension == "jar") {
            return readJarMetadata(file)
        }

        // For non-JAR formats on JVM: derive metadata from file name
        if (file.length() == 0L) return null
        val id = file.nameWithoutExtension
        return PluginFileMetadata(id = id, name = id, version = "unknown")
    }

    override suspend fun probeMetadata(path: String): PluginFileMetadata? {
        if (!path.endsWith(".wasm") || readMetadata(path) == null) return readMetadata(path)
        return WasmMetadataReader.readMetadata(Path(path))?.let { PluginFileMetadata(it.id, it.name, it.version) }
    }

    private fun readJarMetadata(file: java.io.File): PluginFileMetadata? {
        if (inspectJar(file) != null) return null
        val attributes = openJar(file).getOrNull()?.use { it.manifest?.mainAttributes }
        val id = attributes?.getValue("Plugin-Id")
            ?: attributes?.getValue("Implementation-Title")
            ?: file.nameWithoutExtension
        return PluginFileMetadata(
            id = id,
            name = attributes?.getValue("Plugin-Name") ?: id,
            version = attributes?.getValue("Plugin-Version")
                ?: attributes?.getValue("Implementation-Version")
                ?: "unknown",
        )
    }

    override suspend fun verifyFile(
        path: String,
        extension: String
    ): String? {
        val file = java.io.File(path)

        if (extension == "jar") {
            return inspectJar(file)
        }

        if (file.length() == 0L) {
            return "File is empty: ${file.name}"
        }

        return null
    }

    /** Returns why [file] is not a loadable plugin JAR, or null when it registers at least one present Module class. */
    private fun inspectJar(file: java.io.File): String? {
        val jar = openJar(file).getOrElse { return "Not a valid JAR file: ${file.name} (${it.message})" }
        return jar.use {
            val services = jar
                .getJarEntry(MODULE_SERVICE_ENTRY)
                ?.let { entry -> jar.getInputStream(entry).bufferedReader().readLines() }
                .orEmpty()
                .map { it.substringBefore('#').trim() }
                .filter { it.isNotEmpty() }
            val missing = services.filter { jar.getJarEntry(it.replace('.', '/') + ".class") == null }
            when {
                services.isEmpty() -> "No valid plugin modules found in ${file.name} (no $MODULE_SERVICE_ENTRY entry)"
                missing.isNotEmpty() -> "No valid plugin modules found in ${file.name} (missing classes: ${missing.joinToString()})"
                else -> null
            }
        }
    }

    private fun openJar(file: java.io.File): Result<JarFile> = suspendCatching { JarFile(file) }

    override fun nativeLibraryExtensions(): Set<String> = setOf("dylib", "so", "dll")

    override suspend fun shutdown() {
        // No persistent resources to release
    }

    private companion object {
        const val MODULE_SERVICE_ENTRY = "META-INF/services/io.karandash.api.Module"
    }
}
