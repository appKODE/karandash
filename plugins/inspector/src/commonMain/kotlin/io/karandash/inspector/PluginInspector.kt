package io.karandash.inspector

/**
 * Platform-specific plugin file introspection.
 *
 * Implementations read metadata and verify plugin files using
 * platform-appropriate mechanisms (classloader on JVM, dlopen/QuickJS on native).
 */
interface PluginInspector {
    /**
     * Read metadata from a plugin file. Returns null if the file is not a valid plugin.
     */
    suspend fun readMetadata(path: String): PluginFileMetadata?

    /**
     * Reads the identity the plugin itself declares by **running its code**: a WASM guest in the WASM engine,
     * a JAR (on native) in the JAR bridge subprocess, both under the loader's guest-call timeouts. Opt-in only
     * (`plugin verify --load`, `plugin list --probe`); formats with no probe fall back to [readMetadata].
     */
    suspend fun probeMetadata(path: String): PluginFileMetadata? = readMetadata(path)

    /**
     * Verify a plugin file is valid. Returns an error message, or null if valid.
     */
    suspend fun verifyFile(
        path: String,
        extension: String
    ): String?

    /**
     * Native library file extensions for the current OS (e.g. "dylib", "so", "dll").
     */
    fun nativeLibraryExtensions(): Set<String>

    /**
     * Release any resources held by this inspector.
     */
    suspend fun shutdown()
}
