package io.karandash.loader

import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.extension
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
import io.karandash.platform.safeName
import io.karandash.platform.userHomeDirectory
import kotlinx.io.files.Path

/**
 * Scans directories for plugin files (native libraries, JavaScript modules).
 *
 * By default, plugins are discovered in `~/.karandash/plugins/`, or in [PLUGIN_DIR_ENV] when it is set.
 * Additional directories can be supplied.
 */
object PluginDiscovery {
    /** Environment variable that replaces the default plugin directory. */
    const val PLUGIN_DIR_ENV = "KARANDASH_PLUGIN_DIR"

    /** Default plugin directory path: [PLUGIN_DIR_ENV] when set and non-blank, else ~/.karandash/plugins/ */
    fun defaultPluginDirPath(env: (String) -> String? = PlatformEnvironment::getEnv): String =
        env(PLUGIN_DIR_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: "${userHomeDirectory()}/.karandash/plugins"

    /** Default plugin directory as Path */
    fun defaultPluginDir(): Path = Path(defaultPluginDirPath())

    /**
     * Discover native plugin libraries (.so, .dylib, .dll) in the given directories.
     *
     * @param directories Directories to scan (non-recursive). Directories
     *   that do not exist are silently skipped.
     * @return List of native library [Path]s sorted by name for deterministic load order.
     */
    fun discoverNativeLibs(directories: List<Path>): List<Path> {
        val extensions = setOf("so", "dylib", "dll")
        return directories
            .filter { it.isDirectory() }
            .flatMap { dir ->
                dir.listFiles()?.filter { file ->
                    file.extension() in extensions && !file.isDirectory()
                } ?: emptyList()
            }.sortedBy { it.safeName }
    }

    /**
     * Discover JavaScript plugin modules (.mjs, .js) in the given directories.
     *
     * @param directories Directories to scan (non-recursive). Directories
     *   that do not exist are silently skipped.
     * @return List of JavaScript module [Path]s sorted by name for deterministic load order.
     */
    fun discoverJsModules(directories: List<Path>): List<Path> {
        val extensions = setOf("mjs", "js")
        return directories
            .filter { it.isDirectory() }
            .flatMap { dir ->
                dir.listFiles()?.filter { file ->
                    file.extension() in extensions && !file.isDirectory()
                } ?: emptyList()
            }.sortedBy { it.safeName }
    }

    /**
     * Discover WASM plugin modules (.wasm files) in the given directories.
     *
     * @param directories Directories to scan (non-recursive). Directories
     *   that do not exist are silently skipped.
     * @return List of WASM module [Path]s sorted by name for deterministic load order.
     */
    fun discoverWasmModules(directories: List<Path>): List<Path> =
        directories
            .filter { it.isDirectory() }
            .flatMap { dir ->
                dir.listFiles()?.filter { file ->
                    file.extension() == "wasm" && !file.isDirectory()
                } ?: emptyList()
            }.sortedBy { it.safeName }
}
